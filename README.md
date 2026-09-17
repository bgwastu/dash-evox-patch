# dash-evox-patch

Device-specific fixes for EvolutionX on the POCO X8 Pro Max (`dash`).

## Features

### SystemUI hooks

- Keeps the date visible in collapsed Quick Settings after reboot.
- Opens the default calendar when the Quick Settings date is tapped.
- Aligns the expanded Quick Settings header with the status bar insets.
- Disables notification-dismiss swipe vibration.

### Boot property fixes

- Restores perceptual brightness slider mapping.
- Sets media volume to 30 steps.
- Removes the obsolete Android 16 `sys.oem_unlock_allowed` property.
- Preserves the user's auto-rotate and locked-orientation state across reboots.

## Requirements

- POCO X8 Pro Max (`dash`)
- EvolutionX based on Android 16
- KernelSU for the complete module ZIP
- LSPosed or a compatible Xposed framework for the SystemUI hooks

The hooks target the SystemUI implementation shipped with this ROM and may need updates after a ROM upgrade.

## Installation

### Complete patch

1. Install `dash-evox-patch-kernelsu-*.zip` in KernelSU Manager.
2. Enable `dash-evox-patch` in LSPosed.
3. Scope it to **System UI** (`com.android.systemui`) only.
4. Reboot.

The installer disables the superseded `slider_scaling_fix` and `android16_oem_unlock_prop_cleanup` modules when present.

### SystemUI hooks only

1. Install the APK.
2. Enable `dash-evox-patch` in LSPosed.
3. Scope it to **System UI** (`com.android.systemui`) only.
4. Reboot.

## Building

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Build the KernelSU ZIP with the same APK embedded:

```bash
./gradlew packageKernelSuDebug
```

Artifacts are written to:

- `app/build/outputs/apk/debug/`
- `build/outputs/kernelsu/`

Public releases must use a stable signing key so upgrades retain the same APK identity.

## Diagnostics

After boot, the complete module writes diagnostics to:

```text
/data/adb/modules/dash_evox_patch/property-state.log
/data/adb/dash-evox-patch/rotation-state.log
```

Disabling or removing the KernelSU module and rebooting restores the ROM's original boot property values.

## License

MIT
