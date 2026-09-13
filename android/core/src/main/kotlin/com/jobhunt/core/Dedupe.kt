package com.jobhunt.core

/**
 * Collapses the same job showing up more than once.
 *
 * Two shapes of duplicate turn up constantly in real runs:
 *
 *  - **Reposts.** A continuously-recruited req is re-advertised under a fresh
 *    vacancy id every few weeks, so the same role arrives three or four times.
 *  - **Cross-posts.** An aggregator re-lists an employer's job under its own
 *    name, so the same role arrives once as "Regeneron" and once as "BioSpace".
 *
 * Neither is caught by keying on `source:id`, because every copy has a
 * different id. Grouping instead keys on the title and location.
 *
 * Company is deliberately left out of the key for **distinctive** titles, which
 * is what lets a cross-post collapse across two different company names. For
 * short, generic titles ("Project Manager") that would be too eager — two
 * unrelated employers in one city would merge — so those stay company-scoped
 * and only collapse against reposts of themselves.
 */
object Dedupe {

    /** Title tokens needed before a title is trusted to identify a job on its own. */
    private const val DISTINCTIVE_TOKENS = 3

    private val REMOTE_WORDS = listOf("remote", "anywhere", "worldwide", "flexible")

    /** A canonical posting plus the duplicates that were folded into it. */
    data class Merged(
        val posting: JobPosting,
        val score: Int,
        val groupKey: String,
        /** Names the same job was also advertised under. */
        val alsoPostedBy: List<String> = emptyList(),
    )

    fun isDistinctiveTitle(title: String): Boolean =
        Matching.tokens(title).size >= DISTINCTIVE_TOKENS

    fun groupKey(posting: JobPosting): String =
        groupKey(posting.title, posting.location, posting.company)

    fun groupKey(title: String, location: String, company: String): String {
        // Sorted tokens, so "Online Learning Director" and "Director of Online
        // Learning" land on the same key.
        val titleKey = Matching.tokens(title).sorted().joinToString(" ")
        val locationKey = normalizeLocation(location)
        return if (isDistinctiveTitle(title)) {
            "$titleKey|$locationKey"
        } else {
            "$titleKey|$locationKey|${company.normalizedForProfile()}"
        }
    }

    /**
     * Group scored postings, keeping one per job. The copy kept is the one that
     * scores highest — which favours a posting carrying a real description over
     * a bare search-result card — then the earliest advertised, then whichever
     * was seen first.
     */
    fun collapse(scored: List<Pair<JobPosting, Int>>): List<Merged> {
        val groups = LinkedHashMap<String, MutableList<IndexedValue<Pair<JobPosting, Int>>>>()
        scored.forEachIndexed { index, entry ->
            groups.getOrPut(groupKey(entry.first)) { mutableListOf() }
                .add(IndexedValue(index, entry))
        }

        return groups.map { (key, members) ->
            val canonical = members.minWith(
                compareByDescending<IndexedValue<Pair<JobPosting, Int>>> { it.value.second }
                    .thenBy { it.value.first.posted.ifBlank { UNDATED } }
                    .thenBy { it.index },
            )
            val alternates = members
                .filter { it.index != canonical.index }
                .map { it.value.first }
                .map { it.company.ifBlank { it.source } }
                .filter { it.isNotBlank() && !it.equals(canonical.value.first.company, true) }
                .distinct()

            Merged(canonical.value.first, canonical.value.second, key, alternates)
        }
    }

    /**
     * Loose enough that "Albany County, NY" and "Albany, NY" agree, and that any
     * flavour of remote agrees with any other. Not a geocoder: "Greater Albany
     * Area" still keys differently from "Albany, NY".
     */
    internal fun normalizeLocation(location: String): String {
        val lowered = location.lowercase()
        if (REMOTE_WORDS.any { it in lowered }) return "remote"
        return lowered
            .replace(Regex("""\bcounty\b"""), " ")
            .replace(Regex("""\b(united states|usa)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    /** Sorts after every real date, so undated postings lose the tie-break. */
    private const val UNDATED = "9999-99-99"
}
