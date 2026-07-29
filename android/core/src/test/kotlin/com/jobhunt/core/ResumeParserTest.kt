package com.jobhunt.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SAMPLE = """
John Smith
Albany, NY | john@example.com

Summary
Education leader with 10 years of experience in online learning and
instructional design, focused on accessibility.

Skills
- Instructional Design, Curriculum Development, LMS Administration
- Project Management | Canvas | Articulate Storyline
- WCAG, Accessibility

Experience

Director of Online Learning | Excelsior University
2019 - Present
- Led a team of 12 instructional designers and media developers.
- Implemented WCAG 2.1 accessibility standards across 300 courses.
- Managed a ${'$'}1.2M annual budget for educational technology.

Instructional Designer at SUNY Albany
2014 - 2019
- Designed online courses in Canvas for 5,000+ students.

Education
M.S. Educational Technology, University at Albany

Certifications
- PMP (Project Management Professional)
- CPACC — Certified Professional in Accessibility Core Competencies
""".trimIndent()

class ResumeParserTest {

    @Test
    fun `skills come from the taxonomy and the skills section`() {
        val skills = ResumeParser.parse(SAMPLE).skills.map { it.lowercase() }
        assertContains(skills, "instructional design")
        assertContains(skills, "curriculum development")
        assertContains(skills, "wcag")
        assertContains(skills, "canvas")
        // Verbatim from the Skills section, not just the taxonomy:
        assertContains(skills, "lms administration")
    }

    @Test
    fun `duties are action bullets from the experience section`() {
        val duties = ResumeParser.parse(SAMPLE).duties
        assertTrue(duties.any { it.startsWith("Led a team of 12") }, "got: $duties")
        assertTrue(duties.any { "WCAG 2.1" in it }, "got: $duties")
        assertTrue(duties.none { it.startsWith("Director of Online Learning") })
    }

    @Test
    fun `certifications are detected`() {
        val certs = ResumeParser.parse(SAMPLE).certifications.joinToString(" ").lowercase()
        assertContains(certs, "pmp")
        assertContains(certs, "cpacc")
    }

    @Test
    fun `job titles are detected for query building`() {
        val titles = ResumeParser.parse(SAMPLE).titles
        assertContains(titles, "Director of Online Learning")
        assertContains(titles, "Instructional Designer")
    }

    @Test
    fun `summary paragraph is captured`() {
        assertTrue(ResumeParser.parse(SAMPLE).summary.startsWith("Education leader"))
    }

    @Test
    fun `the same resume twice does not double up the profile`() {
        val parsed = ResumeParser.parse(SAMPLE)
        val merged = ProfileBuilder.build(listOf(parsed, parsed), emptyList())

        assertEquals(1, merged.titles.count { it == "Director of Online Learning" })
        assertEquals(
            merged.skills.size,
            merged.skills.map { it.lowercase() }.toSet().size,
            "skills should be case-insensitively unique",
        )
    }

    @Test
    fun `an empty resume yields an empty profile`() {
        val profile = ProfileBuilder.build(listOf(ResumeParser.parse("")), emptyList())
        assertTrue(profile.isEmpty)
    }
}
