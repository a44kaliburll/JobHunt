"""Markdown report generation, emulating the artex_job_hunt output files.

Per user, per run we write to ``data/reports/<user_id>/``:

- ``YYYY-MM-DD.md``        — daily digest: funnel, TL;DR, top matches, queries
- ``current_listings.md``  — rolling working list grouped by first-seen date,
                             newest first, with NEW markers and match bars
"""
from __future__ import annotations

import datetime as dt
from collections import Counter
from pathlib import Path
from typing import TYPE_CHECKING

from sqlalchemy import select

from . import config
from .db import Listing, User
from .matching import match_bar

if TYPE_CHECKING:
    from .pipeline import RunResult


def user_reports_dir(user_id: int) -> Path:
    path = config.REPORTS_DIR / str(user_id)
    path.mkdir(parents=True, exist_ok=True)
    return path


def render_digest(user: User, result: "RunResult", top: list[Listing]) -> str:
    lines = [
        f"# Job Hunt - {result.date}",
        "",
        "**Run mode:** deterministic pipeline (no LLM)",
        f"**Funnel:** {result.funnel}",
        f"**Dropped — out of range:** {result.fetched - result.in_range} | "
        f"**Dropped — no resume fit:** {result.in_range - result.relevant}",
        "",
        "## TL;DR",
    ]
    if top:
        best = top[0]
        lines.append(
            f"- Best match: **{best.title}** at {best.company} — "
            f"{match_bar(best.score)} ({best.location})."
        )
        lines.append(f"- {result.new_count} new listing(s) in this digest.")
        source_counts = Counter(l.source for l in top)
        lines.append(
            "- Sources represented in top matches: "
            + ", ".join(f"{s}: {n}" for s, n in source_counts.most_common())
        )
    else:
        lines.append("- No new matching listings this run.")
    lines += ["", "## Top matches"]
    for i, listing in enumerate(top, 1):
        lines += [
            f"### {i}. {listing.title} - {listing.company} ({listing.location})",
            f"- **Match:** {match_bar(listing.score)}",
            f"- **Posted:** {listing.posted or 'unknown'} | "
            f"**Source:** {listing.source}",
            f"- **Apply:** {listing.url}",
            "",
        ]
    if result.errors:
        lines += ["## Warnings"]
        lines += [f"- {e}" for e in result.errors]
        lines.append("")
    lines += ["## Queries fanned"]
    lines += [f"- {q}" for q in result.queries]
    return "\n".join(lines) + "\n"


def render_current_listings(user: User, listings: list[Listing], now: str) -> str:
    lines = [
        "# Job Hunt — Working List",
        "",
        f"_Last refreshed: {now}_",
        f"_Active listings: **{len(listings)}** "
        f"({config.RETENTION_DAYS}-day retention)_",
        "",
        "Sorted by date first seen, newest first. "
        "**NEW** = surfaced in the most recent run.",
        "",
    ]
    current_date = None
    for listing in listings:
        if listing.first_seen != current_date:
            current_date = listing.first_seen
            lines += [f"## {current_date}", ""]
        marker = "**🆕 NEW** — " if listing.is_new else ""
        lines += [
            f"### {marker}{listing.title} — {listing.company}",
            f"- **Match:** {match_bar(listing.score)}",
            f"- **Location:** {listing.location or 'unknown'} | "
            f"**Source:** {listing.source} | **Posted:** {listing.posted or 'unknown'}",
            f"- **Apply:** {listing.url}",
            "",
        ]
    return "\n".join(lines) + "\n"


def write_reports(db, user: User, result: "RunResult") -> Path:
    """Write both report files; returns the digest path."""
    top = sorted(result.new_listings, key=lambda l: l.score, reverse=True)[:10]
    digest = render_digest(user, result, top)

    listings = db.scalars(
        select(Listing)
        .where(Listing.user_id == user.id, Listing.status != "hidden")
        .order_by(Listing.first_seen.desc(), Listing.score.desc())
    ).all()
    now = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    working_list = render_current_listings(user, listings, now)

    out_dir = user_reports_dir(user.id)
    digest_path = out_dir / f"{result.date}.md"
    digest_path.write_text(digest, encoding="utf-8")
    (out_dir / "current_listings.md").write_text(working_list, encoding="utf-8")
    return digest_path
