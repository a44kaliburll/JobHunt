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
    /** Descriptions fetched for postings whose search results lacked one. */
    val enriched: Int = 0,
    /** Copies of a job folded into another copy rather than listed twice. */
    val duplicates: Int = 0,
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
 * The scrape -> enrich -> score -> group -> retain pipeline, kept free of
 * Android and storage concerns so it can be unit-tested on the JVM.
 *
 *  1. Build (title x location) queries from the profile.
 *  2. Fan them out across every scraper; failures degrade to warnings.
 *  3. Fill in missing descriptions for a budgeted shortlist of the most
 *     promising postings, so boards that only return cards can still be scored
 *     on more than their title.
 *  4. Funnel: fetched -> in range (location) -> relevant (score >= threshold).
 *  5. Group reposts and cross-posts so one job is one listing.
 *  6. Report which listings are new, which improved, and which aged out.
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
                    "No profile yet: add a job title or a resume so queries can be built.",
                ),
            )
        }

        val queries = Matching.buildQueries(profile, settings.locations)
        val queryLabels = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val seen = LinkedHashMap<String, Sighting>()
        var fetched = 0

        for (scraper in scrapers) {
            for (query in queries) {
                val outcome = scraper.safeSearch(query)
                queryLabels += query.label(scraper.name)
                outcome.error?.let { errors += it }
                for (posting in outcome.postings) {
                    fetched++
                    seen.putIfAbsent(posting.key, Sighting(posting, scraper))
                }
            }
        }

        val inRange = seen.values.filter {
            Matching.locationInRange(it.posting.location, settings.locations)
        }

        val enrichment = enrich(inRange, profile, settings.descriptionBudget)
        errors += enrichment.errors

        val minScore = settings.minScore.coerceIn(0, MAX_SCORE)
        val relevant = enrichment.postings
            .map { it to Matching.score(it, profile).total }
            .filter { (_, score) -> score >= minScore }

        val merged = Dedupe.collapse(relevant)

        val existingByKey = existing.associateBy { it.key }
        val existingByGroup = existing
            .filter { it.groupKey.isNotBlank() }
            .associateBy { it.groupKey }

        val newListings = mutableListOf<Listing>()
        val rescored = mutableMapOf<String, Int>()

        for (job in merged) {
            val stored = existingByKey[job.posting.key] ?: existingByGroup[job.groupKey]
            if (stored == null) {
                newListings += Listing(
                    key = job.posting.key,
                    title = job.posting.title,
                    company = job.posting.company,
                    location = job.posting.location,
                    url = job.posting.url,
                    source = job.posting.source,
                    posted = job.posting.posted,
                    score = job.score,
                    firstSeen = date,
                    isNew = true,
                    groupKey = job.groupKey,
                    alsoPostedBy = job.alsoPostedBy,
                )
            } else if (job.score > stored.score) {
                rescored[stored.key] = job.score
            }
        }

        val cutoff = today.minusDays(settings.retentionDays.toLong()).toString()
        val staleKeys = existing.filter { it.firstSeen < cutoff }.map { it.key }

        return RunResult(
            date = date,
            fetched = fetched,
            inRange = inRange.size,
            relevant = relevant.size,
            enriched = enrichment.count,
            duplicates = relevant.size - merged.size,
            newListings = newListings.sortedByDescending { it.score },
            rescored = rescored,
            staleKeys = staleKeys,
            queries = queryLabels,
            errors = errors,
        )
    }

    /**
     * Fill in descriptions the search results left out.
     *
     * Every fetch is one more request to a board that would rather we did not,
     * so the budget is spent on the postings most likely to survive scoring:
     * the shortlist is ranked by the score they already have on title alone.
     */
    private fun enrich(
        candidates: List<Sighting>,
        profile: SearchProfile,
        budget: Int,
    ): Enrichment {
        val shortlist = candidates
            .filter { it.posting.description.isBlank() && it.scraper.supportsDescriptions }
            .sortedByDescending { Matching.score(it.posting, profile).total }
            .take(budget.coerceAtLeast(0))
            .mapTo(mutableSetOf()) { it.posting.key }

        if (shortlist.isEmpty()) return Enrichment(candidates.map { it.posting }, 0, emptyList())

        var count = 0
        val errors = mutableListOf<String>()
        val postings = candidates.map { sighting ->
            if (sighting.posting.key !in shortlist) return@map sighting.posting
            val description = runCatching { sighting.scraper.describe(sighting.posting) }
                .onFailure {
                    errors += "${sighting.scraper.name} description: " +
                        (it.message ?: it::class.simpleName)
                }
                .getOrNull()
            if (description.isNullOrBlank()) {
                sighting.posting
            } else {
                count++
                sighting.posting.copy(description = description)
            }
        }
        // One board rate-limiting mid-run shouldn't bury the digest in noise.
        return Enrichment(postings, count, errors.distinct().take(3))
    }

    private data class Sighting(val posting: JobPosting, val scraper: Scraper)

    private data class Enrichment(
        val postings: List<JobPosting>,
        val count: Int,
        val errors: List<String>,
    )

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
