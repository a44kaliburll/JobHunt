"""Match scoring and query building.

Emulates the original artex_job_hunt scoring: each listing gets a
deterministic score out of 50, rendered as ``[████░░░░░░] 38% (19/50)``.

Components (max 50):
- title overlap with the user's resume-derived titles ........ up to 20
- skills found in the listing title/description/tags ......... up to 15
- duty keywords found in the description ..................... up to 10
- certifications mentioned ................................... up to 5
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from . import config
from .scrapers.base import JobPosting, SearchQuery

STOPWORDS = {
    "of", "and", "the", "a", "an", "for", "to", "in", "at", "on", "with",
    "or", "&", "-", "i", "ii", "iii", "sr", "jr", "senior", "junior",
}


def _tokens(text: str) -> set[str]:
    return {
        t
        for t in re.findall(r"[a-z0-9+#/.]+", text.lower())
        if t not in STOPWORDS and len(t) > 1
    }


@dataclass
class ScoreBreakdown:
    title: int = 0
    skills: int = 0
    duties: int = 0
    certifications: int = 0

    @property
    def total(self) -> int:
        return min(
            config.MAX_SCORE,
            self.title + self.skills + self.duties + self.certifications,
        )


def score_posting(posting: JobPosting, profile: dict) -> ScoreBreakdown:
    """Score one posting against the merged resume profile."""
    breakdown = ScoreBreakdown()
    title_tokens = _tokens(posting.title)
    haystack = " ".join(
        [posting.title, posting.description, " ".join(posting.tags)]
    ).lower()

    # Title overlap: best overlap ratio against any profile title.
    best_ratio = 0.0
    for profile_title in profile.get("titles", []):
        pt = _tokens(profile_title)
        if not pt:
            continue
        overlap = len(pt & title_tokens) / len(pt)
        best_ratio = max(best_ratio, overlap)
    breakdown.title = round(best_ratio * 20)

    # Skills: 3 points each, capped at 15.
    hits = 0
    for skill in profile.get("skills", []):
        pattern = r"(?<![\w+#])" + re.escape(skill.lower()) + r"(?![\w+#])"
        if re.search(pattern, haystack):
            hits += 1
    breakdown.skills = min(15, hits * 3)

    # Duties: shared meaningful words between duty bullets and description.
    if posting.description:
        duty_tokens: set[str] = set()
        for duty in profile.get("duties", []):
            duty_tokens |= _tokens(duty)
        desc_tokens = _tokens(posting.description)
        shared = len(duty_tokens & desc_tokens)
        breakdown.duties = min(10, shared // 3)

    # Certifications: 5 if any cert is mentioned.
    for cert in profile.get("certifications", []):
        if cert.lower() in haystack:
            breakdown.certifications = 5
            break

    return breakdown


def match_bar(score: int, max_score: int = config.MAX_SCORE) -> str:
    """Render the artex-style match bar, e.g. '[████░░░░░░] 38% (19/50)'."""
    pct = round(score / max_score * 100)
    filled = round(score / max_score * 10)
    return f"[{'█' * filled}{'░' * (10 - filled)}] {pct}% ({score}/{max_score})"


def location_in_range(posting_location: str, preferred: list[str]) -> bool:
    """True if the posting's location matches any preferred location.

    Matching is loose: a preferred location matches if its city token
    appears in the posting location, and 'Remote' matches the usual
    remote/anywhere/flexible phrasings. No preferences = everything passes.
    """
    if not preferred:
        return True
    loc = posting_location.lower()
    for pref in preferred:
        p = pref.lower().strip()
        if not p:
            continue
        if p in {"remote", "anywhere"}:
            if any(
                w in loc for w in ("remote", "anywhere", "flexible", "worldwide")
            ) or loc == "":
                return True
            continue
        city = p.split(",")[0].strip()
        if city and city in loc:
            return True
    return False


def build_queries(profile: dict, locations: list[str]) -> list[SearchQuery]:
    """Fan out (title x location) queries, like the artex cron did."""
    titles = profile.get("titles", [])[:8]
    if not titles:
        # Fall back to top skills as keywords so new users still get results.
        titles = profile.get("skills", [])[:4]
    locs = locations or ["Remote"]
    queries = [
        SearchQuery(keywords=title, location=loc)
        for title in titles
        for loc in locs
    ]
    return queries[: config.MAX_QUERIES_PER_SOURCE]
