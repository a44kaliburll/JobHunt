package com.jobhunt.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.jobhunt.android.data.JobHuntDatabase
import com.jobhunt.android.data.SettingsStore
import com.jobhunt.android.work.JobHuntScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class JobHuntApplication : Application() {

    val database: JobHuntDatabase by lazy { JobHuntDatabase.get(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val repository: JobHuntRepository by lazy {
        JobHuntRepository(this, database, settingsStore)
    }

    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android loads its font resources from assets at first use.
        PDFBoxResourceLoader.init(this)
        createNotificationChannel()
        if (settingsStore.dailyRunEnabled) {
            JobHuntScheduler.scheduleDaily(this, settingsStore)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_MATCHES,
            getString(R.string.notification_channel_matches),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_matches_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_MATCHES = "jobhunt_matches"
    }
}
