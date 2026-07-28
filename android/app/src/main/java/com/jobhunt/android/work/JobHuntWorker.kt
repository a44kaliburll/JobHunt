package com.jobhunt.android.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jobhunt.android.JobHuntApplication
import com.jobhunt.android.R
import com.jobhunt.android.ui.MainActivity
import com.jobhunt.core.Matching
import com.jobhunt.core.RunResult

/**
 * The scheduled hunt — this app's answer to the original nightly cron job.
 *
 * Runs the pipeline in the background and posts a notification only when there
 * is something genuinely new to look at.
 */
class JobHuntWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as JobHuntApplication
        return try {
            val result = app.repository.runHunt()
            if (result.newCount > 0) notifyNewMatches(result)
            Result.success()
        } catch (e: Exception) {
            // Transient network trouble is the common case; let WorkManager
            // back off and try again rather than dropping the day's run.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun notifyNewMatches(result: RunResult) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // The user declined notifications; the app UI still shows results.
        }

        val best = result.newListings.first()
        val title = context.resources.getQuantityString(
            R.plurals.notification_new_matches, result.newCount, result.newCount,
        )
        val body = "${best.title} — ${best.company} ${Matching.matchBar(best.score)}"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, JobHuntApplication.CHANNEL_MATCHES)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "jobhunt_daily"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 3
    }
}
