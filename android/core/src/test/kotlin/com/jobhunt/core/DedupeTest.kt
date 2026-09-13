package com.jobhunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun posting(
    key: String,
    title: String,
    company: String = "",
    location: String = "Albany, NY",
    posted: String = "",
    description: String = "",
) = JobPosting(
    key = key, title = title, company = company, location = location,
    posted = posted, description = description, source = key.substringBefore(':'),
)

class DedupeTest {

    // The aggregator case, taken from a real run: the same Troy NY role arrived
    // twice, once under the employer and once under a board reposting it.
    @Test
    fun `a cross-post under a different company collapses to one job`() {
        val merged = Dedupe.collapse(
            listOf(
                posting(
                    "li:4376424817", "Associate Director, Digital Lab Orchestration",
                    company = "Regeneron", location = "Troy, NY", posted = "2026-05-21",
                ) to 12,
                posting(
                    "li:4420251030", "Associate Director, Digital Lab Orchestration",
                    company = "BioSpace", location = "Troy, NY", posted = "2026-05-28",
                ) to 12,
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("Regeneron", merged.single().posting.company, "earliest posting wins the tie")
        assertEquals(listOf("BioSpace"), merged.single().alsoPostedBy)
    }

    // The repost case, also from a real run: one continuously-recruited req
    // advertised under three vacancy ids over six weeks.
    @Test
    fun `repeated reposts of one req collapse to one job`() {
        val merged = Dedupe.collapse(
            listOf("nys:214159" to "2026-04-16", "nys:216265" to "2026-05-18", "nys:216988" to "2026-05-28")
                .map { (key, date) ->
                    posting(
                        key, "Design Project Manager - Continuous Recruitment",
                        company = "State University Construction Fund",
                        location = "Albany County, NY", posted = date,
                    ) to 16
                },
        )

        assertEquals(1, merged.size)
        assertEquals("nys:214159", merged.single().posting.key)
    }

    @Test
    fun `two employers hiring the same generic role stay separate`() {
        val merged = Dedupe.collapse(
            listOf(
                posting("li:1", "Project Manager", company = "Acme") to 10,
                posting("li:2", "Project Manager", company = "Beta Industries") to 10,
            ),
        )

        assertEquals(2, merged.size, "a two-word title is not enough to call these one job")
    }

    @Test
    fun `the same generic role reposted by one employer still collapses`() {
        val merged = Dedupe.collapse(
            listOf(
                posting("li:1", "Project Manager", company = "Acme", posted = "2026-05-01") to 10,
                posting("li:2", "Project Manager", company = "Acme", posted = "2026-06-01") to 10,
            ),
        )

        assertEquals(1, merged.size)
    }

    @Test
    fun `word order does not create a duplicate`() {
        assertEquals(
            Dedupe.groupKey("Director of Online Learning", "Albany, NY", "Acme"),
            Dedupe.groupKey("Online Learning Director", "Albany, NY", "Acme"),
        )
    }

    @Test
    fun `county and bare city spellings agree`() {
        assertEquals(
            Dedupe.groupKey("Education Program Manager One", "Albany County, NY", "NYSED"),
            Dedupe.groupKey("Education Program Manager One", "Albany, NY", "NYSED"),
        )
    }

    @Test
    fun `every flavour of remote agrees`() {
        assertEquals("remote", Dedupe.normalizeLocation("USA Remote"))
        assertEquals("remote", Dedupe.normalizeLocation("Flexible / Remote"))
        assertEquals("remote", Dedupe.normalizeLocation("Anywhere (100% Remote)"))
        assertEquals("albany ny", Dedupe.normalizeLocation("Albany, NY"))
    }

    @Test
    fun `different jobs in different cities never merge`() {
        val merged = Dedupe.collapse(
            listOf(
                posting("li:1", "Associate Director, Digital Lab Orchestration", location = "Troy, NY") to 12,
                posting("li:2", "Associate Director, Digital Lab Orchestration", location = "Boston, MA") to 12,
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `the copy carrying a description is the one kept`() {
        val merged = Dedupe.collapse(
            listOf(
                posting("li:1", "Senior Instructional Designer Lead", company = "Acme") to 6,
                posting(
                    "wwr:2", "Senior Instructional Designer Lead", company = "Acme",
                    description = "Full description here",
                ) to 24,
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("wwr:2", merged.single().posting.key, "the higher-scoring copy wins")
        assertEquals(24, merged.single().score)
    }

    @Test
    fun `title distinctiveness ignores filler words`() {
        assertTrue(Dedupe.isDistinctiveTitle("Associate Director, Digital Lab Orchestration"))
        assertFalse(Dedupe.isDistinctiveTitle("Project Manager"))
        assertFalse(Dedupe.isDistinctiveTitle("Senior Project Manager II"), "senior and II are filler")
    }

    @Test
    fun `an empty run collapses to nothing`() {
        assertEquals(emptyList(), Dedupe.collapse(emptyList()))
    }
}
