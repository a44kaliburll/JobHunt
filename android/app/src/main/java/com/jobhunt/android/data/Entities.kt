package com.jobhunt.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jobhunt.core.Listing

@Entity(tableName = "resumes")
data class ResumeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val contentText: String,
    /** Serialized [com.jobhunt.core.ParsedResume]. */
    val parsedJson: String,
    val uploadedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "job_sources")
data class JobSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "rss" | "json" | "html" */
    val kind: String,
    val configJson: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "listings",
    indices = [Index(value = ["key"], unique = true), Index(value = ["firstSeen"])],
)
data class ListingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable dedupe key, e.g. "li:4412327417". */
    val key: String,
    val title: String,
    val company: String = "",
    val location: String = "",
    val url: String = "",
    val source: String = "",
    val posted: String = "",
    val score: Int = 0,
    val firstSeen: String = "",
    val isNew: Boolean = true,
    /** "" | "saved" | "applied" | "rejected" | "hidden" */
    val status: String = "",
) {
    fun toCore(): Listing = Listing(
        key = key, title = title, company = company, location = location,
        url = url, source = source, posted = posted, score = score,
        firstSeen = firstSeen, isNew = isNew, status = status,
    )
}

fun Listing.toEntity(id: Long = 0): ListingEntity = ListingEntity(
    id = id, key = key, title = title, company = company, location = location,
    url = url, source = source, posted = posted, score = score,
    firstSeen = firstSeen, isNew = isNew, status = status,
)

@Entity(tableName = "run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ranAt: Long = System.currentTimeMillis(),
    val date: String,
    val fetched: Int,
    val inRange: Int,
    val relevant: Int,
    val newCount: Int,
    val queryCount: Int,
    val warnings: String = "",
)
