"""User-defined job source scraper.

Lets any user add their own job site without writing code. Three kinds:

``rss``  — any RSS/Atom feed of job postings::

    {"url": "https://example.com/jobs.rss?q={query}"}

``json`` — a JSON API. ``list_path`` is a dot-path to the array of jobs and
``fields`` maps posting fields to dot-paths inside each item::

    {
      "url": "https://example.com/api/jobs?q={query}",
      "list_path": "results",
      "fields": {
        "id": "id", "title": "name", "company": "company.name",
        "location": "location", "url": "links.apply", "posted": "published"
      }
    }

``html`` — a search results page scraped with CSS selectors::

    {
      "url": "https://example.com/jobs?q={query}",
      "selectors": {
        "item": "li.job", "title": ".job-title", "company": ".employer",
        "location": ".place", "url": "a", "posted": "time"
      }
    }

In every ``url``, ``{query}`` is replaced with the URL-encoded search
keywords and ``{location}`` with the URL-encoded location. URLs without a
``{query}`` placeholder are fetched once and filtered by keywords locally.
"""
from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from typing import Any
from urllib.parse import quote_plus, urljoin

from bs4 import BeautifulSoup

from .base import JobPosting, Scraper, SearchQuery


def _dot_get(obj: Any, path: str) -> Any:
    """Resolve 'a.b.0.c' style paths in nested dicts/lists."""
    if not path:
        return None
    current = obj
    for part in path.split("."):
        if isinstance(current, dict):
            current = current.get(part)
        elif isinstance(current, list) and part.isdigit():
            idx = int(part)
            current = current[idx] if idx < len(current) else None
        else:
            return None
        if current is None:
            return None
    return current


def _stable_key(prefix: str, *parts: str) -> str:
    digest = hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()[:12]
    return f"{prefix}:{digest}"


class CustomSourceScraper(Scraper):
    """Scraper driven entirely by a JobSource row's JSON config."""

    def __init__(self, source_id: int, name: str, kind: str, cfg: dict, client=None):
        super().__init__(client)
        self.name = name
        self.kind = kind
        self.cfg = cfg
        self.key_prefix = f"u{source_id}"

    def _build_url(self, query: SearchQuery) -> str:
        url = self.cfg.get("url", "")
        return url.replace("{query}", quote_plus(query.keywords)).replace(
            "{location}", quote_plus(query.location)
        )

    def _needs_local_filter(self) -> bool:
        return "{query}" not in self.cfg.get("url", "")

    def _filter(self, postings: list[JobPosting], query: SearchQuery) -> list[JobPosting]:
        if not self._needs_local_filter():
            return postings
        keywords = [w for w in query.keywords.lower().split() if len(w) > 2]
        if not keywords:
            return postings
        return [
            p
            for p in postings
            if any(
                k in f"{p.title} {p.description}".lower() for k in keywords
            )
        ]

    def search(self, query: SearchQuery) -> list[JobPosting]:
        url = self._build_url(query)
        if not url:
            return []
        resp = self.client.get(url)
        resp.raise_for_status()
        if self.kind == "rss":
            postings = self._parse_rss(resp.text)
        elif self.kind == "json":
            postings = self._parse_json(resp.json())
        elif self.kind == "html":
            postings = self._parse_html(resp.text, base_url=url)
        else:
            raise ValueError(f"Unknown custom source kind: {self.kind!r}")
        return self._filter(postings, query)

    # --- RSS / Atom ---

    def _parse_rss(self, text: str) -> list[JobPosting]:
        root = ET.fromstring(text)
        postings: list[JobPosting] = []
        # RSS 2.0 <item> and Atom <entry>
        items = root.findall(".//item") + root.findall(
            ".//{http://www.w3.org/2005/Atom}entry"
        )
        for item in items:
            title = _xml_text(item, "title")
            link = _xml_text(item, "link")
            if not link:  # Atom: <link href="...">
                link_el = item.find("{http://www.w3.org/2005/Atom}link")
                if link_el is not None:
                    link = link_el.get("href", "")
            guid = _xml_text(item, "guid") or link or title
            posted = _xml_text(item, "pubDate") or _xml_text(item, "updated")
            description = re.sub(
                r"<[^>]+>", " ", _xml_text(item, "description")
            )[:4000]
            if not title:
                continue
            postings.append(
                JobPosting(
                    key=_stable_key(self.key_prefix, guid),
                    title=title,
                    url=link,
                    source=self.name,
                    posted=_normalize_date(posted),
                    description=description,
                )
            )
        return postings

    # --- JSON API ---

    def _parse_json(self, data: Any) -> list[JobPosting]:
        items = _dot_get(data, self.cfg.get("list_path", "")) if self.cfg.get(
            "list_path"
        ) else data
        if not isinstance(items, list):
            return []
        fields = self.cfg.get("fields", {})
        postings: list[JobPosting] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            title = str(_dot_get(item, fields.get("title", "title")) or "")
            if not title:
                continue
            url = str(_dot_get(item, fields.get("url", "url")) or "")
            external_id = str(_dot_get(item, fields.get("id", "id")) or "")
            postings.append(
                JobPosting(
                    key=(
                        f"{self.key_prefix}:{external_id}"
                        if external_id
                        else _stable_key(self.key_prefix, url or title)
                    ),
                    title=title,
                    company=str(_dot_get(item, fields.get("company", "company")) or ""),
                    location=str(
                        _dot_get(item, fields.get("location", "location")) or ""
                    ),
                    url=url,
                    source=self.name,
                    posted=_normalize_date(
                        str(_dot_get(item, fields.get("posted", "posted")) or "")
                    ),
                    description=str(
                        _dot_get(item, fields.get("description", "description")) or ""
                    )[:4000],
                )
            )
        return postings

    # --- HTML with CSS selectors ---

    def _parse_html(self, html: str, base_url: str) -> list[JobPosting]:
        selectors = self.cfg.get("selectors", {})
        item_sel = selectors.get("item")
        if not item_sel:
            raise ValueError("html source config requires selectors.item")
        soup = BeautifulSoup(html, "html.parser")
        postings: list[JobPosting] = []
        for card in soup.select(item_sel):
            title = _sel_text(card, selectors.get("title"))
            if not title:
                continue
            url = ""
            url_sel = selectors.get("url")
            link_el = card.select_one(url_sel) if url_sel else None
            if link_el is None and card.name == "a":
                link_el = card
            if link_el is not None:
                url = urljoin(base_url, link_el.get("href", ""))
            postings.append(
                JobPosting(
                    key=_stable_key(self.key_prefix, url or title),
                    title=title,
                    company=_sel_text(card, selectors.get("company")),
                    location=_sel_text(card, selectors.get("location")),
                    url=url,
                    source=self.name,
                    posted=_normalize_date(_sel_text(card, selectors.get("posted"))),
                )
            )
        return postings


def _xml_text(item: ET.Element, tag: str) -> str:
    el = item.find(tag)
    if el is None:
        el = item.find(f"{{http://www.w3.org/2005/Atom}}{tag}")
    return (el.text or "").strip() if el is not None else ""


def _sel_text(card, selector: str | None) -> str:
    if not selector:
        return ""
    el = card.select_one(selector)
    return el.get_text(strip=True) if el else ""


def _normalize_date(raw: str) -> str:
    """Best-effort: pull an ISO-like date out of common date formats."""
    if not raw:
        return ""
    iso = re.search(r"\d{4}-\d{2}-\d{2}", raw)
    if iso:
        return iso.group(0)
    # RFC 822: "Mon, 18 May 2026 09:00:00 GMT"
    rfc = re.search(r"(\d{1,2}) (\w{3}) (\d{4})", raw)
    if rfc:
        months = {
            "Jan": 1, "Feb": 2, "Mar": 3, "Apr": 4, "May": 5, "Jun": 6,
            "Jul": 7, "Aug": 8, "Sep": 9, "Oct": 10, "Nov": 11, "Dec": 12,
        }
        month = months.get(rfc.group(2))
        if month:
            return f"{rfc.group(3)}-{month:02d}-{int(rfc.group(1)):02d}"
    return ""
