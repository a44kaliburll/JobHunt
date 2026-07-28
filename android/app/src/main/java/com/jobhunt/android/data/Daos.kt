package com.jobhunt.android.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resumes ORDER BY uploadedAt DESC")
    fun observeAll(): Flow<List<ResumeEntity>>

    @Query("SELECT * FROM resumes ORDER BY uploadedAt DESC")
    suspend fun getAll(): List<ResumeEntity>

    @Insert
    suspend fun insert(resume: ResumeEntity): Long

    @Query("DELETE FROM resumes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface JobSourceDao {
    @Query("SELECT * FROM job_sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JobSourceEntity>>

    @Query("SELECT * FROM job_sources WHERE enabled = 1")
    suspend fun getEnabled(): List<JobSourceEntity>

    @Insert
    suspend fun insert(source: JobSourceEntity): Long

    @Update
    suspend fun update(source: JobSourceEntity)

    @Delete
    suspend fun delete(source: JobSourceEntity)

    @Query("SELECT * FROM job_sources WHERE id = :id")
    suspend fun getById(id: Long): JobSourceEntity?
}

@Dao
interface ListingDao {
    @Query(
        """
        SELECT * FROM listings
        WHERE status != 'hidden'
        ORDER BY firstSeen DESC, score DESC
        """,
    )
    fun observeVisible(): Flow<List<ListingEntity>>

    @Query("SELECT * FROM listings ORDER BY firstSeen DESC, score DESC")
    suspend fun getAll(): List<ListingEntity>

    @Query("SELECT * FROM listings WHERE status != 'hidden' ORDER BY firstSeen DESC, score DESC")
    suspend fun getVisible(): List<ListingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(listings: List<ListingEntity>)

    @Query("UPDATE listings SET isNew = 0 WHERE isNew = 1")
    suspend fun clearNewFlags()

    @Query("UPDATE listings SET score = :score WHERE key = :key")
    suspend fun updateScore(key: String, score: Int)

    @Query("UPDATE listings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM listings WHERE key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("SELECT COUNT(*) FROM listings WHERE isNew = 1")
    suspend fun countNew(): Int
}

@Dao
interface RunLogDao {
    @Query("SELECT * FROM run_logs ORDER BY ranAt DESC LIMIT 1")
    fun observeLatest(): Flow<RunLogEntity?>

    @Insert
    suspend fun insert(runLog: RunLogEntity)

    @Query("DELETE FROM run_logs WHERE ranAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
