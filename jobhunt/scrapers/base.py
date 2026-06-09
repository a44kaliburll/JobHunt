"""Scraper plugin interface."""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import httpx

from .. import config

log = logging.getLogger(__name__)


@dataclass
class JobPosting:
    """One job listing as returned by a scraper, before scoring/dedupe."""

    key: str          # globally unique per user, e.g. "li:4412327417"
    title: str
    company: str = ""
    location: str = ""
    url: str = ""
    source: str = ""
    posted: str = ""       # ISO date string or ""
    description: str = ""  # used for scoring only, not stored
    tags: list[str] = field(default_factory=list)


@dataclass
class SearchQuery:
    keywords: str
    location: str = ""  # "" or "Remote" or "City, ST"


class Scraper:
    """Base class. Subclasses implement ``search`` for one query."""

    name = "base"
    key_prefix = "x"

    def __init__(self, client: httpx.Client | None = None):
        self._client = client

    @property
    def client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                timeout=config.HTTP_TIMEOUT,
                headers={"User-Agent": config.USER_AGENT},
                follow_redirects=True,
            )
        return self._client

    def search(self, query: SearchQuery) -> list[JobPosting]:
        raise NotImplementedError

    def safe_search(self, query: SearchQuery) -> tuple[list[JobPosting], str | None]:
        """Run search, never raising; returns (postings, error_or_None)."""
        try:
            return self.search(query), None
        except Exception as exc:  # network errors, parse errors, blocks
            log.warning("%s scraper failed for %r: %s", self.name, query, exc)
            return [], f"{self.name}: {exc}"
