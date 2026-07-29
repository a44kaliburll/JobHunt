package com.jobhunt.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RESUME = ParsedResume(
    skills = listOf("Instructional Design", "WCAG", "Forklift Operation"),
    duties = listOf("Led a team of 12 instructional designers"),
    certifications = listOf("PMP"),
    titles = listOf("Director of Online Learning"),
    summary = "Education leader.",
)

class ProfileBuilderTest {

    @Test
    fun `a title can be added without uploading anything`() {
        val edits = listOf(ProfileEdit(ProfileField.TITLES, "Head of Learning Technology"))
        val profile = ProfileBuilder.build(emptyList(), edits)

        assertEquals(listOf("Head of Learning Technology"), profile.titles)
        assertFalse(profile.isEmpty, "a manual title alone is enough to run a hunt")
    }

    @Test
    fun `manual items sit after resume items and both reach the hunt`() {
        val edits = listOf(ProfileEdit(ProfileField.SKILLS, "Kotlin"))
        val profile = ProfileBuilder.build(listOf(RESUME), edits)

        assertEquals(
            listOf("Instructional Design", "WCAG", "Forklift Operation", "Kotlin"),
            profile.skills,
        )
    }

    @Test
    fun `hiding an irrelevant extracted skill removes it from the hunt`() {
        val edits = listOf(ProfileEdit(ProfileField.SKILLS, "forklift operation", hidden = true))
        val profile = ProfileBuilder.build(listOf(RESUME), edits)

        assertEquals(listOf("Instructional Design", "WCAG"), profile.skills)
    }

    @Test
    fun `hidden items are still listed for the UI, flagged as hidden`() {
        val edits = listOf(ProfileEdit(ProfileField.SKILLS, "WCAG", hidden = true))
        val items = ProfileBuilder.items(ProfileField.SKILLS, listOf(RESUME), edits)

        val wcag = items.single { it.value == "WCAG" }
        assertTrue(wcag.hidden)
        assertEquals(ItemOrigin.RESUME, wcag.origin)
        assertFalse(items.single { it.value == "Instructional Design" }.hidden)
    }

    @Test
    fun `hiding is case insensitive so casing differences do not leak through`() {
        val edits = listOf(ProfileEdit(ProfileField.CERTIFICATIONS, "  pmp  ", hidden = true))
        assertTrue(ProfileBuilder.build(listOf(RESUME), edits).certifications.isEmpty())
    }

    @Test
    fun `items are tagged by origin so the UI can tell them apart`() {
        val edits = listOf(ProfileEdit(ProfileField.TITLES, "Head of Learning Technology"))
        val items = ProfileBuilder.items(ProfileField.TITLES, listOf(RESUME), edits)

        assertEquals(ItemOrigin.RESUME, items[0].origin)
        assertEquals(ItemOrigin.MANUAL, items[1].origin)
    }

    @Test
    fun `adding something a resume already has does not duplicate it`() {
        val edits = listOf(ProfileEdit(ProfileField.SKILLS, "instructional design"))
        val skills = ProfileBuilder.build(listOf(RESUME), edits).skills

        assertEquals(1, skills.count { it.equals("instructional design", ignoreCase = true) })
        assertEquals("Instructional Design", skills.first(), "the resume's casing wins")
    }

    @Test
    fun `edits survive a resume being replaced`() {
        val edits = listOf(
            ProfileEdit(ProfileField.SKILLS, "Kotlin"),
            ProfileEdit(ProfileField.SKILLS, "forklift operation", hidden = true),
        )
        val replacement = RESUME.copy(skills = listOf("Forklift Operation", "Curriculum Design"))
        val skills = ProfileBuilder.build(listOf(replacement), edits).skills

        assertContains(skills, "Kotlin")
        assertContains(skills, "Curriculum Design")
        assertFalse(skills.any { it.equals("forklift operation", ignoreCase = true) })
    }

    @Test
    fun `duties are editable too, not just the short fields`() {
        val edits = listOf(
            ProfileEdit(ProfileField.DUTIES, "Led a team of 12 instructional designers", hidden = true),
            ProfileEdit(ProfileField.DUTIES, "Ran the accessibility remediation programme"),
        )
        assertEquals(
            listOf("Ran the accessibility remediation programme"),
            ProfileBuilder.build(listOf(RESUME), edits).duties,
        )
    }

    @Test
    fun `the summary still comes from the resume`() {
        assertEquals("Education leader.", ProfileBuilder.build(listOf(RESUME), emptyList()).summary)
    }

    @Test
    fun `blank and duplicate entries are rejected with a reason`() {
        assertNotNull(ProfileBuilder.rejectionReason(ProfileField.SKILLS, "   ", listOf(RESUME), emptyList()))
        assertNotNull(
            ProfileBuilder.rejectionReason(ProfileField.SKILLS, "wcag", listOf(RESUME), emptyList()),
        )
        assertNull(
            ProfileBuilder.rejectionReason(ProfileField.SKILLS, "Kotlin", listOf(RESUME), emptyList()),
        )
    }

    @Test
    fun `re-adding a hidden item is allowed, which is how it gets restored`() {
        val edits = listOf(ProfileEdit(ProfileField.SKILLS, "WCAG", hidden = true))
        assertNull(ProfileBuilder.rejectionReason(ProfileField.SKILLS, "WCAG", listOf(RESUME), edits))
    }

    @Test
    fun `an edited profile drives query building`() {
        val edits = listOf(ProfileEdit(ProfileField.TITLES, "Head of Learning Technology"))
        val profile = ProfileBuilder.build(emptyList(), edits)
        val queries = Matching.buildQueries(profile, listOf("Albany, NY"))

        assertEquals(1, queries.size)
        assertEquals("Head of Learning Technology", queries.single().keywords)
    }
}
