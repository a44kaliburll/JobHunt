package com.jobhunt.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobhunt.android.data.ListingEntity

private val STATUS_OPTIONS = listOf("", "saved", "applied", "rejected", "hidden")

@Composable
fun ListingsScreen(viewModel: JobHuntViewModel) {
    val listings by viewModel.listings.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.All) }

    val visible = remember(listings, filter) {
        when (filter) {
            Filter.All -> listings
            Filter.New -> listings.filter { it.isNew }
            Filter.Saved -> listings.filter { it.status == "saved" }
            Filter.Applied -> listings.filter { it.status == "applied" }
        }
    }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Text(
                "Matches",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                EmptyState(
                    when (filter) {
                        Filter.All -> "No matches yet. Run a hunt from the Home tab."
                        else -> "Nothing here yet."
                    },
                    Modifier.padding(20.dp),
                )
            }
        }
        items(visible, key = { it.id }) { listing ->
            ListingCard(
                listing = listing,
                onStatusChange = { viewModel.setListingStatus(listing.id, it) },
            )
        }
    }
}

@Composable
private fun ListingCard(listing: ListingEntity, onStatusChange: (String) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (listing.isNew) NewBadge(Modifier.padding(end = 6.dp))
                Text(listing.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                listing.company.ifBlank { "Unknown company" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    listing.location.ifBlank { null },
                    listing.source.ifBlank { null },
                    listing.posted.ifBlank { null }?.let { "posted $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MatchMeter(listing.score, Modifier.padding(top = 8.dp))

            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (listing.url.isNotBlank()) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, listing.url.toUri()),
                            )
                        }
                    },
                    enabled = listing.url.isNotBlank(),
                ) { Text("Open") }

                TextButton(onClick = { menuOpen = true }) {
                    Text(listing.status.ifBlank { "Set status" })
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    STATUS_OPTIONS.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.ifBlank { "none" }) },
                            onClick = {
                                onStatusChange(status)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class Filter(val label: String) {
    All("All"),
    New("New"),
    Saved("Saved"),
    Applied("Applied"),
}
