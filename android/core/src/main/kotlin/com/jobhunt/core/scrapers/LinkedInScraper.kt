package com.jobhunt.core.scrapers

import com.jobhunt.core.Fetcher
import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import org.jsoup.Jsoup

/**
 * LinkedIn guest job search.
 *
 * Uses the public (logged-out) endpoint that powers LinkedIn's "see more job
 * postings" infinite scroll, restricted to the last 7 days — the same approach
 * as the original cron pipeline.
 */
class LinkedInScraper(private val fetcher: Fetcher) : Scraper {

    override val name = "LinkedIn"
    override val keyPrefix = "li"

    override fun search(query: SearchQuery): List<JobPosting> {
        val url = buildString {
            append(SEARCH_URL)
            append("?keywords=").append(urlEncode(query.keywords))
            append("&location=").append(urlEncode(query.location.ifBlank { "Remote" }))
            append("&f_TPR=r604800&start=0")
        }
        return parse(fetcher.get(url))
    }

    internal fun parse(html: String): List<JobPosting> {
        val document = Jsoup.parse(html)
        val postings = mutableListOf<JobPosting>()
        for (card in document.select("li")) {
            val link = card.selectFirst("a.base-card__full-link") ?: card.selectFirst("a")
            val titleEl = card.selectFirst("h3")
            if (link == null || titleEl == null) continue
            val url = link.attr("href").substringBefore('?')
            val jobId = JOB_ID_RE.find(url)?.groupValues?.get(1) ?: continue
            val time = card.selectFirst("time")
            postings += JobPosting(
                key = "$keyPrefix:$jobId",
                title = titleEl.text().trim(),
                company = card.selectFirst("h4")?.text()?.trim().orEmpty(),
                location = card.selectFirst(
                    ".job-search-card__location, .base-search-card__metadata span",
                )?.text()?.trim().orEmpty(),
                url = url,
                source = name,
                posted = time?.attr("datetime").orEmpty(),
            )
        }
        return postings
    }

    private companion object {
        const val SEARCH_URL =
            "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
        val JOB_ID_RE = Regex("""-?(\d{6,})(?:\?|$)""")
    }
}
