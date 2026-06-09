"""The Muse public jobs API scraper (https://www.themuse.com/developers/api/v2)."""
from __future__ import annotations

from urllib.parse import urlencode

from .base import JobPosting, Scraper, SearchQuery

API_URL = "https://www.themuse.com/api/public/jobs"


class TheMuseScraper(Scraper):
    name = "TheMuse"
    key_prefix = "tm"

    def search(self, query: SearchQuery) -> list[JobPosting]:
        params: dict[str, str] = {"page": "0"}
        if query.location and query.location.lower() != "remote":
            params["location"] = query.location
        elif query.location.lower() == "remote":
            params["location"] = "Flexible / Remote"
        resp = self.client.get(f"{API_URL}?{urlencode(params)}")
        resp.raise_for_status()
        data = resp.json()
        keywords = [w for w in query.keywords.lower().split() if len(w) > 2]
        postings: list[JobPosting] = []
        for item in data.get("results", []):
            name = str(item.get("name", ""))
            contents = str(item.get("contents", ""))[:4000]
            haystack = f"{name} {contents}".lower()
            if keywords and not any(k in haystack for k in keywords):
                continue
            locations = ", ".join(
                loc.get("name", "") for loc in item.get("locations", [])
            )
            postings.append(
                JobPosting(
                    key=f"{self.key_prefix}:{item.get('id', '')}",
                    title=name,
                    company=str((item.get("company") or {}).get("name", "")),
                    location=locations,
                    url=str(((item.get("refs") or {}).get("landing_page")) or ""),
                    source=self.name,
                    posted=str(item.get("publication_date", ""))[:10],
                    description=contents,
                )
            )
        return postings
