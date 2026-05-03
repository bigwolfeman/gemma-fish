package com.gemmatranslator.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gemmatranslator.model.Language
import com.gemmatranslator.model.TranslationEntry
import com.gemmatranslator.model.TranslationMode
import com.gemmatranslator.model.TranslatorUiState
import com.gemmatranslator.ui.components.LanguageSelector
import com.gemmatranslator.ui.components.ModeToggle
import com.gemmatranslator.ui.theme.DisplayGradientEnd
import com.gemmatranslator.ui.theme.DisplayGradientStart
import com.gemmatranslator.ui.theme.GemmaTranslatorTheme
import com.gemmatranslator.ui.theme.ListeningRed

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

@Composable
fun MainScreen(
    uiState: TranslatorUiState,
    onModeChange: () -> Unit,
    onLeftLanguageChange: (Language) -> Unit,
    onRightLanguageChange: (Language) -> Unit,
    onToggleListening: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Top bar ─────────────────────────────────────────────────────
            TopBar(onNavigateSettings = onNavigateSettings)

            // ── Error banner ────────────────────────────────────────────────
            if (uiState.errorMessage != null) {
                ErrorBanner(message = uiState.errorMessage)
            }

            // ── Display area ────────────────────────────────────────────────
            TranslationDisplayArea(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // ── Mode toggle ─────────────────────────────────────────────────
            ModeToggle(
                mode = uiState.mode,
                onModeChange = { onModeChange() },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Language selectors ──────────────────────────────────────────
            val leftLabel = when (uiState.mode) {
                TranslationMode.EARBUD  -> "Left Earbud"
                TranslationMode.SPEAKER -> "Target 1"
            }
            val rightLabel = when (uiState.mode) {
                TranslationMode.EARBUD  -> "Right Earbud"
                TranslationMode.SPEAKER -> "Target 2"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LanguageSelector(
                    label = leftLabel,
                    selectedLanguage = uiState.leftLanguage,
                    onLanguageSelected = onLeftLanguageChange,
                    enabled = !uiState.isListening,
                    modifier = Modifier.weight(1f),
                )
                LanguageSelector(
                    label = rightLabel,
                    selectedLanguage = uiState.rightLanguage,
                    onLanguageSelected = onRightLanguageChange,
                    enabled = !uiState.isListening,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Listen FAB ──────────────────────────────────────────────────
            ListenFab(
                isReady = uiState.isReady,
                isModelLoading = uiState.isModelLoading,
                isListening = uiState.isListening,
                onClick = onToggleListening,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Gemma",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Translator",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNavigateSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Error banner
// ---------------------------------------------------------------------------

@Composable
private fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Translation display area
// ---------------------------------------------------------------------------

@Composable
private fun TranslationDisplayArea(
    uiState: TranslatorUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp),
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DisplayGradientStart, DisplayGradientEnd),
                    ),
                )
                .padding(20.dp),
        ) {
            when {
                uiState.isModelLoading -> {
                    ModelLoadingState(progress = uiState.modelLoadingProgress ?: 0f)
                }

                uiState.isListening
                        || uiState.pendingRecognizedText != null
                        || uiState.latestTranslation != null
                        || uiState.translationHistory.isNotEmpty() -> {
                    LiveTranslationContent(
                        isListening = uiState.isListening,
                        pendingText = uiState.pendingRecognizedText,
                        latestEntry = uiState.latestTranslation,
                        history = uiState.translationHistory,
                    )
                }

                else -> IdleBranding()
            }
        }
    }
}

@Composable
private fun IdleBranding(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "🌐", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Ready to translate",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap the mic to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModelLoadingState(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp,
            strokeCap = StrokeCap.Round,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Loading Gemma 4...",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun LiveTranslationContent(
    isListening: Boolean,
    pendingText: String?,
    latestEntry: TranslationEntry?,
    history: List<TranslationEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to top when new content arrives
    LaunchedEffect(pendingText, latestEntry) {
        listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        reverseLayout = false,
    ) {
        // Listening / pending item
        if (isListening || pendingText != null) {
            item(key = "pending") {
                PendingTranscriptCard(
                    isListening = isListening,
                    text = pendingText,
                )
            }
        }

        // Latest translation (highlighted)
        if (latestEntry != null) {
            item(key = "latest_${latestEntry.id}") {
                TranslationEntryCard(
                    entry = latestEntry,
                    isLatest = true,
                )
            }
        }

        // Historical entries (de-emphasized)
        val historyToShow = if (latestEntry != null)
            history.filter { it.id != latestEntry.id }
        else
            history

        items(historyToShow, key = { it.id }) { entry ->
            TranslationEntryCard(entry = entry, isLatest = false)
        }
    }
}

@Composable
private fun PendingTranscriptCard(
    isListening: Boolean,
    text: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isListening) {
                ListeningPulse()
            }
            Text(
                text = if (isListening) "Listening..." else "Heard",
                style = MaterialTheme.typography.labelMedium,
                color = if (isListening) ListeningRed
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text ?: "...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontStyle = if (text.isNullOrBlank()) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

@Composable
private fun TranslationEntryCard(
    entry: TranslationEntry,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = if (isLatest) 1f else 0.6f
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Original text
        Text(
            text = entry.originalText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            fontStyle = FontStyle.Italic,
        )
        // Translation
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isLatest) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
        }
        // Language pair label
        Text(
            text = "${entry.sourceLang.displayName} → ${entry.targetLang.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.7f),
        )
    }
}

// ---------------------------------------------------------------------------
// Listening pulse indicator
// ---------------------------------------------------------------------------

@Composable
private fun ListeningPulse(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(ListeningRed),
    )
}

// ---------------------------------------------------------------------------
// Listen FAB
// ---------------------------------------------------------------------------

@Composable
private fun ListenFab(
    isReady: Boolean,
    isModelLoading: Boolean,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fabScale by animateFloatAsState(
        targetValue = if (isListening) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "fabScale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FloatingActionButton(
            onClick = { if (isReady || isListening) onClick() },
            modifier = Modifier
                .size(72.dp)
                .scale(fabScale),
            containerColor = when {
                isModelLoading || !isReady && !isListening -> MaterialTheme.colorScheme.surfaceVariant
                isListening -> ListeningRed
                else -> MaterialTheme.colorScheme.primary
            },
            contentColor = when {
                isModelLoading || !isReady && !isListening -> MaterialTheme.colorScheme.onSurfaceVariant
                isListening -> Color.White
                else -> MaterialTheme.colorScheme.onPrimary
            },
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isListening) 12.dp else 6.dp,
            ),
        ) {
            AnimatedContent(
                targetState = isListening,
                transitionSpec = {
                    (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith
                            (scaleOut(tween(150)) + fadeOut(tween(150)))
                },
                label = "fabIcon",
            ) { listening ->
                Icon(
                    imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop listening" else "Start listening",
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Text(
            text = when {
                isModelLoading -> "Loading model..."
                !isReady -> "Initializing..."
                isListening -> "Tap to stop"
                else -> "Tap to speak"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenIdlePreview() {
    GemmaTranslatorTheme(darkTheme = true) {
        MainScreen(
            uiState = TranslatorUiState(isModelReady = true),
            onModeChange = {},
            onLeftLanguageChange = {},
            onRightLanguageChange = {},
            onToggleListening = {},
            onNavigateSettings = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenListeningPreview() {
    GemmaTranslatorTheme(darkTheme = true) {
        MainScreen(
            uiState = TranslatorUiState(
                isModelReady = true,
                isListening = true,
                pendingRecognizedText = "Hello, how are you today?",
                latestTranslation = TranslationEntry(
                    id = 1L,
                    originalText = "Hello, how are you today?",
                    translatedText = "Hola, ¿cómo estás hoy?",
                    sourceLang = Language.ENGLISH,
                    targetLang = Language.SPANISH,
                ),
            ),
            onModeChange = {},
            onLeftLanguageChange = {},
            onRightLanguageChange = {},
            onToggleListening = {},
            onNavigateSettings = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenLoadingPreview() {
    GemmaTranslatorTheme(darkTheme = true) {
        MainScreen(
            uiState = TranslatorUiState(
                isModelReady = false,
                modelLoadingProgress = 0.45f,
            ),
            onModeChange = {},
            onLeftLanguageChange = {},
            onRightLanguageChange = {},
            onToggleListening = {},
            onNavigateSettings = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenSpeakerModePreview() {
    GemmaTranslatorTheme(darkTheme = true) {
        MainScreen(
            uiState = TranslatorUiState(
                isModelReady = true,
                mode = TranslationMode.SPEAKER,
                leftLanguage = Language.FRENCH,
                rightLanguage = Language.GERMAN,
            ),
            onModeChange = {},
            onLeftLanguageChange = {},
            onRightLanguageChange = {},
            onToggleListening = {},
            onNavigateSettings = {},
        )
    }
}
