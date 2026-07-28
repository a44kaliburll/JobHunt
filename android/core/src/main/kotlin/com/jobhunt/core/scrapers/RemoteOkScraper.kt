package com.jobhunt.core.scrapers

import com.jobhunt.core.Fetcher
import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * RemoteOK public API. It returns the whole board in one response (the first
 * element is a legal notice, not a job), so keywords are filtered locally.
 */
class RemoteOkScraper(private val fetcher: Fetcher) : Scraper {

    override val name = "RemoteOK"
    override val keyPrefix = "ro"

    override fun search(query: SearchQuery): List<JobPosting> =
        parse(fetcher.get(API_URL), query)

    internal fun parse(body: String, query: SearchQuery): List<JobPosting> {
        val root = json.parseToJsonElement(body)
        val items = root as? JsonArray ?: return emptyList()
        val keywords = query.keywords.lowercase().split(' ').filter { it.length > 2 }
        val postings = mutableListOf<JobPosting>()
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val position = item.stringAt("position")
            if (position.isEmpty()) continue // legal notice / malformed row
            val tags = (item["tags"] as? JsonArray)?.map { it.asText() }.orEmpty()
            val description = item.stringAt("description").take(4000)
            val haystack = "$position ${tags.joinToString(" ")} $description".lowercase()
            if (keywords.isNotEmpty() && keywords.none { it in haystack }) continue
            postings += JobPosting(
                key = "$keyPrefix:${item.stringAt("id")}",
                title = position,
                company = item.stringAt("company"),
                location = item.stringAt("location").ifBlank { "Remote" },
                url = item.stringAt("url"),
                source = name,
                posted = normalizeDate(item.stringAt("date")),
                description = description,
                tags = tags,
            )
        }
        return postings
    }

    private companion object {
        const val API_URL = "https://remoteok.com/api"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
