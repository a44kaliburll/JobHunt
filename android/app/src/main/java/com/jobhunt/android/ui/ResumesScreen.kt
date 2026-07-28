package com.jobhunt.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobhunt.android.data.ResumeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RESUME_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
)

@Composable
fun ResumesScreen(viewModel: JobHuntViewModel) {
    val resumes by viewModel.resumes.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ResumeEntity?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addResumes(uris) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Text(
                "Resumes",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }
        item {
            SectionCard("Add resumes") {
                Text(
                    "PDF, DOCX, Markdown or plain text. Add as many as you like — " +
                        "skills, duties, certifications and job titles are pulled from " +
                        "each one and merged into a single search profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { picker.launch(RESUME_MIME_TYPES) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Choose files") }
            }
        }
        if (resumes.isEmpty()) {
            item { EmptyState("No resumes yet.", Modifier.padding(horizontal = 20.dp)) }
        }
        items(resumes, key = { it.id }) { resume ->
            val parsed = remember(resume.id, resume.parsedJson) { viewModel.parsedOf(resume) }
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(resume.filename, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Added ${formatDate(resume.uploadedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Label("Titles")
                    ChipFlow(parsed.titles, "none detected", maxItems = 10)
                    Label("Skills")
                    ChipFlow(parsed.skills, "none detected", maxItems = 20)
                    Label("Certifications")
                    ChipFlow(parsed.certifications, "none detected", maxItems = 10)
                    Text(
                        "${parsed.duties.size} duty bullets extracted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(
                        onClick = { pendingDelete = resume },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Remove") }
                }
            }
        }
    }

    pendingDelete?.let { resume ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove resume?") },
            text = {
                Text(
                    "${resume.filename} will be removed and your search profile " +
                        "rebuilt from the resumes that remain.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteResume(resume.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
