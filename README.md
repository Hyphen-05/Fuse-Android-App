# Expressive RGB

An Android app for controlling DuoCo Bluetooth LE RGB LED hardware — power, color, brightness, modes, and warmth, plus advanced features:

- **Ambiance mode** — syncs LED color to on-screen content in real time via screen capture, with smoothing, scene-cut detection, and gamma-corrected fades to avoid flicker
- **Audio visualizer** — reactive lighting driven by on-device audio capture, with beat detection (spectral flux onset analysis) and multiple presets (Smooth Flow, Ambient Chill, Vocal Floor, and more)
- **Smart Scenes** — on-device AI scene generation (Gemini Nano / ML Kit GenAI) that turns a mood/tempo description into an LED animation
- **Scene chaining** — sequence multiple scenes with configurable delay, looping, and auto-cancel on manual input
- **Multi-device support** — save, alias, and control multiple DuoCo devices independently

## Architecture

The app is mid-migration to an MVI (unidirectional data flow) architecture, chosen to handle concurrent async streams (BLE, audio capture, UI events) without the race conditions plain MVVM ran into. Package structure:

- `core/` — pure logic (color math, protocol serialization, calibration)
- `data/` — persistence (database, preferences)
- `domain/` — models and repository interfaces
- `hardware/` — BLE and audio I/O
- `presentation/` — Compose UI and ViewModels/reducers

## Hardware note

DuoCo devices flash briefly at the firmware level whenever the pixel count changes — this is a hardware constraint, not a bug, and animations are designed around it.

## License

MIT — see [LICENSE](LICENSE).
