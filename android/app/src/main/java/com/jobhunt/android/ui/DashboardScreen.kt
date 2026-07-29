package com.jobhunt.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobhunt.core.MAX_SCORE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: JobHuntViewModel,
    onSeeAllMatches: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val listings by viewModel.listings.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()
    val resumes by viewModel.resumes.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Text(
                "JobHunt",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
        }

        item {
            SectionCard("Your profile") {
                if (profile.isEmpty) {
                    EmptyState(
                        "Nothing to search on yet. Add job titles and skills by " +
                            "hand, or upload a resume to fill them in quickly.",
                    )
                } else {
                    Text(
                        if (resumes.isEmpty()) {
                            "Built entirely from entries you added."
                        } else {
                            "From ${resumes.size} resume" +
                                (if (resumes.size == 1) "" else "s") + ", plus your edits."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabeledChips("Search titles", profile.titles, "none yet")
                    LabeledChips("Skills", profile.skills, "none yet")
                    LabeledChips("Certifications", profile.certifications, "none yet")
                    Text(
                        "${profile.duties.size} experience bullets used for duty matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(onClick = onEditProfile, modifier = Modifier.padding(top = 10.dp)) {
                    Text(if (profile.isEmpty) "Build your profile" else "Edit profile")
                }
            }
        }

        item {
            SectionCard("Where to look") {
                var locations by remember(settings) {
                    mutableStateOf(settings.locations.joinToString(", "))
                }
                var minScore by remember(settings) { mutableIntStateOf(settings.minScore) }

                OutlinedTextField(
                    value = locations,
                    onValueChange = { locations = it },
                    label = { Text("Locations (comma separated)") },
                    placeholder = { Text("Albany, NY, Remote") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                Text(
                    "Job titles live in the Profile tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Minimum match score: $minScore / $MAX_SCORE",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = minScore.toFloat(),
                    onValueChange = { minScore = it.toInt() },
                    valueRange = 0f..MAX_SCORE.toFloat(),
                    steps = MAX_SCORE - 1,
                )
                Button(onClick = { viewModel.updateSettings(locations, minScore) }) {
                    Text("Save")
                }
            }
        }

        item {
            SectionCard("Daily hunt") {
                var enabled by remember { mutableStateOf(viewModel.dailyRunEnabled) }
                var hour by remember { mutableIntStateOf(viewModel.dailyRunHour) }
                var wifiOnly by remember { mutableStateOf(viewModel.wifiOnly) }

                SettingSwitch("Run automatically every day", enabled) {
                    enabled = it
                    viewModel.setDailyRunEnabled(it)
                }
                if (enabled) {
                    Text(
                        "Runs at %02d:00".format(hour),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(
                        value = hour.toFloat(),
                        onValueChange = { hour = it.toInt() },
                        onValueChangeFinished = { viewModel.setDailyRunHour(hour) },
                        valueRange = 0f..23f,
                        steps = 22,
                    )
                }
                SettingSwitch("Only on Wi-Fi", wifiOnly) {
                    wifiOnly = it
                    viewModel.setWifiOnly(it)
                }

                lastRun?.let { run ->
                    Text(
                        "Last run ${formatTimestamp(run.ranAt)} — ${run.fetched} fetched → " +
                            "${run.inRange} in range → ${run.relevant} relevant → " +
                            "${run.newCount} new.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (run.warnings.isNotBlank()) {
                        Text(
                            "Warnings: ${run.warnings.lines().first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } ?: EmptyState(
                    "No runs yet.",
                    Modifier.padding(top = 12.dp),
                )

                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = viewModel::runHuntNow, enabled = !isRunning) {
                        Text(if (isRunning) "Hunting…" else "Hunt now")
                    }
                    if (isRunning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    OutlinedButton(
                        onClick = { shareText(context, viewModel.workingListMarkdown()) },
                    ) { Text("Share list") }
                }
            }
        }

        item {
            SectionCard("Recent matches") {
                if (listings.isEmpty()) {
                    EmptyState("Nothing yet — run a hunt to fill this in.")
                } else {
                    listings.take(5).forEach { listing ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (listing.isNew) {
                                    NewBadge(Modifier.padding(end = 6.dp))
                                }
                                Text(
                                    listing.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                "${listing.company} · ${listing.location}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MatchMeter(listing.score, Modifier.padding(top = 2.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = onSeeAllMatches,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("See all ${listings.size}") }
                }
            }
        }
    }
}

@Composable
private fun LabeledChips(label: String, items: List<String>, emptyText: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    ChipFlow(items, emptyText)
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

internal fun shareText(context: android.content.Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Job Hunt — Working List")
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share working list"))
}
