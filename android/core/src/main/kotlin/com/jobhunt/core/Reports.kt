package com.jobhunt.core

/**
 * Markdown report rendering, matching the original cron's output so digests
 * stay diffable and readable outside the app (share sheet, Obsidian, Drive).
 */
object Reports {

    /** The per-run digest: funnel, TL;DR, top matches, and the queries fanned. */
    fun renderDigest(result: RunResult): String = buildString {
        appendLine("# Job Hunt - ${result.date}")
        appendLine()
        appendLine("**Run mode:** deterministic on-device pipeline (no LLM)")
        appendLine("**Funnel:** ${result.funnel}")
        appendLine(
            "**Dropped — out of range:** ${result.fetched - result.inRange} | " +
                "**Dropped — no resume fit:** ${result.inRange - result.relevant}",
        )
        appendLine()
        appendLine("## TL;DR")

        val top = result.newListings.take(10)
        val best = top.firstOrNull()
        if (best != null) {
            appendLine(
                "- Best match: **${best.title}** at ${best.company} — " +
                    "${Matching.matchBar(best.score)} (${best.location}).",
            )
            appendLine("- ${result.newCount} new listing(s) in this digest.")
            val bySource = top.groupingBy { it.source }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}: ${it.value}" }
            appendLine("- Sources represented in top matches: $bySource")
        } else {
            appendLine("- No new matching listings this run.")
        }

        appendLine()
        appendLine("## Top matches")
        top.forEachIndexed { index, listing ->
            appendLine(
                "### ${index + 1}. ${listing.title} - ${listing.company} " +
                    "(${listing.location})",
            )
            appendLine("- **Match:** ${Matching.matchBar(listing.score)}")
            appendLine(
                "- **Posted:** ${listing.posted.ifBlank { "unknown" }} | " +
                    "**Source:** ${listing.source}",
            )
            appendLine("- **Apply:** ${listing.url}")
            appendLine()
        }

        if (result.errors.isNotEmpty()) {
            appendLine("## Warnings")
            result.errors.forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("## Queries fanned")
        result.queries.forEach { appendLine("- $it") }
    }

    /** The rolling working list, grouped by first-seen date, newest first. */
    fun renderWorkingList(
        listings: List<Listing>,
        refreshedAt: String,
        retentionDays: Int = RETENTION_DAYS,
    ): String = buildString {
        appendLine("# Job Hunt — Working List")
        appendLine()
        appendLine("_Last refreshed: ${refreshedAt}_")
        appendLine("_Active listings: **${listings.size}** ($retentionDays-day retention)_")
        appendLine()
        appendLine(
            "Sorted by date first seen, newest first. " +
                "**NEW** = surfaced in the most recent run.",
        )
        appendLine()

        var currentDate: String? = null
        for (listing in listings) {
            if (listing.firstSeen != currentDate) {
                currentDate = listing.firstSeen
                appendLine("## $currentDate")
                appendLine()
            }
            val marker = if (listing.isNew) "**🆕 NEW** — " else ""
            appendLine("### $marker${listing.title} — ${listing.company}")
            appendLine("- **Match:** ${Matching.matchBar(listing.score)}")
            appendLine(
                "- **Location:** ${listing.location.ifBlank { "unknown" }} | " +
                    "**Source:** ${listing.source} | " +
                    "**Posted:** ${listing.posted.ifBlank { "unknown" }}",
            )
            appendLine("- **Apply:** ${listing.url}")
            appendLine()
        }
    }
}
