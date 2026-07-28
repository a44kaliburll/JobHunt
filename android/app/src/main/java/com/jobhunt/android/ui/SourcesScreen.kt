package com.jobhunt.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val BUILT_IN_BOARDS =
    listOf("LinkedIn (guest search)", "RemoteOK", "WeWorkRemotely", "The Muse")

private val KINDS = listOf(
    "rss" to "RSS / Atom feed",
    "json" to "JSON API",
    "html" to "HTML page",
)

private val TEMPLATES = mapOf(
    "rss" to """{"url": "https://example.com/jobs.rss?q={query}"}""",
    "json" to """
        {
          "url": "https://example.com/api/jobs?q={query}&where={location}",
          "list_path": "results",
          "fields": {"id": "id", "title": "name", "company": "company.name",
                     "location": "location", "url": "links.apply", "posted": "published"}
        }
    """.trimIndent(),
    "html" to """
        {
          "url": "https://example.com/jobs?q={query}",
          "selectors": {"item": "li.job", "title": ".job-title", "company": ".employer",
                        "location": ".place", "url": "a", "posted": "time"}
        }
    """.trimIndent(),
)

@Composable
fun SourcesScreen(viewModel: JobHuntViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("rss") }
    var config by remember { mutableStateOf(TEMPLATES.getValue("rss")) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Text(
                "Job sources",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }

        item {
            SectionCard("Built-in boards") {
                ChipFlow(BUILT_IN_BOARDS, "")
                Text(
                    "Always searched, using your resume titles and locations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            SectionCard("Add your own site") {
                Text(
                    "Point JobHunt at any job board: an RSS feed, a JSON API, or an " +
                        "HTML page read with CSS selectors. In the URL, {query} is " +
                        "replaced with each search title and {location} with each location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Source name") },
                    placeholder = { Text("NY State Jobs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KINDS.forEach { (value, label) ->
                        FilterChip(
                            selected = kind == value,
                            onClick = {
                                kind = value
                                config = TEMPLATES.getValue(value)
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = { Text("Config (JSON)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        viewModel.addSource(name, kind, config)
                        name = ""
                        config = TEMPLATES.getValue(kind)
                    },
                    modifier = Modifier.padding(top = 10.dp),
                ) { Text("Add source") }
            }
        }

        if (sources.isEmpty()) {
            item {
                EmptyState("No custom sources yet.", Modifier.padding(horizontal = 20.dp))
            }
        }

        items(sources, key = { it.id }) { source ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        source.name + if (source.enabled) "" else "  (disabled)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        source.kind.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        source.configJson,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.toggleSource(source.id) }) {
                            Text(if (source.enabled) "Disable" else "Enable")
                        }
                        TextButton(onClick = { viewModel.deleteSource(source) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
