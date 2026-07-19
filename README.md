# Expressive RGB

An Android app for controlling DuoCo Bluetooth LE RGB LED hardware — with ambiance-synced screen lighting, an audio-reactive visualizer, and AI-generated lighting scenes.

## Features

- **Ambiance Mode** — syncs LED color to on-screen content in real time via screen capture, with luminance-weighted zone averaging and smoothed, flicker-resistant color transitions.
- **Audio Visualizer** — beat detection (spectral flux onset, adaptive thresholding, tempo-adaptive cooldown) and frequency-band-driven color response, with multiple presets (Smooth Flow, Ambient Chill, Vocal Floor, and more).
- **Smart Scenes** — on-device AI scene generation using Gemini Nano (ML Kit GenAI Prompt API), turning a mood/tempo description into a custom animated lighting sequence.
- **Scene System** — full scene editing, scene chaining with configurable delay and loop behavior, and auto-cancel on manual input.
- **Multi-device support** — per-device state persistence, automation-aware save/restore, and manual reconnection control.

## Architecture

This project is in the middle of an incremental refactor away from a small number of large, tightly-coupled files toward a modular, unidirectional-data-flow (MVI-style) architecture. The target structure:

```
com.example
├── core/          # Pure logic: color math, protocol serialization, calibration, animation parsing
├── data/          # Repository implementations: database, preferences
├── domain/        # Repository interfaces, domain models
├── hardware/      # BLE and audio I/O (in progress)
└── presentation/  # Compose UI and ViewModels (in progress)
```

**Current status:** pure-logic extraction, dependency-injection cleanup, and connection-state management have been migrated to this structure. UI-state consolidation and hardware-layer isolation are still in progress. Expect some files to still mix responsibilities until the refactor completes.

Dependency injection is manual (no Hilt/Dagger) — see `AppContainer` and `RgbControllerApplication`.

## Hardware

Targets DuoCo BLE RGB LED strips. Note: Only tested on MELK Leds, but will likely work on ELK devices, and any other devices that use DuoCo StripX. Native hardware CCT support has not been implemented.

## Setup

1. Clone the repo and open in Android Studio.
2. Requires Bluetooth LE and audio-recording runtime permissions (requested at first launch).
3. Build and run on a physical device — BLE and audio capture require real hardware; the emulator won't exercise these paths meaningfully.

## Known Issues

- LEDs occasionally freezing during extended use — under active investigation, with diagnostic logging (exportable to file) added to help isolate the cause.
- Some DSP/animation timing constants are sample-count-based rather than time-based, causing slightly different effective behavior between the native `AudioRecord` (~43Hz) and `Visualizer` API (~20Hz) pipelines. Audited and scoped for a future fix.

## License

MIT — see [LICENSE](LICENSE).
