package com.jobhunt.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jobhunt.android.data.SettingsStore
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Sets up and tears down the recurring background hunt. */
object JobHuntScheduler {

    fun scheduleDaily(context: Context, settings: SettingsStore) {
        val request = PeriodicWorkRequestBuilder<JobHuntWorker>(Duration.ofDays(1))
            .setConstraints(constraints(settings))
            .setInitialDelay(delayUntilNextRun(settings.dailyRunHour))
            .addTag(JobHuntWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            JobHuntWorker.WORK_NAME,
            // UPDATE keeps the existing schedule's history when only the
            // constraints or run hour changed.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(JobHuntWorker.WORK_NAME)
    }

    /** Kick off a hunt right now, without disturbing the daily schedule. */
    fun runNow(context: Context, settings: SettingsStore) {
        val request = OneTimeWorkRequestBuilder<JobHuntWorker>()
            .setConstraints(constraints(settings))
            .addTag(MANUAL_WORK_NAME)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a hunt is queued or running, so the UI can show progress. */
    fun observeRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(MANUAL_WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }

    private fun constraints(settings: SettingsStore) = Constraints.Builder()
        .setRequiredNetworkType(
            if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()

    /** Time from now until the next occurrence of [hour]:00 local time. */
    internal fun delayUntilNextRun(
        hour: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): Duration {
        var next = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    private const val MANUAL_WORK_NAME = "jobhunt_manual"
}
