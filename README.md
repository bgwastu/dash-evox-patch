# dash-evox-patch

Fixes for EvolutionX on POCO X8 Pro Max (`dash`).

## Features

- Persistent Quick Settings date with calendar shortcut
- Correct expanded header insets
- No notification-dismiss vibration
- Brightness slider uses the gamma curve instead of the ROM's linear curve
- Smooth auto-brightness response (absorbs raw ALS jitter via widened debounce windows and slowed automatic ramp rate)
- HyperCharge, fast-charge, or normal charging status, live input power, and a charge-time estimate on the lock screen

## Requirements

- EvolutionX Android 16 or 17 on `dash`
- KernelSU with module scripts enabled
- LSPosed or Vector

## Install

1. Flash the KernelSU ZIP.
2. In LSPosed / Vector, enable `dash-evox-patch` for:
   - System UI (`com.android.systemui`)
   - System Framework (`android` / `system`)
3. Reboot.

## Build

```bash
./gradlew :app:assembleDebug packageKernelSuDebug
```

GitHub Actions builds the APK and KernelSU ZIP on every push. The newest main-branch build is published under the `latest` pre-release.

## License

MIT
