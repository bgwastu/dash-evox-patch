# dash-evox-patch

Fixes for EvolutionX on POCO X8 Pro Max (`dash`).

## Features

- Persistent Quick Settings date with calendar shortcut
- Brightness slider uses the gamma curve instead of the ROM's linear curve
- Smooth auto-brightness response (absorbs raw ALS jitter via widened debounce windows and slowed automatic ramp rate)
- HyperCharge, fast-charge, or normal charging status, live input power, and a charge-time estimate on the lock screen
- MiSound speaker tuning. The ROM turns it off and ships the DSP config without the stock MiSound library; the module puts that library and config back
- Speaker volume uses 30 steps, and the middle of the slider is no longer near −23 dB
- Circle to Search from a navigation-pill hold. The ROM omits the feature the launcher checks before starting it
- Last rotation choice stays across reboot. Auto-rotate and the locked angle are restored; the module does not force a lock
- No haptic buzz while swiping a notification or dragging the volume slider; the brightness slider keeps its feedback
- The lock screen "swipe up" hint sits below the fingerprint icon instead of under it. The dash overlay lifts the hint into the sensor circle; this keeps a 12dp clearance wherever the Android version places either view
- Privacy indicator exemption for background automation apps (MacroDroid exempt by default, customizable via `settings put secure dash_evox_privacy_exempt_packages`)

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
