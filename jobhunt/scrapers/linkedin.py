"""LinkedIn guest job search scraper.

Uses the public (logged-out) jobs-guest endpoint that powers LinkedIn's
"see more job postings" infinite scroll. Same approach as the original
artex_job_hunt cron. Restricted to the last 7 days (f_TPR=r604800) since
the pipeline runs on a schedule and dedupes anyway.
"""
from __future__ import annotations

import re
from urllib.parse import urlencode

from bs4 import BeautifulSoup

from .base import JobPosting, Scraper, SearchQuery

SEARCH_URL = (
    "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
)
DETAIL_URL = "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting"
JOB_ID_RE = re.compile(r"-?(\d{6,})(?:\?|$)")


class LinkedInScraper(Scraper):
    name = "LinkedIn"
    key_prefix = "li"

    # Guest search returns cards only — title, company, location, date — so a
    # LinkedIn listing would otherwise be scored on its title alone, leaving
    # the skills and duties halves of the score permanently unreachable.
    supports_descriptions = True

    def search(self, query: SearchQuery) -> list[JobPosting]:
        params = {
            "keywords": query.keywords,
            "location": query.location or "Remote",
            "f_TPR": "r604800",
            "start": "0",
        }
        resp = self.client.get(f"{SEARCH_URL}?{urlencode(params)}")
        resp.raise_for_status()
        return self._parse(resp.text)

    def _parse(self, html: str) -> list[JobPosting]:
        soup = BeautifulSoup(html, "html.parser")
        postings: list[JobPosting] = []
        for card in soup.select("li"):
            link = card.select_one("a.base-card__full-link") or card.select_one("a")
            title_el = card.select_one("h3")
            if not link or not title_el:
                continue
            url = link.get("href", "").split("?")[0]
            job_id_match = JOB_ID_RE.search(url)
            if not job_id_match:
                continue
            company_el = card.select_one("h4")
            location_el = card.select_one(
                ".job-search-card__location, .base-search-card__metadata span"
            )
            time_el = card.select_one("time")
            postings.append(
                JobPosting(
                    key=f"{self.key_prefix}:{job_id_match.group(1)}",
                    title=title_el.get_text(strip=True),
                    company=company_el.get_text(strip=True) if company_el else "",
                    location=location_el.get_text(strip=True) if location_el else "",
                    url=url,
                    source=self.name,
                    posted=(time_el.get("datetime", "") if time_el else ""),
                )
            )
        return postings

    def describe(self, posting: JobPosting) -> str | None:
        job_id = posting.key.split(":", 1)[-1]
        if not job_id:
            return None
        resp = self.client.get(f"{DETAIL_URL}/{job_id}")
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, "html.parser")
        body = soup.select_one(".show-more-less-html__markup, .description__text")
        if body is None:
            return None
        # Criteria (seniority, employment type, function) sit outside the body
        # but carry vocabulary worth scoring against.
        criteria = " ".join(
            el.get_text(" ", strip=True)
            for el in soup.select(".description__job-criteria-item")
        )
        text = " ".join(
            part for part in (body.get_text(" ", strip=True), criteria) if part
        )
        return text[:4000] or None
