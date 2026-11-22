package com.arif.goku.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GitHubBuildReport(
    val repo: String,
    val workflowName: String,
    val status: String,         // completed, in_progress, queued
    val conclusion: String,     // success, failure, cancelled, or ""
    val commitMessage: String,
    val runNumber: Int,
    val htmlUrl: String,
    val artifactDownloadUrl: String?,
    val artifactName: String?,
    val hindiSummary: String,
    val checkedAtMs: Long = System.currentTimeMillis()
) {
    val isSuccess: Boolean get() = status == "completed" && conclusion == "success"
    val isFailed: Boolean get() = status == "completed" && conclusion != "success" && conclusion.isNotBlank()
    val isRunning: Boolean get() = status != "completed"
}

/**
 * Phase 4 Goku Dev Mode Manager.
 *
 * - Queries GitHub Actions REST API for saved repos (works on public repos with zero token,
 *   or private repos with an optional AES-256 encrypted GitHub Personal Access Token).
 * - Formats clear Hindi voice summaries (Pass / Fail / Running + commit message) and provides
 *   1-tap APK artifact / workflow run browser download.
 * - Executes Termux commands via Termux `com.termux.RUN_COMMAND` foreground intent or falls back
 *   to Local ADB (`127.0.0.1:5555`).
 */
class GokuDevManager(
    context: Context,
    private val securePrefs: SecurePrefs,
    private val adbManager: LocalAdbManager
) {
    private val appContext = context.applicationContext

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _latestBuild = MutableStateFlow<GitHubBuildReport?>(null)
    val latestBuild: StateFlow<GitHubBuildReport?> = _latestBuild.asStateFlow()

    private val _isCheckingBuild = MutableStateFlow(false)
    val isCheckingBuild: StateFlow<Boolean> = _isCheckingBuild.asStateFlow()

    /**
     * Checks the latest GitHub Actions workflow run for [repoOverride] or the active saved repo.
     */
    suspend fun checkLatestGitHubBuild(repoOverride: String? = null): Result<GitHubBuildReport> =
        withContext(Dispatchers.IO) {
            val repo = resolveTargetRepo(repoOverride)
            if (!isValidRepoSlug(repo)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid GitHub repo format '$repo'. 'owner/repo' format use karein.")
                )
            }

            _isCheckingBuild.value = true
            try {
                val token = securePrefs.getGithubToken()
                val runsUrl = "https://api.github.com/repos/$repo/actions/runs?per_page=1"
                val runsReq = Request.Builder()
                    .url(runsUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GokuAgent-Android/4.0")
                    .apply {
                        if (token.isNotBlank()) {
                            header("Authorization", "Bearer $token")
                        }
                    }
                    .get()
                    .build()

                val runsBody = httpClient.newCall(runsReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val hint = when (resp.code) {
                            404 -> "Repo '$repo' nahi mila ya private hai (Dev Mode mein GitHub Token add karein)."
                            401, 403 -> "GitHub API limit ya token invalid hai (HTTP ${resp.code})."
                            else -> "GitHub Actions API error (HTTP ${resp.code})."
                        }
                        throw IllegalStateException(hint)
                    }
                    raw
                }

                var report = parseLatestRunJson(repo, runsBody)
                    ?: throw IllegalStateException("Repo '$repo' mein abhi koi GitHub Actions workflow run nahi mila.")

                // Fetch artifact metadata if build succeeded so user gets direct download link
                if (report.isSuccess) {
                    val runId = extractRunId(runsBody)
                    if (runId != null) {
                        val artifactInfo = fetchFirstApkArtifact(repo, runId, token)
                        if (artifactInfo != null) {
                            report = report.copy(
                                artifactName = artifactInfo.first,
                                artifactDownloadUrl = artifactInfo.second
                            )
                        }
                    }
                }

                _latestBuild.value = report
                Result.success(report)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _isCheckingBuild.value = false
            }
        }

    /**
     * Opens the APK artifact / GitHub Actions workflow run page in the browser for 1-tap download.
     */
    fun openLatestBuildDownload(report: GitHubBuildReport? = _latestBuild.value): Boolean {
        val targetUrl = report?.htmlUrl ?: return false
        if (!targetUrl.startsWith("https://github.com/")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Dispatches a confirmed shell/dev command via Termux `com.termux.RUN_COMMAND` intent,
     * falling back cleanly to Local ADB (`127.0.0.1:5555`) if Termux is unavailable.
     */
    suspend fun executeTermuxCommand(rawCommand: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Termux command khaali hai."))
        }

        // 1. Try Termux RUN_COMMAND service intent if Termux is installed
        if (isTermuxInstalled()) {
            val launched = try {
                val intent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
                    setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                    putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                    putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", cmd))
                    putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                    putExtra(EXTRA_BACKGROUND, false)
                }
                appContext.startForegroundService(intent)
                true
            } catch (_: Exception) {
                try {
                    val intent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
                        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                        putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                        putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", cmd))
                        putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                        putExtra(EXTRA_BACKGROUND, false)
                    }
                    appContext.startService(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            if (launched) {
                return@withContext Result.success("Termux mein command bhej diya gaya hai: $cmd")
            }
        }

        // 2. Fallback to Local ADB (`127.0.0.1:5555`) if connected
        if (adbManager.isConnected) {
            val adbRes = adbManager.executeShellCommand(cmd)
            return@withContext adbRes.map { output ->
                val preview = output.ifBlank { "(no output)" }.take(220)
                "Termux nahi mila/blocked tha, isliye Local ADB par run kiya:\n$preview"
            }
        }

        Result.failure(
            IllegalStateException(
                "Termux RUN_COMMAND fail hua (Termux mein 'allow-external-apps=true' enable karein) aur Local ADB bhi disconnected hai."
            )
        )
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchFirstApkArtifact(repo: String, runId: Long, token: String): Pair<String, String>? {
        return try {
            val url = "https://api.github.com/repos/$repo/actions/runs/$runId/artifacts"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GokuAgent-Android/4.0")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val artifacts = json.optJSONArray("artifacts") ?: return null
                if (artifacts.length() == 0) return null
                val first = artifacts.getJSONObject(0)
                val name = first.optString("name", "app-debug.apk")
                val dl = first.optString("archive_download_url", "")
                if (dl.isNotBlank()) name to dl else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTargetRepo(overrideRaw: String?): String {
        val candidate = overrideRaw?.trim().orEmpty()
        if (candidate.isEmpty()) return securePrefs.getActiveGithubRepo()
        if (isValidRepoSlug(candidate)) return candidate

        // Fuzzy match against saved repo list (e.g. "order master" -> "autoacceptapp/Order-master")
        val saved = securePrefs.getGithubRepos()
        val normalized = candidate.lowercase().replace(Regex("[^a-z0-9]"), "")
        return saved.firstOrNull { repo ->
            repo.lowercase().replace(Regex("[^a-z0-9]"), "").contains(normalized)
        } ?: securePrefs.getActiveGithubRepo()
    }

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

        private val REPO_SLUG_REGEX = Regex("^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$")

        fun isValidRepoSlug(repo: String): Boolean = REPO_SLUG_REGEX.matches(repo.trim())

        fun extractRunId(rawJson: String): Long? {
            return try {
                val runs = JSONObject(rawJson).optJSONArray("workflow_runs") ?: return null
                if (runs.length() == 0) return null
                runs.getJSONObject(0).optLong("id").takeIf { it > 0L }
            } catch (_: Exception) {
                null
            }
        }

        fun parseLatestRunJson(repo: String, rawJson: String): GitHubBuildReport? {
            return try {
                val root = JSONObject(rawJson)
                val runs = root.optJSONArray("workflow_runs") ?: return null
                if (runs.length() == 0) return null
                val run = runs.getJSONObject(0)
                val workflowName = run.optString("name", "Android Build")
                val status = run.optString("status", "unknown").lowercase()
                val conclusion = run.optString("conclusion", "").lowercase()
                val runNumber = run.optInt("run_number", 0)
                val htmlUrl = run.optString("html_url", "https://github.com/$repo/actions")
                val commitMsg = run.optJSONObject("head_commit")
                    ?.optString("message")
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { run.optString("display_title", "Latest commit") }
                    .take(100)

                val repoShort = repo.substringAfter('/')
                val hindiStatus = when {
                    status != "completed" -> "$repoShort ka build #$runNumber abhi chal raha hai ($status). Commit: $commitMsg."
                    conclusion == "success" -> "Badhai ho! $repoShort ka build #$runNumber PASS ho gaya hai. Commit: $commitMsg. Dev Mode mein 1-tap APK download ready hai."
                    else -> "$repoShort ka build #$runNumber FAIL ho gaya ($conclusion). Commit: $commitMsg."
                }

                GitHubBuildReport(
                    repo = repo,
                    workflowName = workflowName,
                    status = status,
                    conclusion = conclusion,
                    commitMessage = commitMsg,
                    runNumber = runNumber,
                    htmlUrl = htmlUrl,
                    artifactDownloadUrl = null,
                    artifactName = null,
                    hindiSummary = hindiStatus
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
