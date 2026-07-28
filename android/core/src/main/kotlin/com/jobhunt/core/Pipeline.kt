package com.jobhunt.core

import com.jobhunt.core.scrapers.CustomSourceScraper
import com.jobhunt.core.scrapers.LinkedInScraper
import com.jobhunt.core.scrapers.RemoteOkScraper
import com.jobhunt.core.scrapers.Scraper
import com.jobhunt.core.scrapers.TheMuseScraper
import com.jobhunt.core.scrapers.WeWorkRemotelyScraper
import com.jobhunt.core.scrapers.safeSearch
import java.time.LocalDate

/** Everything one run produced. Persisting it is the caller's job. */
data class RunResult(
    val date: String,
    val fetched: Int = 0,
    val inRange: Int = 0,
    val relevant: Int = 0,
    val newListings: List<Listing> = emptyList(),
    /** Existing listings whose score improved: key -> new score. */
    val rescored: Map<String, Int> = emptyMap(),
    /** Listings past the retention window, to delete. */
    val staleKeys: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val newCount: Int get() = newListings.size

    val funnel: String
        get() = "$fetched fetched -> $inRange in range -> $relevant relevant -> " +
            "$newCount new this run"
}

/**
 * The scrape -> score -> dedupe -> retain pipeline, kept free of Android and
 * storage concerns so it can be unit-tested on the JVM.
 *
 *  1. Build (title x location) queries from the merged resume profile.
 *  2. Fan them out across every scraper; failures degrade to warnings.
 *  3. Funnel: fetched -> in range (location) -> relevant (score >= threshold)
 *     -> new (not already stored).
 *  4. Report which listings are new, which improved, and which aged out.
 */
class Pipeline(private val scrapers: List<Scraper>) {

    fun run(
        profile: SearchProfile,
        settings: HuntSettings,
        existing: List<Listing>,
        today: LocalDate = LocalDate.now(),
    ): RunResult {
        val date = today.toString()
        if (profile.isEmpty) {
            return RunResult(
                date = date,
                errors = listOf(
                    "No resume data: add at least one resume so queries can be built.",
                ),
            )
        }

        val queries = Matching.buildQueries(profile, settings.locations)
        val queryLabels = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val seen = LinkedHashMap<String, JobPosting>()
        var fetched = 0

        for (scraper in scrapers) {
            for (query in queries) {
                val outcome = scraper.safeSearch(query)
                queryLabels += query.label(scraper.name)
                outcome.error?.let { errors += it }
                for (posting in outcome.postings) {
                    fetched++
                    seen.putIfAbsent(posting.key, posting)
                }
            }
        }

        val existingByKey = existing.associateBy { it.key }
        val minScore = settings.minScore.coerceIn(0, MAX_SCORE)
        var inRange = 0
        var relevant = 0
        val newListings = mutableListOf<Listing>()
        val rescored = mutableMapOf<String, Int>()

        for (posting in seen.values) {
            if (!Matching.locationInRange(posting.location, settings.locations)) continue
            inRange++
            val score = Matching.score(posting, profile).total
            if (score < minScore) continue
            relevant++

            val stored = existingByKey[posting.key]
            if (stored == null) {
                newListings += Listing(
                    key = posting.key,
                    title = posting.title,
                    company = posting.company,
                    location = posting.location,
                    url = posting.url,
                    source = posting.source,
                    posted = posting.posted,
                    score = score,
                    firstSeen = date,
                    isNew = true,
                )
            } else if (score > stored.score) {
                rescored[posting.key] = score
            }
        }

        val cutoff = today.minusDays(settings.retentionDays.toLong()).toString()
        val staleKeys = existing.filter { it.firstSeen < cutoff }.map { it.key }

        return RunResult(
            date = date,
            fetched = fetched,
            inRange = inRange,
            relevant = relevant,
            newListings = newListings.sortedByDescending { it.score },
            rescored = rescored,
            staleKeys = staleKeys,
            queries = queryLabels,
            errors = errors,
        )
    }

    companion object {
        /** Built-in boards plus the user's enabled custom sources. */
        fun defaultScrapers(
            fetcher: Fetcher,
            customSources: List<CustomSource> = emptyList(),
        ): List<Scraper> = buildList {
            add(LinkedInScraper(fetcher))
            add(RemoteOkScraper(fetcher))
            add(WeWorkRemotelyScraper(fetcher))
            add(TheMuseScraper(fetcher))
            customSources.filter { it.enabled }.forEach { add(CustomSourceScraper(it, fetcher)) }
        }
    }
}
