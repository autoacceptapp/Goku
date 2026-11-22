package com.arif.goku.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryEntity
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.DeepHudSurface
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

/**
 * Phase 4 Personal Memory Vault Settings Section (Room DB).
 * Allows viewing, filtering, adding, editing, and deleting Goku's saved memories.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryVaultSettingsSection(
    memories: List<MemoryEntity>,
    onAddMemory: (MemoryCategory, String, String) -> Unit,
    onUpdateMemory: (MemoryEntity) -> Unit,
    onDeleteMemory: (Long) -> Unit
) {
    var selectedFilter by remember { mutableStateOf<MemoryCategory?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }

    val filtered = remember(memories, selectedFilter) {
        if (selectedFilter == null) memories
        else memories.filter { it.categoryEnum() == selectedFilter }
    }

    // Header + Voice Tips + Add Button
    MemoryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PsychologyAlt, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Personal Memory Vault", style = MaterialTheme.typography.titleSmall)
            }
            MemoryPill(label = "${memories.size} SAVED", color = EmeraldOnline)
        }

        Text(
            text = "Say \"Yaad rakhna Papa ka number +91...\" or \"Bhool jaao...\" anytime. Goku automatically injects relevant memories into both Offline & Online answers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                editingMemory = null
                showEditorDialog = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("memory_add_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add New Memory")
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("All (${memories.size})", style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ArcCyan.copy(alpha = 0.2f),
                    selectedLabelColor = ArcCyan
                )
            )
            MemoryCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedFilter == cat,
                    onClick = { selectedFilter = if (selectedFilter == cat) null else cat },
                    label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GokuAmber.copy(alpha = 0.2f),
                        selectedLabelColor = GokuAmber
                    )
                )
            }
        }
    }

    // Saved Memory List
    if (filtered.isEmpty()) {
        MemoryCard {
            Text(
                text = if (memories.isEmpty()) {
                    "Abhi koi memory saved nahi hai. Upar 'Add New Memory' dabayein ya bolo 'Yaad rakhna...'."
                } else {
                    "Is category mein koi saved memory nahi mili."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    } else {
        filtered.forEach { mem ->
            val catColor = when (mem.categoryEnum()) {
                MemoryCategory.PERSON -> EmeraldOnline
                MemoryCategory.PREFERENCE -> ArcCyan
                MemoryCategory.ROUTINE -> GokuAmber
                MemoryCategory.FACT -> MaterialTheme.colorScheme.secondary
            }
            Surface(
                color = ObsidianVoid,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HudOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MemoryPill(label = mem.categoryEnum().label.uppercase(), color = catColor)
                            Text(
                                text = mem.aliasKey,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = mem.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    Row {
                        IconButton(
                            onClick = {
                                editingMemory = mem
                                showEditorDialog = true
                            },
                            modifier = Modifier.testTag("memory_edit_${mem.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit memory ${mem.aliasKey}",
                                tint = ArcCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteMemory(mem.id) },
                            modifier = Modifier.testTag("memory_delete_${mem.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete memory ${mem.aliasKey}",
                                tint = CrimsonEmergency,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        MemoryEditorDialog(
            existing = editingMemory,
            onDismiss = { showEditorDialog = false },
            onSave = { cat, alias, content ->
                val target = editingMemory
                if (target != null) {
                    onUpdateMemory(
                        target.copy(
                            category = cat.name,
                            aliasKey = alias.trim(),
                            content = content.trim()
                        )
                    )
                } else {
                    onAddMemory(cat, alias.trim(), content.trim())
                }
                showEditorDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditorDialog(
    existing: MemoryEntity?,
    onDismiss: () -> Unit,
    onSave: (MemoryCategory, String, String) -> Unit
) {
    var category by remember(existing) { mutableStateOf(existing?.categoryEnum() ?: MemoryCategory.PERSON) }
    var alias by remember(existing) { mutableStateOf(existing?.aliasKey.orEmpty()) }
    var content by remember(existing) { mutableStateOf(existing?.content.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepHudSurface,
        title = {
            Text(
                text = if (existing == null) "Add Personal Memory" else "Edit Memory",
                style = MaterialTheme.typography.titleMedium,
                color = ArcCyan
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.take(60) },
                    label = { Text("Alias / Key (e.g., Papa, Wi-Fi, Routine)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_alias_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(300) },
                    label = { Text("Details (e.g., Husenbhai - +919876543210)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_content_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(category, alias, content) },
                enabled = alias.isNotBlank() && content.isNotBlank(),
                modifier = Modifier.testTag("memory_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MemoryCard(content: @Composable () -> Unit) {
    Surface(
        color = ElevatedHudCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HudOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun MemoryPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
