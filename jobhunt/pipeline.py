"""The scrape -> score -> dedupe -> report pipeline.

This is the generalized, multi-user version of the artex_job_hunt cron:

  1. Merge all of a user's parsed resumes into one search profile.
  2. Build (title x location) queries and fan them out across every
     enabled scraper (built-in boards + the user's custom sources).
  3. Funnel: fetched -> in range (location filter) -> relevant (score
     >= user's min score) -> new (not already in the user's listings).
  4. Upsert listings (90-day retention by first_seen), write the daily
     digest and the current working list, and log the run.
"""
from __future__ import annotations

import datetime as dt
import logging
from dataclasses import dataclass, field

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import config, reports
from .db import JobSource, Listing, Resume, RunLog, User
from .matching import build_queries, location_in_range, score_posting
from .resume.parser import merge_profiles
from .scrapers import build_scrapers
from .scrapers.base import JobPosting

log = logging.getLogger(__name__)


@dataclass
class RunResult:
    user_id: int
    date: str
    fetched: int = 0
    in_range: int = 0
    relevant: int = 0
    new_count: int = 0
    queries: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    new_listings: list[Listing] = field(default_factory=list)
    digest_path: str = ""

    @property
    def funnel(self) -> str:
        return (
            f"{self.fetched} fetched -> {self.in_range} in range -> "
            f"{self.relevant} relevant -> {self.new_count} new this run"
        )


def get_profile(db: Session, user: User) -> dict:
    resumes = db.scalars(
        select(Resume).where(Resume.user_id == user.id)
    ).all()
    return merge_profiles([r.parsed for r in resumes], user.extra_titles)


def run_for_user(
    db: Session,
    user: User,
    client: httpx.Client | None = None,
    today: dt.date | None = None,
) -> RunResult:
    today = today or dt.date.today()
    result = RunResult(user_id=user.id, date=today.isoformat())

    profile = get_profile(db, user)
    if not any(profile.get(k) for k in ("titles", "skills")):
        result.errors.append(
            "No resume data: upload at least one resume so queries can be built."
        )
        return result

    custom_sources = db.scalars(
        select(JobSource).where(JobSource.user_id == user.id, JobSource.enabled)
    ).all()
    scrapers = build_scrapers(custom_sources, client)
    queries = build_queries(profile, user.locations)

    existing_keys = set(
        db.scalars(select(Listing.key).where(Listing.user_id == user.id)).all()
    )

    seen_this_run: dict[str, JobPosting] = {}
    for scraper in scrapers:
        for query in queries:
            postings, error = scraper.safe_search(query)
            result.queries.append(
                f"{scraper.name}: {query.keywords}"
                + (f" @ {query.location}" if query.location else "")
            )
            if error:
                result.errors.append(error)
            for posting in postings:
                result.fetched += 1
                if posting.key not in seen_this_run:
                    seen_this_run[posting.key] = posting

    # Funnel: location filter, then score threshold.
    min_score = user.min_score or config.DEFAULT_MIN_SCORE
    scored: list[tuple[JobPosting, int]] = []
    for posting in seen_this_run.values():
        if not location_in_range(posting.location, user.locations):
            continue
        result.in_range += 1
        score = score_posting(posting, profile).total
        if score >= min_score:
            result.relevant += 1
            scored.append((posting, score))

    # Reset NEW flags from the previous run, then upsert.
    for listing in db.scalars(
        select(Listing).where(Listing.user_id == user.id, Listing.is_new)
    ):
        listing.is_new = False

    for posting, score in scored:
        if posting.key in existing_keys:
            existing = db.scalar(
                select(Listing).where(
                    Listing.user_id == user.id, Listing.key == posting.key
                )
            )
            if existing is not None:
                existing.score = max(existing.score, score)
            continue
        listing = Listing(
            user_id=user.id,
            key=posting.key,
            title=posting.title[:512],
            company=posting.company[:512],
            location=posting.location[:512],
            url=posting.url,
            source=posting.source,
            posted=posting.posted[:32],
            score=score,
            first_seen=today.isoformat(),
            is_new=True,
        )
        db.add(listing)
        result.new_listings.append(listing)
        result.new_count += 1

    # Retention purge.
    cutoff = (today - dt.timedelta(days=config.RETENTION_DAYS)).isoformat()
    for stale in db.scalars(
        select(Listing).where(Listing.user_id == user.id, Listing.first_seen < cutoff)
    ):
        db.delete(stale)

    run_log = RunLog(
        user_id=user.id,
        fetched=result.fetched,
        in_range=result.in_range,
        relevant=result.relevant,
        new_count=result.new_count,
    )
    run_log.queries = result.queries
    db.add(run_log)
    db.commit()

    result.digest_path = str(reports.write_reports(db, user, result))
    return result


def run_all_users(db: Session, client: httpx.Client | None = None) -> list[RunResult]:
    results = []
    for user in db.scalars(select(User)).all():
        try:
            results.append(run_for_user(db, user, client))
        except Exception:
            log.exception("Run failed for user %s", user.email)
            db.rollback()
    return results
