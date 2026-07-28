package com.jobhunt.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeJsonTest {

    @Test
    fun `a parsed resume round-trips through json`() {
        val parsed = ParsedResume(
            skills = listOf("instructional design", "WCAG"),
            duties = listOf("Led a team of 12 \"instructional\" designers"),
            certifications = listOf("PMP"),
            titles = listOf("Director of Online Learning"),
            summary = "Education leader — 10 years.",
        )
        assertEquals(parsed, ResumeJson.decode(ResumeJson.encode(parsed)))
    }

    @Test
    fun `an empty resume round-trips`() {
        assertEquals(ParsedResume(), ResumeJson.decode(ResumeJson.encode(ParsedResume())))
    }

    @Test
    fun `corrupt json degrades to empty instead of crashing`() {
        assertEquals(ParsedResume(), ResumeJson.decode("{not json"))
        assertEquals(ParsedResume(), ResumeJson.decode(""))
    }

    @Test
    fun `missing fields default to empty`() {
        val parsed = ResumeJson.decode("""{"skills": ["python"]}""")
        assertEquals(listOf("python"), parsed.skills)
        assertEquals(emptyList(), parsed.titles)
        assertEquals("", parsed.summary)
    }
}
