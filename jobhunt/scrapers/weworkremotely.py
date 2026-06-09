"""We Work Remotely search scraper (HTML, https://weworkremotely.com)."""
from __future__ import annotations

import re
from urllib.parse import urlencode, urljoin

from bs4 import BeautifulSoup

from .base import JobPosting, Scraper, SearchQuery

BASE_URL = "https://weworkremotely.com"
SEARCH_URL = f"{BASE_URL}/remote-jobs/search"


class WeWorkRemotelyScraper(Scraper):
    name = "WeWorkRemotely"
    key_prefix = "wwr"

    def search(self, query: SearchQuery) -> list[JobPosting]:
        resp = self.client.get(f"{SEARCH_URL}?{urlencode({'term': query.keywords})}")
        resp.raise_for_status()
        return self._parse(resp.text)

    def _parse(self, html: str) -> list[JobPosting]:
        soup = BeautifulSoup(html, "html.parser")
        postings: list[JobPosting] = []
        seen: set[str] = set()
        for anchor in soup.select("section.jobs li a[href*='/remote-jobs/']"):
            href = anchor.get("href", "")
            slug_match = re.search(r"/remote-jobs/([^/?#]+)", href)
            if not slug_match:
                continue
            slug = slug_match.group(1)
            if slug in seen or slug == "search":
                continue
            title_el = anchor.select_one(".title, span.title")
            company_el = anchor.select_one(".company, span.company")
            region_el = anchor.select_one(".region, span.region")
            if not title_el:
                continue
            seen.add(slug)
            postings.append(
                JobPosting(
                    key=f"{self.key_prefix}:{slug}",
                    title=title_el.get_text(strip=True),
                    company=company_el.get_text(strip=True) if company_el else "",
                    location=(
                        region_el.get_text(strip=True) if region_el else "Remote"
                    ),
                    url=urljoin(BASE_URL, href),
                    source=self.name,
                )
            )
        return postings
