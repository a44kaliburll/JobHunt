package com.jobhunt.core.scrapers

import com.jobhunt.core.Fetcher
import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The Muse public jobs API (https://www.themuse.com/developers/api/v2). */
class TheMuseScraper(private val fetcher: Fetcher) : Scraper {

    override val name = "TheMuse"
    override val keyPrefix = "tm"

    override fun search(query: SearchQuery): List<JobPosting> {
        val location = when {
            query.location.isBlank() -> ""
            query.location.equals("remote", ignoreCase = true) -> "Flexible / Remote"
            else -> query.location
        }
        val url = buildString {
            append(API_URL).append("?page=0")
            if (location.isNotEmpty()) append("&location=").append(urlEncode(location))
        }
        return parse(fetcher.get(url), query)
    }

    internal fun parse(body: String, query: SearchQuery): List<JobPosting> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        val keywords = query.keywords.lowercase().split(' ').filter { it.length > 2 }
        val postings = mutableListOf<JobPosting>()
        for (element in results) {
            val item = element as? JsonObject ?: continue
            val title = item.stringAt("name")
            if (title.isEmpty()) continue
            val contents = item.stringAt("contents").take(4000)
            if (keywords.isNotEmpty() && keywords.none { it in "$title $contents".lowercase() }) {
                continue
            }
            val locations = (item["locations"] as? JsonArray)
                ?.joinToString(", ") { it.stringAt("name") }
                .orEmpty()
            postings += JobPosting(
                key = "$keyPrefix:${item.stringAt("id")}",
                title = title,
                company = item.stringAt("company.name"),
                location = locations,
                url = item.stringAt("refs.landing_page"),
                source = name,
                posted = normalizeDate(item.stringAt("publication_date")),
                description = contents,
            )
        }
        return postings
    }

    private companion object {
        const val API_URL = "https://www.themuse.com/api/public/jobs"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
