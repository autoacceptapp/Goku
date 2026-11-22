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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.dev.GitHubBuildReport
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

/**
 * Phase 4 Goku Dev Mode Settings Section:
 * - Manage GitHub repositories (e.g., autoacceptapp/goku_agent, autoacceptapp/Order-master)
 * - Optional encrypted GitHub Personal Access Token (for private repos or higher rate limits)
 * - Check latest GitHub Actions workflow build + 1-tap APK Download
 * - Termux / Local ADB quick command runner (SafetyGuard-gated)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevModeSettingsSection(
    userSettings: GokuUserSettings,
    latestBuild: GitHubBuildReport?,
    isCheckingBuild: Boolean,
    onSelectRepo: (String) -> Unit,
    onAddRepo: (String) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onSaveGithubToken: (String) -> Unit,
    onClearGithubToken: () -> Unit,
    onCheckLatestBuild: () -> Unit,
    onOpenBuildDownload: () -> Unit,
    onRunTermuxCommand: (String) -> Unit
) {
    var newRepoInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var termuxCmdInput by remember { mutableStateOf("git status") }

    // 1. GitHub Repositories & Actions Build Checker
    DevCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Code, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("GitHub Actions Monitor", style = MaterialTheme.typography.titleSmall)
            }
            DevPill(label = "DEV MODE", color = ArcCyan)
        }

        Text(
            text = "Say \"Goku, GitHub build check karo\" anytime to inspect the latest workflow run and download the APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            userSettings.githubRepos.forEach { repo ->
                val selected = repo == userSettings.activeGithubRepo
                FilterChip(
                    selected = selected,
                    onClick = { onSelectRepo(repo) },
                    label = { Text(repo, style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = if (userSettings.githubRepos.size > 1) {
                        {
                            IconButton(
                                onClick = { onRemoveRepo(repo) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove $repo",
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ArcCyan.copy(alpha = 0.2f),
                        selectedLabelColor = ArcCyan
                    )
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newRepoInput,
                onValueChange = { newRepoInput = it.take(90) },
                label = { Text("Add repo (owner/repo)") },
                placeholder = { Text("autoacceptapp/goku_agent") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dev_add_repo_input")
            )
            OutlinedButton(
                onClick = {
                    onAddRepo(newRepoInput)
                    newRepoInput = ""
                },
                enabled = newRepoInput.contains("/"),
                modifier = Modifier.testTag("dev_add_repo_button")
            ) {
                Text("Add")
            }
        }

        Button(
            onClick = onCheckLatestBuild,
            enabled = !isCheckingBuild,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dev_check_build_button")
        ) {
            if (isCheckingBuild) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ObsidianVoid
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Checking ${userSettings.activeGithubRepo}…")
            } else {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Latest Build (${userSettings.activeGithubRepo.substringAfter('/')})")
            }
        }

        if (latestBuild != null) {
            val statusColor = when {
                latestBuild.isSuccess -> EmeraldOnline
                latestBuild.isFailed -> CrimsonEmergency
                else -> GokuAmber
            }
            val statusText = when {
                latestBuild.isSuccess -> "PASS #${latestBuild.runNumber}"
                latestBuild.isFailed -> "FAIL #${latestBuild.runNumber}"
                else -> "RUNNING #${latestBuild.runNumber}"
            }

            Surface(
                color = ObsidianVoid,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = latestBuild.workflowName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DevPill(label = statusText, color = statusColor)
                    }
                    Text(
                        text = "Repo: ${latestBuild.repo}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan
                    )
                    Text(
                        text = "Commit: ${latestBuild.commitMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (latestBuild.isSuccess) {
                        Button(
                            onClick = onOpenBuildDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldOnline,
                                contentColor = ObsidianVoid
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dev_download_apk_button")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("1-Tap Download APK (${latestBuild.artifactName ?: "Actions Run"})")
                        }
                    }
                }
            }
        }
    }

    // 2. Optional Encrypted GitHub PAT Token
    DevCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("GitHub Token (Optional, AES-256)", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = if (userSettings.hasGithubToken) {
                "Saved securely: ${userSettings.maskedGithubToken}"
            } else {
                "Public repos work without a token. Add a PAT only for private repos."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (userSettings.hasGithubToken) EmeraldOnline else TextSecondary
        )

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("ghp_... or github_pat_...") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showToken) "Hide token" else "Show token"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dev_github_token_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveGithubToken(tokenInput)
                    tokenInput = ""
                },
                enabled = tokenInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("dev_save_token_button")
            ) {
                Text("Save Token")
            }
            if (userSettings.hasGithubToken) {
                OutlinedButton(
                    onClick = onClearGithubToken,
                    modifier = Modifier.testTag("dev_clear_token_button")
                ) {
                    Text("Clear", color = CrimsonEmergency)
                }
            }
        }
    }

    // 3. Termux Command Runner (SafetyGuard-gated)
    DevCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = EmeraldOnline)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Termux / ADB Command Runner", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = "Voice: \"Goku, Termux mein git pull chalao\". Uses Termux RUN_COMMAND (or Local ADB fallback) behind SafetyGuard confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = HudOutline)
        OutlinedTextField(
            value = termuxCmdInput,
            onValueChange = { termuxCmdInput = it.take(180) },
            label = { Text("Shell command") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dev_termux_cmd_input")
        )
        Button(
            onClick = { onRunTermuxCommand(termuxCmdInput) },
            enabled = termuxCmdInput.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dev_termux_run_button")
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Run via SafetyGuard")
        }
    }
}

@Composable
private fun DevCard(content: @Composable () -> Unit) {
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
private fun DevPill(label: String, color: Color) {
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
