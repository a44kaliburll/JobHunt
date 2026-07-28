package com.jobhunt.android

import android.content.Context
import android.net.Uri
import com.jobhunt.android.data.JobHuntDatabase
import com.jobhunt.android.data.JobSourceEntity
import com.jobhunt.android.data.ListingEntity
import com.jobhunt.android.data.ResumeEntity
import com.jobhunt.android.data.RunLogEntity
import com.jobhunt.android.data.SettingsStore
import com.jobhunt.android.data.toEntity
import com.jobhunt.android.resume.AndroidResumeReader
import com.jobhunt.core.CustomSource
import com.jobhunt.core.OkHttpFetcher
import com.jobhunt.core.Pipeline
import com.jobhunt.core.Reports
import com.jobhunt.core.ResumeJson
import com.jobhunt.core.ResumeParser
import com.jobhunt.core.RunResult
import com.jobhunt.core.SearchProfile
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Single seam between the Android layer and the pure core pipeline: reads
 * resumes and sources out of Room, runs the hunt, then persists what came back.
 */
class JobHuntRepository(
    private val context: Context,
    private val database: JobHuntDatabase,
    private val settingsStore: SettingsStore,
) {

    private val resumeReader = AndroidResumeReader(context)
    private val reportsDir: File
        get() = File(context.filesDir, "reports").apply { mkdirs() }

    val resumes: Flow<List<ResumeEntity>> = database.resumeDao().observeAll()
    val sources: Flow<List<JobSourceEntity>> = database.jobSourceDao().observeAll()
    val listings: Flow<List<ListingEntity>> = database.listingDao().observeVisible()
    val lastRun: Flow<RunLogEntity?> = database.runLogDao().observeLatest()

    /** The merged profile driving queries and scoring, recomputed on demand. */
    val profile: Flow<SearchProfile> = resumes.map { rows -> mergeProfile(rows) }

    private fun mergeProfile(rows: List<ResumeEntity>): SearchProfile =
        ResumeParser.mergeProfiles(
            rows.map { ResumeJson.decode(it.parsedJson) },
            settingsStore.settings.extraTitles,
        )

    // --- resumes ---

    /** Read, parse and store a resume the user picked. Returns its display name. */
    suspend fun addResume(uri: Uri): String = withContext(Dispatchers.IO) {
        val filename = resumeReader.displayName(uri)
        val text = resumeReader.extractText(uri, filename)
        require(text.isNotBlank()) {
            "No text could be read from $filename — if it is a scanned PDF, " +
                "export a text-based copy first."
        }
        val parsed = ResumeParser.parse(text)
        database.resumeDao().insert(
            ResumeEntity(
                filename = filename,
                contentText = text,
                parsedJson = ResumeJson.encode(parsed),
            ),
        )
        filename
    }

    suspend fun deleteResume(id: Long) = withContext(Dispatchers.IO) {
        database.resumeDao().deleteById(id)
    }

    // --- custom sources ---

    suspend fun addSource(name: String, kind: String, configJson: String) =
        withContext(Dispatchers.IO) {
            database.jobSourceDao().insert(
                JobSourceEntity(name = name.trim(), kind = kind, configJson = configJson.trim()),
            )
            Unit
        }

    suspend fun toggleSource(id: Long) = withContext(Dispatchers.IO) {
        database.jobSourceDao().getById(id)?.let { source ->
            database.jobSourceDao().update(source.copy(enabled = !source.enabled))
        }
        Unit
    }

    suspend fun deleteSource(source: JobSourceEntity) = withContext(Dispatchers.IO) {
        database.jobSourceDao().delete(source)
    }

    // --- listings ---

    suspend fun setListingStatus(id: Long, status: String) = withContext(Dispatchers.IO) {
        database.listingDao().updateStatus(id, status)
    }

    // --- the hunt ---

    /**
     * Run the full pipeline and persist the outcome: clear stale NEW flags,
     * insert new matches, bump improved scores, drop listings past retention,
     * log the run, and write the digest plus working list to app storage.
     */
    suspend fun runHunt(today: LocalDate = LocalDate.now()): RunResult =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings
            val profile = mergeProfile(database.resumeDao().getAll())
            val customSources = database.jobSourceDao().getEnabled().map {
                CustomSource(it.id, it.name, it.kind, it.configJson, it.enabled)
            }
            val existing = database.listingDao().getAll().map(ListingEntity::toCore)

            val pipeline = Pipeline(
                Pipeline.defaultScrapers(OkHttpFetcher(), customSources),
            )
            val result = pipeline.run(profile, settings, existing, today)

            persist(result, settings.retentionDays)
            result
        }

    private suspend fun persist(result: RunResult, retentionDays: Int) {
        val listingDao = database.listingDao()
        listingDao.clearNewFlags()
        if (result.newListings.isNotEmpty()) {
            listingDao.insertAll(result.newListings.map { it.toEntity() })
        }
        result.rescored.forEach { (key, score) -> listingDao.updateScore(key, score) }
        if (result.staleKeys.isNotEmpty()) {
            listingDao.deleteByKeys(result.staleKeys)
        }

        database.runLogDao().insert(
            RunLogEntity(
                date = result.date,
                fetched = result.fetched,
                inRange = result.inRange,
                relevant = result.relevant,
                newCount = result.newCount,
                queryCount = result.queries.size,
                warnings = result.errors.joinToString("\n"),
            ),
        )
        // Keep a season of run history; the digests on disk are the long record.
        database.runLogDao().deleteOlderThan(
            System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000,
        )

        writeReports(result, retentionDays)
    }

    private suspend fun writeReports(result: RunResult, retentionDays: Int) {
        File(reportsDir, "${result.date}.md").writeText(Reports.renderDigest(result))
        val visible = database.listingDao().getVisible().map(ListingEntity::toCore)
        File(reportsDir, "current_listings.md").writeText(
            Reports.renderWorkingList(
                visible,
                LocalDateTime.now().format(REFRESHED_FORMAT),
                retentionDays,
            ),
        )
    }

    /** Markdown for the share sheet; falls back to a hint before the first run. */
    fun workingListMarkdown(): String {
        val file = File(reportsDir, "current_listings.md")
        return if (file.exists()) file.readText()
        else "# Job Hunt\n\nNo runs yet — start a hunt to build your working list.\n"
    }

    private companion object {
        val REFRESHED_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
