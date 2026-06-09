import httpx

from jobhunt.scrapers.base import SearchQuery
from jobhunt.scrapers.custom import CustomSourceScraper

RSS_FEED = """<?xml version="1.0"?>
<rss version="2.0"><channel>
  <title>Acme Jobs</title>
  <item>
    <title>Senior Welding Technician</title>
    <link>https://acme.test/jobs/42</link>
    <guid>42</guid>
    <pubDate>Mon, 18 May 2026 09:00:00 GMT</pubDate>
    <description>&lt;p&gt;Welding and CNC work.&lt;/p&gt;</description>
  </item>
</channel></rss>
"""

JSON_API = {
    "data": {
        "jobs": [
            {
                "jid": 7,
                "name": "Curriculum Developer",
                "org": {"title": "EduCo"},
                "city": "Albany, NY",
                "links": {"apply": "https://educo.test/7"},
                "published": "2026-06-01T08:00:00Z",
            }
        ]
    }
}

HTML_PAGE = """
<html><body><ul>
  <li class="job">
    <a href="/jobs/teacher-1"><span class="t">Lead Teacher</span></a>
    <span class="c">Little Sprouts</span><span class="l">Troy, NY</span>
  </li>
</ul></body></html>
"""


def _client(response_body, content_type="text/html"):
    def handler(request):
        if isinstance(response_body, dict):
            return httpx.Response(200, json=response_body)
        return httpx.Response(
            200, text=response_body, headers={"content-type": content_type}
        )

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_rss_source():
    scraper = CustomSourceScraper(
        1, "Acme", "rss", {"url": "https://acme.test/feed?q={query}"},
        client=_client(RSS_FEED, "application/rss+xml"),
    )
    postings = scraper.search(SearchQuery(keywords="welding"))
    assert len(postings) == 1
    p = postings[0]
    assert p.title == "Senior Welding Technician"
    assert p.url == "https://acme.test/jobs/42"
    assert p.posted == "2026-05-18"
    assert p.key.startswith("u1:")


def test_json_source_with_dot_paths():
    scraper = CustomSourceScraper(
        2, "EduCo", "json",
        {
            "url": "https://educo.test/api?q={query}",
            "list_path": "data.jobs",
            "fields": {
                "id": "jid", "title": "name", "company": "org.title",
                "location": "city", "url": "links.apply", "posted": "published",
            },
        },
        client=_client(JSON_API),
    )
    postings = scraper.search(SearchQuery(keywords="curriculum"))
    assert len(postings) == 1
    p = postings[0]
    assert p.key == "u2:7"
    assert p.company == "EduCo"
    assert p.posted == "2026-06-01"


def test_html_source_with_selectors():
    scraper = CustomSourceScraper(
        3, "Sprouts", "html",
        {
            "url": "https://sprouts.test/jobs?q={query}",
            "selectors": {
                "item": "li.job", "title": ".t", "company": ".c",
                "location": ".l", "url": "a",
            },
        },
        client=_client(HTML_PAGE),
    )
    postings = scraper.search(SearchQuery(keywords="teacher"))
    assert len(postings) == 1
    p = postings[0]
    assert p.title == "Lead Teacher"
    assert p.url == "https://sprouts.test/jobs/teacher-1"
    assert p.location == "Troy, NY"


def test_local_keyword_filter_when_no_query_placeholder():
    scraper = CustomSourceScraper(
        4, "Acme", "rss", {"url": "https://acme.test/feed"},
        client=_client(RSS_FEED, "application/rss+xml"),
    )
    assert scraper.search(SearchQuery(keywords="welding")) != []
    assert scraper.search(SearchQuery(keywords="astronaut")) == []
