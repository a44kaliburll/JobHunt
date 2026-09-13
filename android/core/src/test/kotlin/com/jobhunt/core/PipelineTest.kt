package com.jobhunt.core

import com.jobhunt.core.scrapers.Scraper
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeBoard(
    override val name: String = "FakeBoard",
    private val postings: List<JobPosting>,
    /** key -> description, as a detail page would return. */
    private val descriptions: Map<String, String> = emptyMap(),
    private val describeThrows: Boolean = false,
) : Scraper {
    override val keyPrefix = "fb"
    var searchCount = 0
    val described = mutableListOf<String>()

    override val supportsDescriptions: Boolean
        get() = descriptions.isNotEmpty() || describeThrows

    override fun search(query: SearchQuery): List<JobPosting> {
        searchCount++
        return postings
    }

    override fun describe(posting: JobPosting): String? {
        described += posting.key
        if (describeThrows) throw java.io.IOException("429 Too Many Requests")
        return descriptions[posting.key]
    }
}

private val PROFILE = SearchProfile(
    titles = listOf("Director of Online Learning"),
    skills = listOf("instructional design", "canvas", "wcag"),
    duties = listOf("Led a team of instructional designers"),
)

private val ON_TARGET = JobPosting(
    key = "fb:1",
    title = "Director of Online Learning",
    company = "Acme U",
    location = "Albany, NY",
    url = "https://fake.test/1",
    source = "FakeBoard",
    posted = "2026-06-01",
    description = "instructional design canvas wcag",
)
private val LOW_SCORE = JobPosting(
    key = "fb:2",
    title = "Forklift Operator",
    company = "Warehouse Inc",
    location = "Albany, NY",
    source = "FakeBoard",
    description = "forklift",
)
private val OUT_OF_RANGE = JobPosting(
    key = "fb:3",
    title = "Instructional Designer",
    company = "Far Away College",
    location = "San Francisco, CA",
    source = "FakeBoard",
    description = "instructional design",
)

private val SETTINGS = HuntSettings(locations = listOf("Albany, NY"))
private val TODAY = LocalDate.of(2026, 6, 9)

class PipelineTest {

    private fun pipeline(vararg postings: JobPosting) =
        Pipeline(listOf(FakeBoard(postings = postings.toList())))

    @Test
    fun `the funnel drops out-of-range and low-scoring postings`() {
        val result = pipeline(ON_TARGET, LOW_SCORE, OUT_OF_RANGE)
            .run(PROFILE, SETTINGS, existing = emptyList(), today = TODAY)

        assertEquals(3, result.fetched)
        assertEquals(2, result.inRange) // the San Francisco posting is dropped
        assertEquals(1, result.relevant) // the forklift job is below threshold
        assertEquals(1, result.newCount)
        assertEquals("fb:1", result.newListings.single().key)
        assertTrue(result.newListings.single().isNew)
        assertEquals("2026-06-09", result.newListings.single().firstSeen)
    }

    @Test
    fun `already-stored listings are not reported as new again`() {
        val stored = Listing(
            key = "fb:1", title = "Director of Online Learning", score = 35,
            firstSeen = "2026-06-01", isNew = false,
        )
        val result = pipeline(ON_TARGET)
            .run(PROFILE, SETTINGS, existing = listOf(stored), today = TODAY)

        assertEquals(1, result.relevant)
        assertEquals(0, result.newCount)
        assertTrue(result.rescored.isEmpty(), "score did not improve, so nothing to update")
    }

    @Test
    fun `an improved score on a known listing is reported for update`() {
        val stored = Listing(key = "fb:1", title = "Director of Online Learning", score = 2)
        val result = pipeline(ON_TARGET)
            .run(PROFILE, SETTINGS, existing = listOf(stored), today = TODAY)

        assertEquals(0, result.newCount)
        assertTrue(result.rescored.getValue("fb:1") > 2)
    }

    @Test
    fun `a bare posting is scored on its description once fetched`() {
        // Same posting, with and without the board filling in its description.
        val bare = ON_TARGET.copy(description = "")
        val withoutFetch = Pipeline(listOf(FakeBoard(postings = listOf(bare))))
            .run(PROFILE, SETTINGS, emptyList(), TODAY)
        val withFetch = Pipeline(
            listOf(
                FakeBoard(
                    postings = listOf(bare),
                    descriptions = mapOf("fb:1" to "instructional design canvas wcag"),
                ),
            ),
        ).run(PROFILE, SETTINGS, emptyList(), TODAY)

        assertEquals(0, withoutFetch.enriched)
        assertEquals(1, withFetch.enriched)
        assertTrue(
            withFetch.newListings.single().score > withoutFetch.newListings.single().score,
            "fetching the description should unlock skill and duty points",
        )
    }

    @Test
    fun `postings that already carry a description are not re-fetched`() {
        val board = FakeBoard(
            postings = listOf(ON_TARGET),
            descriptions = mapOf("fb:1" to "would be wasteful"),
        )
        Pipeline(listOf(board)).run(PROFILE, SETTINGS, emptyList(), TODAY)

        assertTrue(board.described.isEmpty())
    }

    @Test
    fun `the description budget caps how many requests a run makes`() {
        val bare = (1..10).map {
            ON_TARGET.copy(key = "fb:$it", url = "https://fake.test/$it", description = "")
        }
        val board = FakeBoard(
            postings = bare,
            descriptions = bare.associate { it.key to "instructional design" },
        )
        Pipeline(listOf(board)).run(
            PROFILE, SETTINGS.copy(descriptionBudget = 3), emptyList(), TODAY,
        )

        assertEquals(3, board.described.size)
    }

    @Test
    fun `a board refusing detail requests degrades to a warning`() {
        val board = FakeBoard(postings = listOf(ON_TARGET.copy(description = "")), describeThrows = true)
        val result = Pipeline(listOf(board)).run(PROFILE, SETTINGS, emptyList(), TODAY)

        assertEquals(0, result.enriched)
        assertTrue(result.errors.any { "429" in it }, "got: ${result.errors}")
        assertEquals(1, result.newCount, "the run still produces its listings")
    }

    @Test
    fun `a job cross-posted under two companies becomes one listing`() {
        val employer = JobPosting(
            key = "fb:10", title = "Associate Director, Digital Lab Orchestration",
            company = "Regeneron", location = "Albany, NY", posted = "2026-05-21",
            source = "FakeBoard", description = "instructional design",
        )
        val aggregator = employer.copy(
            key = "fb:11", company = "BioSpace", posted = "2026-05-28",
        )
        val result = Pipeline(listOf(FakeBoard(postings = listOf(employer, aggregator))))
            .run(PROFILE, SETTINGS, emptyList(), TODAY)

        assertEquals(2, result.relevant, "both copies passed scoring")
        assertEquals(1, result.newCount, "but only one job is stored")
        assertEquals(1, result.duplicates)
        assertEquals(listOf("BioSpace"), result.newListings.single().alsoPostedBy)
    }

    @Test
    fun `a repost of a job already stored is not surfaced as new`() {
        val stored = Listing(
            key = "fb:99",
            title = ON_TARGET.title,
            company = ON_TARGET.company,
            location = ON_TARGET.location,
            score = 35,
            firstSeen = "2026-06-01",
            isNew = false,
            groupKey = Dedupe.groupKey(ON_TARGET),
        )
        val result = Pipeline(listOf(FakeBoard(postings = listOf(ON_TARGET))))
            .run(PROFILE, SETTINGS, existing = listOf(stored), today = TODAY)

        assertEquals(0, result.newCount, "same job, new vacancy id")
    }

    @Test
    fun `duplicate keys across boards collapse to one listing`() {
        val boards = listOf(
            FakeBoard("BoardA", listOf(ON_TARGET)),
            FakeBoard("BoardB", listOf(ON_TARGET.copy(source = "BoardB"))),
        )
        val result = Pipeline(boards).run(PROFILE, SETTINGS, emptyList(), TODAY)

        assertEquals(2, result.fetched, "both boards returned it")
        assertEquals(1, result.newCount, "but it is stored once")
        assertEquals("FakeBoard", result.newListings.single().source, "first sighting wins")
    }

    @Test
    fun `listings past the retention window are marked stale`() {
        val old = Listing(key = "fb:old", title = "Old Job", firstSeen = "2025-01-01")
        val fresh = Listing(key = "fb:1", title = "Recent Job", firstSeen = "2026-06-01")
        val result = pipeline(ON_TARGET)
            .run(PROFILE, SETTINGS, existing = listOf(old, fresh), today = TODAY)

        assertEquals(listOf("fb:old"), result.staleKeys)
    }

    @Test
    fun `a run without a profile stops before touching the network`() {
        val board = FakeBoard(postings = listOf(ON_TARGET))
        val result = Pipeline(listOf(board)).run(SearchProfile(), SETTINGS, emptyList(), TODAY)

        assertEquals(0, board.searchCount, "no queries should be issued")
        assertEquals(0, result.fetched)
        assertTrue(result.errors.any { "No profile yet" in it }, "got: ${result.errors}")
    }

    @Test
    fun `every query fanned is recorded for the digest`() {
        val profile = PROFILE.copy(titles = listOf("Director of Online Learning", "Designer"))
        val settings = SETTINGS.copy(locations = listOf("Albany, NY", "Remote"))
        val result = pipeline(ON_TARGET).run(profile, settings, emptyList(), TODAY)

        assertEquals(4, result.queries.size) // 2 titles x 2 locations x 1 board
        assertContains(result.queries, "FakeBoard: Director of Online Learning @ Albany, NY")
    }

    @Test
    fun `digest and working list render the artex format`() {
        val result = pipeline(ON_TARGET).run(PROFILE, SETTINGS, emptyList(), TODAY)

        val digest = Reports.renderDigest(result)
        assertContains(digest, "# Job Hunt - 2026-06-09")
        assertContains(digest, "**Funnel:**")
        assertContains(digest, "Director of Online Learning")
        assertContains(digest, "## Queries fanned")

        val working = Reports.renderWorkingList(result.newListings, "2026-06-09 07:26")
        assertContains(working, "# Job Hunt — Working List")
        assertContains(working, "**🆕 NEW** — Director of Online Learning — Acme U")
        assertContains(working, "## 2026-06-09")
    }
}
