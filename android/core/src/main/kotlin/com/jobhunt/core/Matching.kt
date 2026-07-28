package com.jobhunt.core

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Match scoring and query building.
 *
 * Each listing gets a deterministic score out of [MAX_SCORE], rendered as
 * `[████░░░░░░] 38% (19/50)`.
 *
 *  - title overlap with resume-derived titles ....... up to 20
 *  - skills found in title/description/tags ......... up to 15
 *  - duty keywords found in the description ......... up to 10
 *  - certifications mentioned ....................... up to 5
 */
object Matching {

    private val STOPWORDS = setOf(
        "of", "and", "the", "a", "an", "for", "to", "in", "at", "on", "with",
        "or", "&", "-", "i", "ii", "iii", "sr", "jr", "senior", "junior",
    )

    private val TOKEN_RE = Regex("""[a-z0-9+#/.]+""")

    data class ScoreBreakdown(
        val title: Int = 0,
        val skills: Int = 0,
        val duties: Int = 0,
        val certifications: Int = 0,
    ) {
        val total: Int get() = min(MAX_SCORE, title + skills + duties + certifications)
    }

    internal fun tokens(text: String): Set<String> =
        TOKEN_RE.findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOPWORDS && it.length > 1 }
            .toSet()

    fun score(posting: JobPosting, profile: SearchProfile): ScoreBreakdown {
        val titleTokens = tokens(posting.title)
        val haystack = buildString {
            append(posting.title).append(' ')
            append(posting.description).append(' ')
            append(posting.tags.joinToString(" "))
        }.lowercase()

        // Title: best token-overlap ratio against any profile title.
        var bestRatio = 0.0
        for (profileTitle in profile.titles) {
            val pt = tokens(profileTitle)
            if (pt.isEmpty()) continue
            bestRatio = maxOf(bestRatio, pt.intersect(titleTokens).size.toDouble() / pt.size)
        }
        val titleScore = (bestRatio * 20).roundToInt()

        // Skills: 3 points each, capped at 15.
        val skillHits = profile.skills.count { skill ->
            Regex("""(?<![\w+#])${Regex.escape(skill.lowercase())}(?![\w+#])""")
                .containsMatchIn(haystack)
        }
        val skillScore = min(15, skillHits * 3)

        // Duties: shared meaningful words between duty bullets and description.
        val dutyScore = if (posting.description.isNotBlank()) {
            val dutyTokens = profile.duties.flatMap { tokens(it) }.toSet()
            min(10, dutyTokens.intersect(tokens(posting.description)).size / 3)
        } else 0

        val certScore = if (profile.certifications.any { it.lowercase() in haystack }) 5 else 0

        return ScoreBreakdown(titleScore, skillScore, dutyScore, certScore)
    }

    /** Renders the match bar, e.g. `[████░░░░░░] 38% (19/50)`. */
    fun matchBar(score: Int, maxScore: Int = MAX_SCORE): String {
        val pct = (score * 100.0 / maxScore).roundToInt()
        val filled = (score * 10.0 / maxScore).roundToInt().coerceIn(0, 10)
        return "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "] $pct% ($score/$maxScore)"
    }

    /**
     * True if a posting's location matches any preferred location. Matching is
     * loose: a preference matches when its city token appears in the posting
     * location, and "Remote" matches the usual remote/anywhere phrasings.
     * No preferences means everything passes.
     */
    fun locationInRange(postingLocation: String, preferred: List<String>): Boolean {
        if (preferred.isEmpty()) return true
        val loc = postingLocation.lowercase()
        for (pref in preferred) {
            val p = pref.lowercase().trim()
            if (p.isEmpty()) continue
            if (p == "remote" || p == "anywhere") {
                val remoteish = listOf("remote", "anywhere", "flexible", "worldwide")
                if (loc.isEmpty() || remoteish.any { it in loc }) return true
                continue
            }
            val city = p.substringBefore(',').trim()
            if (city.isNotEmpty() && city in loc) return true
        }
        return false
    }

    /** Fan out (title x location) queries, capped at [MAX_QUERIES_PER_SOURCE]. */
    fun buildQueries(profile: SearchProfile, locations: List<String>): List<SearchQuery> {
        val titles = profile.titles.take(8).ifEmpty { profile.skills.take(4) }
        val locs = locations.ifEmpty { listOf("Remote") }
        return titles
            .flatMap { title -> locs.map { loc -> SearchQuery(title, loc) } }
            .take(MAX_QUERIES_PER_SOURCE)
    }
}
