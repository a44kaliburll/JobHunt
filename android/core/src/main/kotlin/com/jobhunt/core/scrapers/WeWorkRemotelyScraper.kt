package com.jobhunt.core.scrapers

import com.jobhunt.core.Fetcher
import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import org.jsoup.Jsoup

/** We Work Remotely search results (HTML). */
class WeWorkRemotelyScraper(private val fetcher: Fetcher) : Scraper {

    override val name = "WeWorkRemotely"
    override val keyPrefix = "wwr"

    override fun search(query: SearchQuery): List<JobPosting> =
        parse(fetcher.get("$SEARCH_URL?term=${urlEncode(query.keywords)}"))

    internal fun parse(html: String): List<JobPosting> {
        val document = Jsoup.parse(html, BASE_URL)
        val postings = mutableListOf<JobPosting>()
        val seen = mutableSetOf<String>()
        for (anchor in document.select("section.jobs li a[href*=/remote-jobs/]")) {
            val href = anchor.attr("href")
            val slug = SLUG_RE.find(href)?.groupValues?.get(1) ?: continue
            if (slug == "search" || !seen.add(slug)) continue
            val title = anchor.selectFirst(".title, span.title")?.text()?.trim() ?: continue
            postings += JobPosting(
                key = "$keyPrefix:$slug",
                title = title,
                company = anchor.selectFirst(".company, span.company")?.text()?.trim().orEmpty(),
                location = anchor.selectFirst(".region, span.region")?.text()?.trim()
                    ?.ifBlank { "Remote" } ?: "Remote",
                url = anchor.absUrl("href").ifBlank { BASE_URL + href },
                source = name,
            )
        }
        return postings
    }

    private companion object {
        const val BASE_URL = "https://weworkremotely.com"
        const val SEARCH_URL = "$BASE_URL/remote-jobs/search"
        val SLUG_RE = Regex("""/remote-jobs/([^/?#]+)""")
    }
}
