"""RemoteOK public API scraper (https://remoteok.com/api).

The API returns the entire board; we filter by keywords locally. The first
element of the response is a legal notice, not a job.
"""
from __future__ import annotations

import datetime as dt

from .base import JobPosting, Scraper, SearchQuery

API_URL = "https://remoteok.com/api"


class RemoteOKScraper(Scraper):
    name = "RemoteOK"
    key_prefix = "ro"

    def search(self, query: SearchQuery) -> list[JobPosting]:
        resp = self.client.get(API_URL)
        resp.raise_for_status()
        data = resp.json()
        keywords = [w for w in query.keywords.lower().split() if len(w) > 2]
        postings: list[JobPosting] = []
        for item in data:
            if not isinstance(item, dict) or "position" not in item:
                continue  # legal notice / malformed rows
            haystack = " ".join(
                [
                    str(item.get("position", "")),
                    " ".join(item.get("tags") or []),
                    str(item.get("description", ""))[:2000],
                ]
            ).lower()
            if keywords and not any(k in haystack for k in keywords):
                continue
            posted = ""
            raw_date = item.get("date", "")
            try:
                posted = dt.datetime.fromisoformat(
                    str(raw_date).replace("Z", "+00:00")
                ).date().isoformat()
            except (ValueError, TypeError):
                pass
            postings.append(
                JobPosting(
                    key=f"{self.key_prefix}:{item.get('id', '')}",
                    title=str(item.get("position", "")),
                    company=str(item.get("company", "")),
                    location=str(item.get("location") or "Remote"),
                    url=str(item.get("url", "")),
                    source=self.name,
                    posted=posted,
                    description=str(item.get("description", ""))[:4000],
                    tags=[str(t) for t in (item.get("tags") or [])],
                )
            )
        return postings
