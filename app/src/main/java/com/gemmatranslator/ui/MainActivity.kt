package com.gemmatranslator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmatranslator.ui.theme.GemmaTranslatorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // ── Permission launcher ─────────────────────────────────────────────────
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult?.invoke(granted)
            onPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Begin model warm-up as early as possible
        viewModel.loadModel()

        setContent {
            GemmaTranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // ── Navigation state ────────────────────────────────────
                    var currentScreen by rememberSaveable { mutableStateOf(Screen.MAIN) }

                    // ── Observe ViewModel state ─────────────────────────────
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    // ── Settings state (local until a SettingsViewModel is wired) ──
                    var settingsState by remember { mutableStateOf(SettingsUiState()) }

                    // ── Snackbar host for permission denial feedback ─────────
                    val snackbarHostState = remember { SnackbarHostState() }
                    var permissionDeniedPending by remember { mutableStateOf(false) }

                    LaunchedEffect(permissionDeniedPending) {
                        if (permissionDeniedPending) {
                            snackbarHostState.showSnackbar(
                                "Microphone permission is required to record speech.",
                            )
                            permissionDeniedPending = false
                        }
                    }

                    // ── Animated screen transitions ─────────────────────────
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            if (targetState == Screen.SETTINGS || targetState == Screen.MODELS) {
                                (slideInHorizontally { it } + fadeIn()) togetherWith
                                        (slideOutHorizontally { -it } + fadeOut())
                            } else {
                                (slideInHorizontally { -it } + fadeIn()) togetherWith
                                        (slideOutHorizontally { it } + fadeOut())
                            }
                        },
                        label = "screenTransition",
                    ) { screen ->
                        when (screen) {
                            Screen.MAIN -> MainScreen(
                                uiState = uiState,
                                snackbarHostState = snackbarHostState,
                                onModeChange = { viewModel.toggleMode() },
                                onLeftLanguageChange = { viewModel.setLeftLanguage(it) },
                                onRightLanguageChange = { viewModel.setRightLanguage(it) },
                                onToggleListening = {
                                    requestMicPermissionIfNeeded(
                                        onGranted = { viewModel.toggleListening() },
                                        onDenied = { permissionDeniedPending = true },
                                    )
                                },
                                onNavigateSettings = { currentScreen = Screen.SETTINGS },
                                onNavigateModels = { currentScreen = Screen.MODELS },
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                state = settingsState,
                                onVoiceSpeedChange = { speed ->
                                    settingsState = settingsState.copy(voiceSpeedMultiplier = speed)
                                },
                                onAutoDetectToggle = { enabled ->
                                    settingsState = settingsState.copy(autoDetectLanguage = enabled)
                                },
                                onEchoTranslationToggle = { enabled ->
                                    settingsState = settingsState.copy(echoTranslation = enabled)
                                },
                                onNavigateBack = { currentScreen = Screen.MAIN },
                                onNavigateModels = { currentScreen = Screen.MODELS },
                            )

                            Screen.MODELS -> ModelDownloadScreen(
                                onNavigateBack = { currentScreen = Screen.MAIN },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Permission helper ───────────────────────────────────────────────────

    private fun requestMicPermissionIfNeeded(
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                onGranted()
            }
            else -> {
                onPermissionResult = { granted -> if (granted) onGranted() else onDenied() }
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Navigation enum
// ---------------------------------------------------------------------------

private enum class Screen { MAIN, SETTINGS, MODELS }
