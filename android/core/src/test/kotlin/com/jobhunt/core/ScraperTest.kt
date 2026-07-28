package com.jobhunt.core

import com.jobhunt.core.scrapers.CustomSourceScraper
import com.jobhunt.core.scrapers.LinkedInScraper
import com.jobhunt.core.scrapers.RemoteOkScraper
import com.jobhunt.core.scrapers.TheMuseScraper
import com.jobhunt.core.scrapers.WeWorkRemotelyScraper
import com.jobhunt.core.scrapers.safeSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Serves canned bodies and records the URLs that were requested. */
private class FakeFetcher(private val body: String = "") : Fetcher {
    val requested = mutableListOf<String>()
    override fun get(url: String): String {
        requested += url
        return body
    }
}

private const val LINKEDIN_HTML = """
<li>
  <a class="base-card__full-link" href="https://www.linkedin.com/jobs/view/director-of-online-learning-at-acme-4412327417?trk=guest">x</a>
  <h3 class="base-search-card__title">Director of Online Learning</h3>
  <h4 class="base-search-card__subtitle">Acme University</h4>
  <span class="job-search-card__location">Albany, NY</span>
  <time datetime="2026-05-10">3 days ago</time>
</li>
<li><a href="/nope">no job id</a><h3>Ignored</h3></li>
"""

private const val REMOTEOK_JSON = """
[
  {"legal": "RemoteOK legal notice, not a job"},
  {"id": "1131568", "position": "Digital Marketing Manager", "company": "Care Access",
   "location": "USA Remote", "url": "https://remoteok.com/remote-jobs/1131568",
   "date": "2026-05-13T10:00:00+00:00", "tags": ["marketing", "digital"],
   "description": "Own digital marketing campaigns."},
  {"id": "999", "position": "Rust Systems Engineer", "company": "Ferris",
   "location": "Remote", "tags": ["rust"], "description": "Low level work."}
]
"""

private const val THEMUSE_JSON = """
{"results": [
  {"id": 21576547, "name": "Director, Clinical Excellence and Education",
   "contents": "Lead clinical education programs.",
   "company": {"name": "Headway"},
   "locations": [{"name": "Flexible / Remote"}],
   "refs": {"landing_page": "https://www.themuse.com/jobs/headway/director"},
   "publication_date": "2026-05-15T00:00:00Z"}
]}
"""

private const val WWR_HTML = """
<section class="jobs"><article><ul>
  <li><a href="/remote-jobs/acme-senior-instructional-designer">
    <span class="company">Acme</span>
    <span class="title">Senior Instructional Designer</span>
    <span class="region">Anywhere (100% Remote)</span>
  </a></li>
  <li><a href="/remote-jobs/search">Search</a></li>
</ul></article></section>
"""

class BuiltInScraperTest {

    @Test
    fun `linkedin cards parse and non-job links are skipped`() {
        val postings = LinkedInScraper(FakeFetcher()).parse(LINKEDIN_HTML)
        assertEquals(1, postings.size)
        val job = postings.single()
        assertEquals("li:4412327417", job.key)
        assertEquals("Director of Online Learning", job.title)
        assertEquals("Acme University", job.company)
        assertEquals("Albany, NY", job.location)
        assertEquals("2026-05-10", job.posted)
        assertTrue("?" !in job.url, "tracking params should be stripped: ${job.url}")
    }

    @Test
    fun `linkedin query is date-bounded and url encoded`() {
        val fetcher = FakeFetcher(LINKEDIN_HTML)
        LinkedInScraper(fetcher).search(SearchQuery("Director of Online Learning", "Albany, NY"))
        val url = fetcher.requested.single()
        assertTrue("keywords=Director+of+Online+Learning" in url, url)
        assertTrue("location=Albany%2C+NY" in url, url)
        assertTrue("f_TPR=r604800" in url, url)
    }

    @Test
    fun `remoteok skips the legal notice and filters by keyword`() {
        val postings = RemoteOkScraper(FakeFetcher())
            .parse(REMOTEOK_JSON, SearchQuery("digital marketing"))
        assertEquals(1, postings.size)
        val job = postings.single()
        assertEquals("ro:1131568", job.key)
        assertEquals("Care Access", job.company)
        assertEquals("2026-05-13", job.posted)
        assertEquals(listOf("marketing", "digital"), job.tags)
    }

    @Test
    fun `themuse maps nested company and location fields`() {
        val postings = TheMuseScraper(FakeFetcher())
            .parse(THEMUSE_JSON, SearchQuery("clinical education"))
        val job = postings.single()
        assertEquals("tm:21576547", job.key)
        assertEquals("Headway", job.company)
        assertEquals("Flexible / Remote", job.location)
        assertEquals("2026-05-15", job.posted)
    }

    @Test
    fun `weworkremotely resolves slugs to absolute urls`() {
        val postings = WeWorkRemotelyScraper(FakeFetcher()).parse(WWR_HTML)
        val job = postings.single()
        assertEquals("wwr:acme-senior-instructional-designer", job.key)
        assertEquals("Senior Instructional Designer", job.title)
        assertEquals(
            "https://weworkremotely.com/remote-jobs/acme-senior-instructional-designer",
            job.url,
        )
    }

    @Test
    fun `a failing board degrades to a warning instead of killing the run`() {
        val exploding = object : com.jobhunt.core.scrapers.Scraper {
            override val name = "Broken"
            override val keyPrefix = "bk"
            override fun search(query: SearchQuery): List<JobPosting> =
                throw java.io.IOException("429 Too Many Requests")
        }
        val outcome = exploding.safeSearch(SearchQuery("anything"))
        assertTrue(outcome.postings.isEmpty())
        assertNotNull(outcome.error)
        assertTrue("429" in outcome.error!!, outcome.error!!)
    }
}

private const val RSS_FEED = """<?xml version="1.0"?>
<rss version="2.0"><channel>
  <title>Acme Jobs</title>
  <item>
    <title>Senior Welding Technician</title>
    <link>https://acme.test/jobs/42</link>
    <guid>42</guid>
    <pubDate>Mon, 18 May 2026 09:00:00 GMT</pubDate>
    <description>Welding and CNC work.</description>
  </item>
</channel></rss>
"""

private const val JSON_API = """
{"data": {"jobs": [
  {"jid": 7, "name": "Curriculum Developer", "org": {"title": "EduCo"},
   "city": "Albany, NY", "links": {"apply": "https://educo.test/7"},
   "published": "2026-06-01T08:00:00Z"}
]}}
"""

private const val HTML_PAGE = """
<html><body><ul>
  <li class="job">
    <a href="/jobs/teacher-1"><span class="t">Lead Teacher</span></a>
    <span class="c">Little Sprouts</span><span class="l">Troy, NY</span>
  </li>
</ul></body></html>
"""

class CustomSourceScraperTest {

    private fun scraper(id: Long, kind: String, config: String, body: String) =
        CustomSourceScraper(CustomSource(id, "Test Source", kind, config), FakeFetcher(body))

    @Test
    fun `rss feeds are parsed with rfc822 dates`() {
        val postings = scraper(
            1, "rss", """{"url": "https://acme.test/feed?q={query}"}""", RSS_FEED,
        ).search(SearchQuery("welding"))

        val job = postings.single()
        assertEquals("Senior Welding Technician", job.title)
        assertEquals("https://acme.test/jobs/42", job.url)
        assertEquals("2026-05-18", job.posted)
        assertTrue(job.key.startsWith("u1:"), job.key)
    }

    @Test
    fun `json sources resolve dot-path field mappings`() {
        val config = """
            {"url": "https://educo.test/api?q={query}", "list_path": "data.jobs",
             "fields": {"id": "jid", "title": "name", "company": "org.title",
                        "location": "city", "url": "links.apply", "posted": "published"}}
        """.trimIndent()
        val job = scraper(2, "json", config, JSON_API).search(SearchQuery("curriculum")).single()

        assertEquals("u2:7", job.key)
        assertEquals("Curriculum Developer", job.title)
        assertEquals("EduCo", job.company)
        assertEquals("Albany, NY", job.location)
        assertEquals("2026-06-01", job.posted)
    }

    @Test
    fun `html sources scrape with css selectors and absolute urls`() {
        val config = """
            {"url": "https://sprouts.test/jobs?q={query}",
             "selectors": {"item": "li.job", "title": ".t", "company": ".c",
                           "location": ".l", "url": "a"}}
        """.trimIndent()
        val job = scraper(3, "html", config, HTML_PAGE).search(SearchQuery("teacher")).single()

        assertEquals("Lead Teacher", job.title)
        assertEquals("Little Sprouts", job.company)
        assertEquals("Troy, NY", job.location)
        assertEquals("https://sprouts.test/jobs/teacher-1", job.url)
    }

    @Test
    fun `sources without a query placeholder are filtered locally`() {
        val config = """{"url": "https://acme.test/feed"}"""
        assertEquals(1, scraper(4, "rss", config, RSS_FEED).search(SearchQuery("welding")).size)
        assertEquals(0, scraper(4, "rss", config, RSS_FEED).search(SearchQuery("astronaut")).size)
    }

    @Test
    fun `query and location placeholders are url encoded into the request`() {
        val fetcher = FakeFetcher(RSS_FEED)
        CustomSourceScraper(
            CustomSource(5, "Test", "rss", """{"url": "https://a.test/f?q={query}&l={location}"}"""),
            fetcher,
        ).search(SearchQuery("Lead Teacher", "Troy, NY"))

        assertEquals("https://a.test/f?q=Lead+Teacher&l=Troy%2C+NY", fetcher.requested.single())
    }

    @Test
    fun `an unknown kind fails loudly rather than silently returning nothing`() {
        val outcome = scraper(6, "carrier-pigeon", """{"url": "https://a.test"}""", "")
            .safeSearch(SearchQuery("anything"))
        assertNotNull(outcome.error)
    }
}
