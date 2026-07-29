package com.jobhunt.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobhunt.core.ItemOrigin
import com.jobhunt.core.ProfileField
import com.jobhunt.core.ProfileItem

private val PLACEHOLDERS = mapOf(
    ProfileField.TITLES to "Director of Online Learning",
    ProfileField.SKILLS to "Curriculum Design",
    ProfileField.CERTIFICATIONS to "PMP",
    ProfileField.DUTIES to "Ran the accessibility remediation programme",
)

private val EXPLANATIONS = mapOf(
    ProfileField.TITLES to
        "What the searches actually look for. Add the roles you want next, " +
        "not only the ones you have held.",
    ProfileField.SKILLS to
        "Worth up to 15 points of a listing's match score when they appear in it.",
    ProfileField.CERTIFICATIONS to
        "Worth 5 points when a listing mentions any of them.",
    ProfileField.DUTIES to
        "Bullet points from your experience, compared against job descriptions " +
        "for up to 10 points.",
)

@Composable
fun ProfileScreen(viewModel: JobHuntViewModel) {
    val itemsByField by viewModel.profileItems.collectAsStateWithLifecycle()
    val resumes by viewModel.resumes.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                "Profile",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            Text(
                if (resumes.isEmpty()) {
                    "Nothing here yet. Type your own entries below — a resume is " +
                        "optional, it is just a fast way to fill this in."
                } else {
                    "Everything the hunt uses. Entries from your resumes are " +
                        "listed first; add your own, and remove anything that is " +
                        "not relevant to the jobs you want."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }

        ProfileField.entries.forEach { field ->
            item(key = field.key) {
                ProfileSection(
                    field = field,
                    items = itemsByField[field].orEmpty(),
                    onAdd = { viewModel.addProfileItem(field, it) },
                    onHide = { viewModel.hideProfileItem(field, it) },
                    onRestore = { viewModel.restoreProfileItem(field, it) },
                )
            }
        }
    }
}

@Composable
private fun ProfileSection(
    field: ProfileField,
    items: List<ProfileItem>,
    onAdd: (String) -> Unit,
    onHide: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }

    val visible = items.filterNot { it.hidden }
    val hidden = items.filter { it.hidden }

    SectionCard("${field.label} (${visible.size})") {
        EXPLANATIONS[field]?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add ${field.label.lowercase().trimEnd('s')}") },
                placeholder = { PLACEHOLDERS[field]?.let { Text(it) } },
                singleLine = field != ProfileField.DUTIES,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add to ${field.label}")
            }
        }

        if (visible.isEmpty()) {
            EmptyState("Nothing here yet.", Modifier.padding(top = 4.dp))
        } else if (field == ProfileField.DUTIES) {
            // Full sentences, so one per row rather than chips.
            visible.forEach { item ->
                EntryRow(item = item, actionIcon = Icons.Filled.Close, onAction = { onHide(item.value) })
            }
        } else {
            EntryChips(items = visible, onAction = onHide, removing = true)
        }

        if (hidden.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { showHidden = !showHidden }) {
                Text(
                    if (showHidden) "Hide removed (${hidden.size})"
                    else "Show removed (${hidden.size})",
                )
            }
            if (showHidden) {
                Text(
                    "These came from a resume and are excluded from the hunt. " +
                        "Restoring one puts it back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (field == ProfileField.DUTIES) {
                    hidden.forEach { item ->
                        EntryRow(
                            item = item,
                            actionIcon = Icons.Filled.Undo,
                            onAction = { onRestore(item.value) },
                        )
                    }
                } else {
                    EntryChips(items = hidden, onAction = onRestore, removing = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryChips(
    items: List<ProfileItem>,
    onAction: (String) -> Unit,
    removing: Boolean,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            InputChip(
                selected = false,
                onClick = { onAction(item.value) },
                label = {
                    Text(
                        item.value,
                        style = MaterialTheme.typography.labelMedium,
                        textDecoration = if (item.hidden) TextDecoration.LineThrough else null,
                    )
                },
                trailingIcon = {
                    Icon(
                        if (removing) Icons.Filled.Close else Icons.Filled.Undo,
                        contentDescription = if (removing) {
                            "Remove ${item.value}"
                        } else {
                            "Restore ${item.value}"
                        },
                        modifier = Modifier.size(16.dp),
                    )
                },
                avatar = if (item.origin == ItemOrigin.MANUAL) {
                    { Text("✎", style = MaterialTheme.typography.labelSmall) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun EntryRow(
    item: ProfileItem,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.value,
                style = MaterialTheme.typography.bodySmall,
                textDecoration = if (item.hidden) TextDecoration.LineThrough else null,
            )
            if (item.origin == ItemOrigin.MANUAL) {
                Text(
                    "added by you",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onAction) {
            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
