# Gemma Translator

Android real-time translation app using Gemma 4 E2B on-device via LiteRT-LM.
Target device: Pixel 10a. Hackathon deadline: May 18, 2026.

## Architecture

```
com.gemmatranslator/
├── model/          # Data classes, enums (Language, TranslationMode, TranslatorUiState, TranslationEntry)
├── ui/             # Compose UI (MainActivity, MainScreen, SettingsScreen, components/, theme/)
├── audio/          # AudioPipeline, SpeechRecognitionManager, TextToSpeechManager, AudioRouter
├── translation/    # TranslationEngine (LiteRT-LM), PromptTemplates, LanguageDetector
└── TranslatorApp.kt
```

## Key Design Decisions
- **All types canonical in `model/`** — no duplicate enums across packages
- **LiteRT-LM calls isolated** in `TranslationEngine.runInference()` and `buildLlmInference()` — if SDK API changes, update those 2 methods only
- **Stereo panning** via TTS `KEY_PARAM_PAN` per-utterance (no AudioTrack wrapping)
- **Two modes**: EARBUD (L/R stereo pan) and SPEAKER (both channels, sequential)
- **Language detection**: two-tier — Unicode heuristics first (μs), Gemma model fallback for Latin scripts
- **Temperature=0, topK=1** for deterministic translation output

## Build
- Kotlin 2.1.0, Compose BOM 2024.12, Material 3, AGP 8.7.3
- minSdk 28, targetSdk 35
- Model file expected at `context.filesDir/gemma4_e2b.task`

## Not Yet Done
- Model file download/bundling strategy
- End-to-end on-device testing
- Gradle wrapper script (gradlew)
- App icon / branding assets
