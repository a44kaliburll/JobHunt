"""Scraper registry: built-in boards plus user-defined sources."""
from __future__ import annotations

import httpx

from .base import JobPosting, Scraper, SearchQuery
from .custom import CustomSourceScraper
from .linkedin import LinkedInScraper
from .remoteok import RemoteOKScraper
from .themuse import TheMuseScraper
from .weworkremotely import WeWorkRemotelyScraper

BUILTIN_SCRAPERS: tuple[type[Scraper], ...] = (
    LinkedInScraper,
    RemoteOKScraper,
    WeWorkRemotelyScraper,
    TheMuseScraper,
)

__all__ = [
    "JobPosting",
    "Scraper",
    "SearchQuery",
    "BUILTIN_SCRAPERS",
    "CustomSourceScraper",
    "build_scrapers",
]


def build_scrapers(custom_sources, client: httpx.Client | None = None) -> list[Scraper]:
    """Instantiate all built-in scrapers plus the user's enabled custom ones."""
    scrapers: list[Scraper] = [cls(client) for cls in BUILTIN_SCRAPERS]
    for src in custom_sources:
        if getattr(src, "enabled", True):
            scrapers.append(
                CustomSourceScraper(src.id, src.name, src.kind, src.config, client)
            )
    return scrapers
