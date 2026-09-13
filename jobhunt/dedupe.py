"""Collapses the same job showing up more than once.

Two shapes of duplicate turn up constantly in real runs:

- **Reposts.** A continuously-recruited req is re-advertised under a fresh
  vacancy id every few weeks, so the same role arrives three or four times.
- **Cross-posts.** An aggregator re-lists an employer's job under its own
  name, so the same role arrives once as "Regeneron" and once as "BioSpace".

Neither is caught by keying on ``source:id``, because every copy has a
different id. Grouping instead keys on the title and location.

Company is deliberately left out of the key for *distinctive* titles, which is
what lets a cross-post collapse across two different company names. For short,
generic titles ("Project Manager") that would be too eager, so those stay
company-scoped and only collapse against reposts of themselves.

This mirrors ``com.jobhunt.core.Dedupe`` in the Android module; the two are
kept deliberately in step.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .matching import _tokens
from .scrapers.base import JobPosting

#: Title tokens needed before a title is trusted to identify a job on its own.
DISTINCTIVE_TOKENS = 3

REMOTE_WORDS = ("remote", "anywhere", "worldwide", "flexible")

#: Sorts after every real date, so undated postings lose the tie-break.
_UNDATED = "9999-99-99"


@dataclass
class Merged:
    """A canonical posting plus the duplicates folded into it."""

    posting: JobPosting
    score: int
    group_key: str
    #: Names the same job was also advertised under.
    also_posted_by: list[str] = field(default_factory=list)


def is_distinctive_title(title: str) -> bool:
    return len(_tokens(title)) >= DISTINCTIVE_TOKENS


def normalize_location(location: str) -> str:
    """Loose enough that "Albany County, NY" and "Albany, NY" agree, and that
    any flavour of remote agrees with any other. Not a geocoder."""
    lowered = location.lower()
    if any(word in lowered for word in REMOTE_WORDS):
        return "remote"
    cleaned = re.sub(r"\bcounty\b", " ", lowered)
    cleaned = re.sub(r"\b(united states|usa)\b", " ", cleaned)
    cleaned = re.sub(r"[^a-z0-9]+", " ", cleaned)
    return cleaned.strip()


def group_key(title: str, location: str, company: str = "") -> str:
    # Sorted tokens, so "Online Learning Director" and "Director of Online
    # Learning" land on the same key.
    title_key = " ".join(sorted(_tokens(title)))
    location_key = normalize_location(location)
    if is_distinctive_title(title):
        return f"{title_key}|{location_key}"
    return f"{title_key}|{location_key}|{company.strip().lower()}"


def key_for(posting: JobPosting) -> str:
    return group_key(posting.title, posting.location, posting.company)


def collapse(scored: list[tuple[JobPosting, int]]) -> list[Merged]:
    """Group scored postings, keeping one per job.

    The copy kept is the one that scores highest — which favours a posting
    carrying a real description over a bare search-result card — then the
    earliest advertised, then whichever was seen first.
    """
    groups: dict[str, list[tuple[int, JobPosting, int]]] = {}
    for index, (posting, score) in enumerate(scored):
        groups.setdefault(key_for(posting), []).append((index, posting, score))

    merged: list[Merged] = []
    for key, members in groups.items():
        index, posting, score = min(
            members,
            key=lambda m: (-m[2], m[1].posted or _UNDATED, m[0]),
        )
        alternates: list[str] = []
        for other_index, other, _ in members:
            if other_index == index:
                continue
            name = other.company or other.source
            if name and name.lower() != posting.company.lower() and name not in alternates:
                alternates.append(name)
        merged.append(Merged(posting, score, key, alternates))
    return merged
