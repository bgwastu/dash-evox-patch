# dash-evox-patch

Fixes for EvolutionX on POCO X8 Pro Max (`dash`).

## Features

- Persistent Quick Settings date with calendar shortcut
- Correct expanded header insets
- No notification-dismiss vibration
- Persistent rotation-lock state
- Correct brightness scaling
- 30-step media volume with a louder, audio-specific speaker curve
- Stronger speaker output through MediaTek BesLoudness
- HyperCharge status, live input power, and charge-time estimate on the lock screen
- Android 16 OEM unlock property cleanup

## Requirements

- EvolutionX Android 16 on `dash`
- KernelSU with module mounting enabled
- LSPosed or Vector

## Install

1. Flash the KernelSU ZIP.
2. Enable `dash-evox-patch` for System UI only.
3. Reboot.

## Build

```bash
./gradlew :app:assembleDebug packageKernelSuDebug
```

GitHub Actions builds the APK and KernelSU ZIP on every push. The newest main-branch build is published under the `latest` pre-release.

## License

MIT
