package com.jobhunt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON encoding for a [ParsedResume], used to persist parse results alongside
 * the resume itself. Written by hand against [JsonObject] so the module needs
 * no serialization compiler plugin.
 */
object ResumeJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(parsed: ParsedResume): String = buildJsonObject {
        put("skills", parsed.skills.toJsonArray())
        put("duties", parsed.duties.toJsonArray())
        put("certifications", parsed.certifications.toJsonArray())
        put("titles", parsed.titles.toJsonArray())
        put("summary", JsonPrimitive(parsed.summary))
    }.toString()

    /** Lenient by design: a corrupt row degrades to empty rather than crashing. */
    fun decode(raw: String): ParsedResume {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return ParsedResume()
        return ParsedResume(
            skills = root.stringList("skills"),
            duties = root.stringList("duties"),
            certifications = root.stringList("certifications"),
            titles = root.stringList("titles"),
            summary = (root["summary"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    private fun List<String>.toJsonArray(): JsonArray =
        buildJsonArray { forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.orEmpty()
}
