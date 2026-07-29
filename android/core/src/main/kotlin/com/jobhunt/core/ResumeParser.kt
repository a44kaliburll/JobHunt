package com.jobhunt.core

/**
 * Deterministic resume parser — no LLM, no API keys, no network.
 *
 * Extracts a structured profile from resume plain text:
 *  - skills: taxonomy matches plus everything under a Skills section
 *  - duties: bullet lines / action-verb lines from the experience section
 *  - certifications: a Certifications/Licenses section plus known cert phrases
 *  - titles: job titles from experience, used to build job-board queries
 *  - summary: the opening summary/objective paragraph, if present
 */
object ResumeParser {

    private val SECTION_ALIASES: Map<String, Set<String>> = mapOf(
        "summary" to setOf(
            "summary", "professional summary", "profile", "objective",
            "career objective", "about", "about me",
        ),
        "skills" to setOf(
            "skills", "technical skills", "core competencies", "key skills",
            "areas of expertise", "competencies", "skills & abilities",
            "core skills", "highlights",
        ),
        "experience" to setOf(
            "experience", "work experience", "professional experience",
            "employment", "employment history", "work history",
            "relevant experience",
        ),
        "education" to setOf(
            "education", "education & training", "academic background",
        ),
        "certifications" to setOf(
            "certifications", "certificates", "licenses",
            "licenses & certifications", "certifications & licenses",
            "credentials", "professional certifications",
        ),
        "projects" to setOf(
            "projects", "selected projects", "personal projects",
        ),
    )

    private val ACTION_VERBS: Set<String> = setOf(
        "managed", "led", "developed", "designed", "created", "built",
        "implemented", "coordinated", "directed", "supervised", "trained",
        "taught", "facilitated", "organized", "launched", "improved",
        "increased", "reduced", "streamlined", "negotiated", "analyzed",
        "evaluated", "assessed", "maintained", "administered", "oversaw",
        "produced", "authored", "wrote", "presented", "delivered", "mentored",
        "recruited", "hired", "budgeted", "planned", "executed", "established",
        "founded", "spearheaded", "collaborated", "partnered", "supported",
        "resolved", "monitored", "audited", "researched", "tested", "deployed",
        "automated", "migrated", "optimized", "engineered", "architected",
        "drafted", "scheduled", "tracked", "processed", "operated", "installed",
        "repaired", "inspected", "performed", "provided", "ensured", "achieved",
    )

    private val TITLE_WORDS: List<String> = listOf(
        "director", "manager", "coordinator", "specialist", "engineer",
        "developer", "designer", "analyst", "consultant", "teacher", "instructor",
        "professor", "administrator", "assistant", "associate", "lead", "head",
        "officer", "supervisor", "technician", "nurse", "therapist", "counselor",
        "architect", "scientist", "librarian", "writer", "editor", "producer",
        "president", "vice president", "principal", "superintendent", "dean",
        "chief", "founder", "owner", "representative", "agent", "advisor",
        "strategist", "trainer", "facilitator", "planner", "buyer", "recruiter",
    )

    private val BULLET_RE = Regex("""^\s*[-*•·▪◦‣–—]\s+(.*)$""")
    private val DATE_RANGE_RE = Regex(
        """(19|20)\d{2}\s*[-–—to]+\s*((19|20)\d{2}|present|current)""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_LINE_RE = Regex(
        """^(?<title>[A-Z][A-Za-z0-9&/().'’+ -]{2,60}?)(\s*[|,@–—-]\s*|\s+at\s+)(?<rest>.+)$""",
    )
    private val SKILL_SPLIT_RE = Regex("""[,|;•·/]| {3,}""")
    private val FIRST_WORD_RE = Regex("""[\s,]""")

    fun parse(text: String): ParsedResume {
        val sections = splitSections(text)

        val skills = buildList {
            addAll(findKnownPhrases(text, Taxonomy.SKILLS))
            sections["skills"]?.let { addAll(skillsFromSection(it)) }
        }

        val certifications = buildList {
            addAll(findKnownPhrases(text, Taxonomy.CERTIFICATIONS))
            sections["certifications"]?.let { addAll(certificationsFromSection(it)) }
        }

        val experience = sections["experience"].orEmpty()
        val dutySource = experience.ifEmpty { sections["body"].orEmpty() }
        val titleSource = experience.ifEmpty { sections["body"].orEmpty() }

        val summary = sections["summary"]
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { it.trim() }
            .orEmpty()

        return ParsedResume(
            skills = dedupeKeepOrder(skills),
            duties = dedupeKeepOrder(dutiesFromLines(dutySource)),
            certifications = dedupeKeepOrder(certifications),
            titles = dedupeKeepOrder(titlesFromExperience(titleSource)),
            summary = summary.take(1000),
        )
    }

    // Several resumes are merged with the user's own edits by [ProfileBuilder],
    // which is the single place a search profile is assembled.

    // --- internals ---

    /** Split resume text into canonical sections; unmatched lines go to "body". */
    internal fun splitSections(text: String): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>("body" to mutableListOf())
        var current = "body"
        for (line in text.lines()) {
            val heading = normalizeHeading(line)
            if (heading != null) {
                current = heading
                sections.getOrPut(current) { mutableListOf() }
                continue
            }
            sections.getOrPut(current) { mutableListOf() }.add(line.trimEnd())
        }
        return sections
    }

    private fun normalizeHeading(line: String): String? {
        val stripped = line.trim().trim(':', '#').trim()
        if (stripped.isEmpty() || stripped.length > 45) return null
        val lowered = stripped.lowercase()
        return SECTION_ALIASES.entries.firstOrNull { lowered in it.value }?.key
    }

    private fun findKnownPhrases(text: String, phrases: List<String>): List<String> {
        val lowered = text.lowercase()
        return phrases.filter { phrase ->
            Regex("""(?<![\w+#])${Regex.escape(phrase)}(?![\w+#])""").containsMatchIn(lowered)
        }
    }

    private fun skillsFromSection(lines: List<String>): List<String> = buildList {
        for (line in lines) {
            val bullet = BULLET_RE.matchEntire(line)
            var content = bullet?.groupValues?.get(1) ?: line
            // "Category: a, b, c" -> keep only the list part
            if (content.contains(':')) content = content.substringAfter(':')
            for (raw in SKILL_SPLIT_RE.split(content)) {
                val part = raw.trim().trim('.').trim()
                if (part.length in 2..40 && !DATE_RANGE_RE.containsMatchIn(part)) add(part)
            }
        }
    }

    private fun dutiesFromLines(lines: List<String>): List<String> = buildList {
        for (line in lines) {
            val bullet = BULLET_RE.matchEntire(line)
            val candidate = (bullet?.groupValues?.get(1) ?: line).trim()
            if (candidate.length < 12) continue
            val firstWord = FIRST_WORD_RE.split(candidate, limit = 2).first().lowercase()
            if (bullet != null || firstWord in ACTION_VERBS) add(candidate.trimEnd('.'))
        }
    }

    private fun looksLikeTitle(text: String): Boolean {
        val lowered = text.lowercase()
        return TITLE_WORDS.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lowered) }
    }

    private fun titlesFromExperience(lines: List<String>): List<String> = buildList {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || BULLET_RE.matchEntire(line) != null) continue
            // "Title | Company", "Title at Company", "Title, Company"
            val match = TITLE_LINE_RE.matchEntire(line)
            val title = match?.groups?.get("title")?.value?.trim()
            if (title != null && looksLikeTitle(title)) {
                add(title)
                continue
            }
            // Bare title lines, and table-style rows where the date comes first
            // ("2019-2024 | Director of Online Learning"): weigh each cell alone.
            for (segment in line.split('|')) {
                val candidate = segment.trim()
                if (candidate.length in 3..60 && looksLikeTitle(candidate) &&
                    !DATE_RANGE_RE.containsMatchIn(candidate)
                ) {
                    add(candidate)
                }
            }
        }
    }

    private fun certificationsFromSection(lines: List<String>): List<String> = buildList {
        for (line in lines) {
            val bullet = BULLET_RE.matchEntire(line)
            val content = (bullet?.groupValues?.get(1) ?: line).trim().trimEnd('.')
            if (content.length in 3..120) add(content)
        }
    }

    private fun dedupeKeepOrder(items: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val key = item.lowercase().trim()
            if (key.isEmpty() || !seen.add(key)) null else item.trim()
        }
    }
}
