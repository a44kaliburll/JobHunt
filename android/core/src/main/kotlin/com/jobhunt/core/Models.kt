package com.jobhunt.core

/** One job listing as returned by a scraper, before scoring and dedupe. */
data class JobPosting(
    val key: String,
    val title: String,
    val company: String = "",
    val location: String = "",
    val url: String = "",
    val source: String = "",
    /** ISO date (yyyy-MM-dd) or "". */
    val posted: String = "",
    /** Used for scoring only; not persisted. */
    val description: String = "",
    val tags: List<String> = emptyList(),
)

/** A scored listing that has been kept for the user. */
data class Listing(
    val key: String,
    val title: String,
    val company: String = "",
    val location: String = "",
    val url: String = "",
    val source: String = "",
    val posted: String = "",
    val score: Int = 0,
    /** ISO date the listing first surfaced. */
    val firstSeen: String = "",
    val isNew: Boolean = true,
    /** "" | "saved" | "applied" | "rejected" | "hidden" */
    val status: String = "",
)

/** Structured output of parsing a single resume. */
data class ParsedResume(
    val skills: List<String> = emptyList(),
    val duties: List<String> = emptyList(),
    val certifications: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val summary: String = "",
)

/** All of a user's resumes merged into one profile that drives the hunt. */
data class SearchProfile(
    val skills: List<String> = emptyList(),
    val duties: List<String> = emptyList(),
    val certifications: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val summary: String = "",
) {
    val isEmpty: Boolean get() = titles.isEmpty() && skills.isEmpty()
}

data class HuntSettings(
    val locations: List<String> = emptyList(),
    val minScore: Int = DEFAULT_MIN_SCORE,
    val retentionDays: Int = RETENTION_DAYS,
)

data class SearchQuery(
    val keywords: String,
    val location: String = "",
) {
    fun label(scraperName: String): String =
        "$scraperName: $keywords" + if (location.isNotBlank()) " @ $location" else ""
}

/** A user-defined job site. See [com.jobhunt.core.scrapers.CustomSourceScraper]. */
data class CustomSource(
    val id: Long,
    val name: String,
    /** "rss" | "json" | "html" */
    val kind: String,
    /** Raw JSON config string. */
    val configJson: String,
    val enabled: Boolean = true,
)

const val MAX_SCORE = 50
const val DEFAULT_MIN_SCORE = 6
const val RETENTION_DAYS = 90

/** Cap on (title x location) queries per scraper, so boards aren't hammered. */
const val MAX_QUERIES_PER_SOURCE = 25

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36 JobHunt/0.1"
