package com.jobhunt.core.scrapers

import com.jobhunt.core.JobPosting
import com.jobhunt.core.SearchQuery
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A source of job postings. Implementations must be safe to call repeatedly. */
interface Scraper {
    val name: String
    val keyPrefix: String
    fun search(query: SearchQuery): List<JobPosting>
}

/** Result of a scrape attempt that never throws — one bad board can't kill a run. */
data class ScrapeOutcome(val postings: List<JobPosting>, val error: String?)

fun Scraper.safeSearch(query: SearchQuery): ScrapeOutcome = try {
    ScrapeOutcome(search(query), null)
} catch (e: Exception) {
    ScrapeOutcome(emptyList(), "$name: ${e.message ?: e::class.simpleName}")
}

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Resolve an `a.b.0.c` style path through nested JSON objects and arrays. */
internal fun JsonElement.atPath(path: String): JsonElement? {
    if (path.isEmpty()) return null
    var current: JsonElement? = this
    for (part in path.split('.')) {
        current = when (val node = current) {
            is JsonObject -> node[part]
            is JsonArray -> part.toIntOrNull()?.let { node.getOrNull(it) }
            else -> null
        } ?: return null
    }
    return current
}

/** Best-effort scalar-to-string, without JSON quoting. */
internal fun JsonElement?.asText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> if (this.isString) content else content
    else -> ""
}

internal fun JsonElement?.stringAt(path: String): String =
    this?.atPath(path).asText()
