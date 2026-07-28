package com.jobhunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val PROFILE = SearchProfile(
    titles = listOf("Director of Online Learning", "Instructional Designer"),
    skills = listOf("instructional design", "wcag", "canvas", "project management"),
    duties = listOf(
        "Led a team of 12 instructional designers",
        "Implemented WCAG accessibility standards across 300 courses",
    ),
    certifications = listOf("pmp", "cpacc"),
)

class MatchingTest {

    @Test
    fun `an on-target posting scores high across every component`() {
        val posting = JobPosting(
            key = "x:1",
            title = "Director of Online Learning",
            description = "Seeking experience with instructional design, WCAG, " +
                "Canvas and project management. PMP preferred. You will lead a " +
                "team of instructional designers and implement accessibility " +
                "standards across courses.",
        )
        val breakdown = Matching.score(posting, PROFILE)
        assertEquals(20, breakdown.title)
        assertEquals(12, breakdown.skills) // 4 skills x 3
        assertEquals(5, breakdown.certifications)
        assertTrue(breakdown.duties > 0)
        assertTrue(breakdown.total <= MAX_SCORE)
    }

    @Test
    fun `an unrelated posting scores near zero`() {
        val posting = JobPosting(key = "x:2", title = "Forklift Operator", description = "Warehouse.")
        assertTrue(Matching.score(posting, PROFILE).total <= 2)
    }

    @Test
    fun `match bar renders exactly like the original digests`() {
        assertEquals("[████░░░░░░] 38% (19/50)", Matching.matchBar(19))
        assertEquals("[██████████] 100% (50/50)", Matching.matchBar(50))
        assertEquals("[░░░░░░░░░░] 0% (0/50)", Matching.matchBar(0))
    }

    @Test
    fun `location matching is loose but not sloppy`() {
        assertTrue(Matching.locationInRange("Albany, NY", listOf("Albany, NY")))
        assertTrue(Matching.locationInRange("Greater Albany Area", listOf("Albany, NY")))
        assertTrue(Matching.locationInRange("USA Remote", listOf("Remote")))
        assertTrue(Matching.locationInRange("Flexible / Remote", listOf("Remote")))
        assertFalse(Matching.locationInRange("San Francisco, CA", listOf("Albany, NY")))
        assertTrue(Matching.locationInRange("Anywhere At All", emptyList()))
    }

    @Test
    fun `queries fan titles across locations`() {
        val combos = Matching.buildQueries(PROFILE, listOf("Albany, NY", "Remote"))
            .map { it.keywords to it.location }
        assertTrue("Director of Online Learning" to "Albany, NY" in combos)
        assertTrue("Instructional Designer" to "Remote" in combos)
    }

    @Test
    fun `queries fall back to skills when a resume has no titles`() {
        val queries = Matching.buildQueries(
            SearchProfile(skills = listOf("welding", "cnc")),
            emptyList(),
        )
        assertTrue(queries.any { it.keywords == "welding" })
    }

    @Test
    fun `query fan-out is capped so boards are not hammered`() {
        val wide = SearchProfile(titles = (1..8).map { "Title $it" })
        val queries = Matching.buildQueries(wide, (1..10).map { "City $it" })
        assertEquals(MAX_QUERIES_PER_SOURCE, queries.size)
    }
}
