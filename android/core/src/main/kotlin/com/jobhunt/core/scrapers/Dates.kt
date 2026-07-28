package com.jobhunt.core.scrapers

private val ISO_RE = Regex("""\d{4}-\d{2}-\d{2}""")
private val RFC822_RE = Regex("""(\d{1,2}) (\w{3}) (\d{4})""")

private val MONTHS = mapOf(
    "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
    "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
)

/**
 * Best-effort ISO date extraction from the many shapes job boards emit
 * (ISO 8601, RFC 822 feed dates, or free text). Returns "" when unknown.
 */
internal fun normalizeDate(raw: String): String {
    if (raw.isBlank()) return ""
    ISO_RE.find(raw)?.let { return it.value }
    RFC822_RE.find(raw)?.let { match ->
        val (day, monthName, year) = match.destructured
        val month = MONTHS[monthName.replaceFirstChar { it.uppercase() }] ?: return ""
        return "%s-%02d-%02d".format(year, month, day.toInt())
    }
    return ""
}
