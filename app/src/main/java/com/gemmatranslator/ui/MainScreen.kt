package com.gemmatranslator.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gemmatranslator.R
import com.gemmatranslator.model.Language
import com.gemmatranslator.model.TranslationEntry
import com.gemmatranslator.model.TranslationMode
import com.gemmatranslator.model.TranslatorUiState
import com.gemmatranslator.ui.components.LanguageSelector
import com.gemmatranslator.ui.components.ModeToggle
import com.gemmatranslator.ui.theme.GemmaTranslatorTheme
import com.gemmatranslator.ui.theme.ListeningRed
import com.gemmatranslator.ui.theme.ListeningRedSoft
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun MainScreen(
    uiState: TranslatorUiState,
    onModeChange: () -> Unit,
    onLeftLanguageChange: (Language) -> Unit,
    onRightLanguageChange: (Language) -> Unit,
    onToggleListening: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateModels: () -> Unit = {},
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            TopBar(
                onNavigateSettings = onNavigateSettings,
                onNavigateModels = onNavigateModels,
            )

            if (uiState.errorMessage != null) {
                ErrorBanner(message = uiState.errorMessage)
            }

            TranslationDisplayArea(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ModeToggle(
                mode = uiState.mode,
                onModeChange = { onModeChange() },
                modifier = Modifier.fillMaxWidth(),
            )

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

            ListenFab(
                isReady = uiState.isReady,
                isModelLoading = uiState.isModelLoading,
                isListening = uiState.isListening,
                onClick = onToggleListening,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 4.dp),
            )

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TopBar(
    onNavigateSettings: () -> Unit,
    onNavigateModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "GemmaFish",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onNavigateModels) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = "Voice Models",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun TranslationDisplayArea(
    uiState: TranslatorUiState,
    modifier: Modifier = Modifier,
) {
    var showToSpeakerEntry by remember { mutableStateOf<TranslationEntry?>(null) }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
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
                            onCardTap = { entry -> showToSpeakerEntry = entry },
                        )
                    }

                    else -> IdleBranding()
                }
            }
        }

        AnimatedVisibility(
            visible = showToSpeakerEntry != null,
            enter = fadeIn(tween(180)) + scaleIn(tween(200), initialScale = 0.96f),
            exit = fadeOut(tween(150)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
        ) {
            showToSpeakerEntry?.let { entry ->
                ShowToSpeakerOverlay(
                    entry = entry,
                    onDismiss = { showToSpeakerEntry = null },
                )
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
        Text(
            text = "Ready to translate",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap the fish to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
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
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Loading Gemma 4",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth(0.5f)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveTranslationContent(
    isListening: Boolean,
    pendingText: String?,
    latestEntry: TranslationEntry?,
    history: List<TranslationEntry>,
    onCardTap: (TranslationEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(pendingText, latestEntry) {
        listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = false,
    ) {
        if (isListening || pendingText != null) {
            item(key = "pending") {
                PendingTranscriptCard(
                    isListening = isListening,
                    text = pendingText,
                )
            }
        }

        if (latestEntry != null) {
            item(key = "latest_${latestEntry.id}") {
                TranslationEntryCard(
                    entry = latestEntry,
                    isLatest = true,
                    onTap = { onCardTap(latestEntry) },
                )
            }
        }

        val historyToShow = if (latestEntry != null)
            history.filter { it.id != latestEntry.id }
        else
            history

        items(historyToShow, key = { it.id }) { entry ->
            TranslationEntryCard(
                entry = entry,
                isLatest = false,
                onTap = { onCardTap(entry) },
            )
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

private fun relativeTime(timestamp: Instant): String {
    val now = Instant.now()
    val secondsAgo = ChronoUnit.SECONDS.between(timestamp, now).coerceAtLeast(0)
    return when {
        secondsAgo < 10   -> "just now"
        secondsAgo < 60   -> "${secondsAgo}s ago"
        secondsAgo < 3600 -> "${secondsAgo / 60}m ago"
        else              -> "${secondsAgo / 3600}h ago"
    }
}

@Composable
private fun TranslationEntryCard(
    entry: TranslationEntry,
    isLatest: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLatest) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isLatest) 2.dp else 0.dp,
        ),
        border = if (isLatest) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        ) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = entry.sourceLang.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = entry.targetLang.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = relativeTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = entry.originalText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = entry.translatedText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isLatest) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isLatest) {
                Text(
                    text = "Tap to show full screen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun ShowToSpeakerOverlay(
    entry: TranslationEntry,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2FFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.sourceLang.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = entry.targetLang.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Tap anywhere to dismiss",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

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

@Composable
private fun ListenFab(
    isReady: Boolean,
    isModelLoading: Boolean,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fabScale by animateFloatAsState(
        targetValue = if (isListening) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "fabScale",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(fabScale)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                )
                .clickable(
                    enabled = isReady || isListening,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isListening,
                transitionSpec = {
                    (scaleIn(tween(250)) + fadeIn(tween(250))) togetherWith
                            (scaleOut(tween(200)) + fadeOut(tween(200)))
                },
                label = "fishState",
            ) { listening ->
                androidx.compose.foundation.Image(
                    painter = painterResource(
                        if (listening) R.drawable.speaking else R.drawable.tap_to_speak,
                    ),
                    contentDescription = if (listening) "Tap to stop" else "Tap to speak",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    alpha = if (isReady || listening) 1f else 0.5f,
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenIdlePreview() {
    GemmaTranslatorTheme {
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
    GemmaTranslatorTheme {
        MainScreen(
            uiState = TranslatorUiState(
                isModelReady = true,
                isListening = true,
                pendingRecognizedText = "Hello, how are you today?",
                latestTranslation = TranslationEntry(
                    id = 1L,
                    originalText = "Hello, how are you today?",
                    translatedText = "Hola, como estas hoy?",
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
