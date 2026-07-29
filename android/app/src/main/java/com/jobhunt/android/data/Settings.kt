package com.jobhunt.android.data

import android.content.Context
import androidx.core.content.edit
import com.jobhunt.core.DEFAULT_MIN_SCORE
import com.jobhunt.core.HuntSettings
import com.jobhunt.core.RETENTION_DAYS
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** User preferences for the hunt, backed by SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jobhunt_settings", Context.MODE_PRIVATE)

    var settings: HuntSettings
        get() = HuntSettings(
            locations = prefs.getString(KEY_LOCATIONS, "").orEmpty().toCsvList(),
            minScore = prefs.getInt(KEY_MIN_SCORE, DEFAULT_MIN_SCORE),
            retentionDays = prefs.getInt(KEY_RETENTION, RETENTION_DAYS),
        )
        set(value) = prefs.edit {
            putString(KEY_LOCATIONS, value.locations.joinToString(", "))
            putInt(KEY_MIN_SCORE, value.minScore)
            putInt(KEY_RETENTION, value.retentionDays)
        }

    /**
     * Titles from the old settings field, before job titles moved into the
     * editable profile. Read once at startup so they are carried over rather
     * than silently dropped.
     */
    val legacyExtraTitles: List<String>
        get() = prefs.getString(KEY_EXTRA_TITLES, "").orEmpty().toCsvList()

    fun clearLegacyExtraTitles() = prefs.edit { remove(KEY_EXTRA_TITLES) }

    var dailyRunEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_DAILY_ENABLED, value) }

    /** Hour of day (0-23) for the scheduled run. */
    var dailyRunHour: Int
        get() = prefs.getInt(KEY_DAILY_HOUR, 7)
        set(value) = prefs.edit { putInt(KEY_DAILY_HOUR, value.coerceIn(0, 23)) }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    /** Emits on every preference change so the UI stays in sync. */
    fun observe(): Flow<HuntSettings> = callbackFlow {
        trySend(settings)
        val listener = android.content.SharedPreferences
            .OnSharedPreferenceChangeListener { _, _ -> trySend(settings) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_LOCATIONS = "locations"
        const val KEY_EXTRA_TITLES = "extra_titles"
        const val KEY_MIN_SCORE = "min_score"
        const val KEY_RETENTION = "retention_days"
        const val KEY_DAILY_ENABLED = "daily_enabled"
        const val KEY_DAILY_HOUR = "daily_hour"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}

fun String.toCsvList(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }
