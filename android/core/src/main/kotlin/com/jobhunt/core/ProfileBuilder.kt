package com.jobhunt.core

/** The editable parts of a profile. Summary is descriptive only, so it is not listed. */
enum class ProfileField(val key: String, val label: String) {
    TITLES("titles", "Job titles"),
    SKILLS("skills", "Skills"),
    CERTIFICATIONS("certifications", "Certifications"),
    DUTIES("duties", "Experience"),
    ;

    companion object {
        fun fromKey(key: String): ProfileField? = entries.firstOrNull { it.key == key }
    }
}

/** Where a profile item came from. */
enum class ItemOrigin { RESUME, MANUAL }

/**
 * One user edit. Either an item typed in by hand (`hidden = false`) or a
 * suppression of an item that came out of a resume (`hidden = true`).
 */
data class ProfileEdit(
    val field: ProfileField,
    val value: String,
    val hidden: Boolean = false,
)

/** A profile entry as the UI should show it. */
data class ProfileItem(
    val value: String,
    val origin: ItemOrigin,
    val hidden: Boolean,
)

/**
 * Combines what was parsed out of resumes with the user's own edits.
 *
 * Edits are kept as a separate layer rather than written back into the parsed
 * resumes, so adding, re-uploading, or deleting a resume never discards
 * hand-curated changes. An item hidden here is also invisible to the hunt: it
 * stops generating queries and stops contributing to match scores.
 */
object ProfileBuilder {

    /** Every item for one field, in a stable order, including hidden ones. */
    fun items(
        field: ProfileField,
        resumes: List<ParsedResume>,
        edits: List<ProfileEdit>,
    ): List<ProfileItem> {
        val hidden = edits.filter { it.field == field && it.hidden }
            .mapTo(mutableSetOf()) { it.value.normalizedForProfile() }

        val seen = mutableSetOf<String>()
        val items = mutableListOf<ProfileItem>()

        fun add(value: String, origin: ItemOrigin) {
            val key = value.normalizedForProfile()
            if (key.isEmpty() || !seen.add(key)) return
            items += ProfileItem(value.trim(), origin, key in hidden)
        }

        // Resume-derived items first, in the order they were extracted...
        resumes.forEach { resume -> resume.valuesOf(field).forEach { add(it, ItemOrigin.RESUME) } }
        // ...then anything the user typed in, in the order they added it.
        edits.filter { it.field == field && !it.hidden }.forEach { add(it.value, ItemOrigin.MANUAL) }

        return items
    }

    /** The profile the hunt actually runs on: everything visible, nothing hidden. */
    fun build(resumes: List<ParsedResume>, edits: List<ProfileEdit>): SearchProfile {
        fun visible(field: ProfileField) =
            items(field, resumes, edits).filterNot { it.hidden }.map { it.value }

        return SearchProfile(
            skills = visible(ProfileField.SKILLS),
            duties = visible(ProfileField.DUTIES),
            certifications = visible(ProfileField.CERTIFICATIONS),
            titles = visible(ProfileField.TITLES),
            summary = resumes.firstOrNull { it.summary.isNotBlank() }?.summary.orEmpty(),
        )
    }

    /**
     * Whether [value] can be added to [field] — non-blank, and not already
     * present as a visible item. Returns the reason it cannot, or null.
     */
    fun rejectionReason(
        field: ProfileField,
        value: String,
        resumes: List<ParsedResume>,
        edits: List<ProfileEdit>,
    ): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "Type something first."
        if (trimmed.length > 200) return "That is too long for a single entry."
        val key = trimmed.normalizedForProfile()
        val existing = items(field, resumes, edits).firstOrNull {
            it.value.normalizedForProfile() == key
        }
        return when {
            existing == null -> null
            existing.hidden -> null // re-adding is how you restore a hidden item
            else -> "\"$trimmed\" is already in your ${field.label.lowercase()}."
        }
    }

    private fun ParsedResume.valuesOf(field: ProfileField): List<String> = when (field) {
        ProfileField.TITLES -> titles
        ProfileField.SKILLS -> skills
        ProfileField.CERTIFICATIONS -> certifications
        ProfileField.DUTIES -> duties
    }
}

/** Case- and whitespace-insensitive identity for profile entries. */
fun String.normalizedForProfile(): String = trim().lowercase()
