package com.gemmatranslator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gemmatranslator.model.Language
import com.gemmatranslator.model.ModelDownloadState
import com.gemmatranslator.model.ModelManager
import com.gemmatranslator.ui.theme.GemmaTranslatorTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Model download screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelDownloadScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    // ── State ────────────────────────────────────────────────────────────────

    var searchQuery by remember { mutableStateOf("") }

    // Per-language download state (null = not downloading)
    val downloadStates = remember { mutableStateMapOf<String, ModelDownloadState>() }
    // Active coroutine jobs so we can cancel
    val downloadJobs = remember { mutableStateMapOf<String, Job>() }

    // Refresh tick — forces recomposition after a download completes
    var refreshTick by remember { mutableStateOf(0) }

    // Delete confirmation dialog
    var pendingDeleteLanguage by remember { mutableStateOf<Language?>(null) }

    // Force re-check of downloaded state when refreshTick changes
    LaunchedEffect(refreshTick) { /* trigger recompose */ }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun isDownloaded(lang: Language) = manager.isModelDownloaded(lang)

    fun startDownload(lang: Language) {
        val key = lang.bcp47
        if (downloadJobs[key]?.isActive == true) return
        val job = scope.launch {
            manager.downloadModel(lang)
                .catch { e ->
                    if (e !is kotlinx.coroutines.CancellationException) {
                        downloadStates[key] = ModelDownloadState(
                            languageCode = key,
                            progress = 0f,
                            totalBytes = 0L,
                            downloadedBytes = 0L,
                            isComplete = false,
                            error = e.message,
                        )
                    }
                }
                .collect { state ->
                    downloadStates[key] = state
                    if (state.isComplete) {
                        downloadJobs.remove(key)
                        refreshTick++
                    }
                }
        }
        downloadJobs[key] = job
    }

    fun cancelDownload(lang: Language) {
        downloadJobs.remove(lang.bcp47)?.cancel()
        downloadStates.remove(lang.bcp47)
        manager.cancelDownload(lang)
    }

    fun deleteModel(lang: Language) {
        manager.deleteModel(lang)
        downloadStates.remove(lang.bcp47)
        refreshTick++
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    val allLanguages = Language.entries
    val filtered = remember(searchQuery) {
        if (searchQuery.isBlank()) allLanguages
        else allLanguages.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.bcp47.contains(searchQuery, ignoreCase = true)
        }
    }

    val downloadedLanguages = filtered.filter { isDownloaded(it) || downloadStates[it.bcp47]?.isComplete == true }
    val availableLanguages = filtered.filter {
        !isDownloaded(it) && downloadStates[it.bcp47]?.isComplete != true && manager.isDownloadable(it)
    }

    // ── Storage summary ───────────────────────────────────────────────────────

    val totalUsedBytes = remember(refreshTick) { manager.getTotalStorageUsedBytes() }

    // ── Delete dialog ─────────────────────────────────────────────────────────

    pendingDeleteLanguage?.let { lang ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLanguage = null },
            title = { Text("Delete ${lang.displayName} voice?") },
            text = { Text("This will remove the downloaded TTS model (~109 MB). You can re-download it later.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteModel(lang)
                    pendingDeleteLanguage = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLanguage = null }) { Text("Cancel") }
            },
        )
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Voice Models",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── Storage summary card ─────────────────────────────────────────
            item(key = "storage_header") {
                Spacer(Modifier.height(8.dp))
                StorageSummaryCard(
                    downloadedCount = downloadedLanguages.size + downloadStates.values.count { it.isComplete },
                    usedBytes = totalUsedBytes,
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── Search bar ───────────────────────────────────────────────────
            item(key = "search_bar") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Search languages…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Downloaded section ───────────────────────────────────────────
            if (downloadedLanguages.isNotEmpty()) {
                item(key = "downloaded_header") {
                    SectionHeader(
                        title = "Downloaded",
                        count = downloadedLanguages.size,
                        accentColor = MaterialTheme.colorScheme.primary,
                    )
                }

                items(
                    items = downloadedLanguages,
                    key = { "downloaded_${it.bcp47}" },
                ) { lang ->
                    LanguageRow(
                        language = lang,
                        downloadState = downloadStates[lang.bcp47],
                        isDownloaded = true,
                        onTap = { /* already downloaded — no-op */ },
                        onLongPress = { pendingDeleteLanguage = lang },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 56.dp),
                    )
                }

                item(key = "downloaded_spacer") { Spacer(Modifier.height(16.dp)) }
            }

            // ── Available section ────────────────────────────────────────────
            if (availableLanguages.isNotEmpty()) {
                item(key = "available_header") {
                    SectionHeader(
                        title = "Available to Download",
                        count = availableLanguages.size,
                        accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(
                    items = availableLanguages,
                    key = { "available_${it.bcp47}" },
                ) { lang ->
                    val state = downloadStates[lang.bcp47]
                    val isDownloading = downloadJobs[lang.bcp47]?.isActive == true
                    LanguageRow(
                        language = lang,
                        downloadState = state,
                        isDownloaded = false,
                        isDownloading = isDownloading,
                        onTap = {
                            if (isDownloading) cancelDownload(lang)
                            else startDownload(lang)
                        },
                        onLongPress = { /* not downloaded, nothing to delete */ },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 56.dp),
                    )
                }
            }

            // ── Empty state ──────────────────────────────────────────────────
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No languages match \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Storage summary card
// ---------------------------------------------------------------------------

@Composable
private fun StorageSummaryCard(
    downloadedCount: Int,
    usedBytes: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$downloadedCount voice model${if (downloadedCount != 1) "s" else ""} downloaded",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Storage used: ${formatBytes(usedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section header
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
        )
        Surface(
            shape = CircleShape,
            color = accentColor.copy(alpha = 0.12f),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Language row
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LanguageRow(
    language: Language,
    downloadState: ModelDownloadState?,
    isDownloaded: Boolean,
    isDownloading: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasError = downloadState?.error != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = if (isDownloaded) onLongPress else null,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Status icon (40 dp slot) ─────────────────────────────────────────
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isDownloaded -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )

                isDownloading -> {
                    val progress = downloadState?.progress ?: 0f
                    if (progress > 0f) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                else -> Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // ── Language name + progress/error detail ────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isDownloaded) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when {
                hasError -> Text(
                    text = "Failed — tap to retry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )

                isDownloading -> {
                    val state = downloadState
                    if (state != null && state.totalBytes > 0) {
                        Text(
                            text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}  •  ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progress,
                            animationSpec = tween(200),
                            label = "downloadProgress_${language.bcp47}",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    } else {
                        Text(
                            text = "Connecting…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                !isDownloaded -> Text(
                    text = "~109 MB  •  Tap to download",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // ── Delete button (downloaded only) ──────────────────────────────────
        AnimatedVisibility(
            visible = isDownloaded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            IconButton(
                onClick = onLongPress,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${language.displayName} model",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ── Cancel button (downloading only) ─────────────────────────────────
        AnimatedVisibility(
            visible = isDownloading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Spacer(Modifier.width(40.dp)) // reserve space; tap row to cancel
        }
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ModelDownloadScreenPreview() {
    GemmaTranslatorTheme(darkTheme = true) {
        ModelDownloadScreen(onNavigateBack = {})
    }
}
