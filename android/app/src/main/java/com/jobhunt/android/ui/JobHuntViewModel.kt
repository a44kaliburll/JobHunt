package com.jobhunt.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobhunt.android.JobHuntApplication
import com.jobhunt.android.data.JobSourceEntity
import com.jobhunt.android.data.ListingEntity
import com.jobhunt.android.data.ResumeEntity
import com.jobhunt.android.data.RunLogEntity
import com.jobhunt.android.data.toCsvList
import com.jobhunt.android.work.JobHuntScheduler
import com.jobhunt.core.HuntSettings
import com.jobhunt.core.MAX_SCORE
import com.jobhunt.core.ResumeJson
import com.jobhunt.core.SearchProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class JobHuntViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JobHuntApplication
    private val repository = app.repository
    private val settingsStore = app.settingsStore

    val resumes: StateFlow<List<ResumeEntity>> =
        repository.resumes.stateInDefault(emptyList())
    val sources: StateFlow<List<JobSourceEntity>> =
        repository.sources.stateInDefault(emptyList())
    val listings: StateFlow<List<ListingEntity>> =
        repository.listings.stateInDefault(emptyList())
    val lastRun: StateFlow<RunLogEntity?> =
        repository.lastRun.stateInDefault(null)
    val profile: StateFlow<SearchProfile> =
        repository.profile.stateInDefault(SearchProfile())
    val isRunning: StateFlow<Boolean> =
        JobHuntScheduler.observeRunning(app).stateInDefault(false)

    private val _settings = MutableStateFlow(settingsStore.settings)
    val settings: StateFlow<HuntSettings> = _settings.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val dailyRunEnabled: Boolean get() = settingsStore.dailyRunEnabled
    val dailyRunHour: Int get() = settingsStore.dailyRunHour
    val wifiOnly: Boolean get() = settingsStore.wifiOnly

    // --- resumes ---

    fun addResumes(uris: List<Uri>) = viewModelScope.launch {
        var added = 0
        for (uri in uris) {
            runCatching { repository.addResume(uri) }
                .onSuccess { added++ }
                .onFailure { error ->
                    _message.value = error.message ?: "Could not read that file."
                }
        }
        if (added > 0) {
            _message.value = "Added $added resume${if (added == 1) "" else "s"} " +
                "— your search profile has been updated."
        }
    }

    fun deleteResume(id: Long) = viewModelScope.launch { repository.deleteResume(id) }

    fun parsedOf(resume: ResumeEntity) = ResumeJson.decode(resume.parsedJson)

    // --- settings ---

    fun updateSettings(locations: String, extraTitles: String, minScore: Int) {
        val updated = HuntSettings(
            locations = locations.toCsvList(),
            extraTitles = extraTitles.toCsvList(),
            minScore = minScore.coerceIn(0, MAX_SCORE),
            retentionDays = settingsStore.settings.retentionDays,
        )
        settingsStore.settings = updated
        _settings.value = updated
        _message.value = "Search settings saved."
    }

    fun setDailyRunEnabled(enabled: Boolean) {
        settingsStore.dailyRunEnabled = enabled
        if (enabled) JobHuntScheduler.scheduleDaily(app, settingsStore)
        else JobHuntScheduler.cancelDaily(app)
    }

    fun setDailyRunHour(hour: Int) {
        settingsStore.dailyRunHour = hour
        if (settingsStore.dailyRunEnabled) JobHuntScheduler.scheduleDaily(app, settingsStore)
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        settingsStore.wifiOnly = wifiOnly
        if (settingsStore.dailyRunEnabled) JobHuntScheduler.scheduleDaily(app, settingsStore)
    }

    // --- custom sources ---

    /** Returns an error message when the config is not usable, else null. */
    fun validateSourceConfig(kind: String, configJson: String): String? {
        val root = runCatching {
            Json.parseToJsonElement(configJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return "That is not valid JSON."
        val url = (root["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (url.isNullOrBlank()) return "The config needs a \"url\" field."
        if (kind == "html" && root["selectors"] == null) {
            return "An HTML source needs a \"selectors\" object with at least \"item\"."
        }
        return null
    }

    fun addSource(name: String, kind: String, configJson: String) = viewModelScope.launch {
        if (name.isBlank()) {
            _message.value = "Give the source a name."
            return@launch
        }
        validateSourceConfig(kind, configJson)?.let {
            _message.value = it
            return@launch
        }
        repository.addSource(name, kind, configJson)
        _message.value = "Added $name to your sources."
    }

    fun toggleSource(id: Long) = viewModelScope.launch { repository.toggleSource(id) }

    fun deleteSource(source: JobSourceEntity) = viewModelScope.launch {
        repository.deleteSource(source)
    }

    // --- listings and runs ---

    fun setListingStatus(id: Long, status: String) = viewModelScope.launch {
        repository.setListingStatus(id, status)
    }

    fun runHuntNow() {
        if (profile.value.isEmpty) {
            _message.value = "Add a resume first — it is what the searches are built from."
            return
        }
        JobHuntScheduler.runNow(app, settingsStore)
        _message.value = "Hunting… results appear here as boards respond."
    }

    fun workingListMarkdown(): String = repository.workingListMarkdown()

    fun consumeMessage() {
        _message.value = null
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInDefault(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
