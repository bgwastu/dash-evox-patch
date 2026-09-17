# Rotation-lock persistence research

## Device evidence

The phone currently reports:

```text
Settings.System.ACCELEROMETER_ROTATION = 0
Settings.System.USER_ROTATION = 0
WindowManager mUserRotationMode = USER_ROTATION_LOCKED
WindowManager mUserRotation = ROTATION_0
```

`0` for `accelerometer_rotation` means rotation is locked. WindowManager's rotation history records the change as coming from `RotationLockTile#handleClick`, so the Quick Settings tile is writing the expected state.

`dumpsys settings` shows the value as a normal owner-user system setting with generation changes. No installed root boot script currently writes either rotation setting.

The device also contains LineageParts' display-rotation screen. Decompilation shows that its main switch calls `RotationPolicy.setRotationLockForAccessibility(...)` and only observes `accelerometer_rotation`; it does not contain a boot receiver or service that unconditionally enables auto-rotation.

## What Android is supposed to do

AOSP defines rotation lock as a persisted system setting:

- `RotationPolicy.isRotationLocked()` returns true when `Settings.System.ACCELEROMETER_ROTATION` is `0`.
- Enabling the lock calls WindowManager `freezeRotation()`; disabling it calls `thawRotation()`.
- `USER_ROTATION` stores the locked angle.
- SettingsProvider persists system settings to per-user XML. Its default value is only for initialization, not something that should be reapplied on every boot.

AOSP Android 16's default for `def_accelerometer_rotation` is already `false`, which corresponds to rotation locked. Therefore an ordinary AOSP boot has no reason to turn a saved `0` into `1`.

Sources:

- [AOSP RotationPolicy.java, Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/com/android/internal/view/RotationPolicy.java)
- [AOSP SettingsProvider defaults.xml, Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SettingsProvider/res/values/defaults.xml)
- [AOSP RotationLockControllerImpl.java, Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/statusbar/policy/RotationLockControllerImpl.java)

## Conclusion

This is a ROM/device integration bug, not expected Android behavior. The UI and WindowManager currently agree that rotation is locked, so the toggle path itself works. Something outside the standard AOSP rotation path changes the setting during a real boot.

The exact writer cannot be named conclusively without capturing one failing reboot. No reboot was performed because the device was explicitly left running after module installation.

## Ranked causes

1. **A vendor or ROM boot component writes `accelerometer_rotation=1`.** This best matches a value that is correct before shutdown and wrong after boot.
2. **SettingsProvider starts with the correct value, then a late setup/device-customization task reapplies a preferred default.** The underlying build contains both EvolutionX/Lineage components and POCO vendor integration.
3. **A user-switch or restore path loads a different per-user value.** Less likely because user 0 is active and currently has the expected setting.
4. **Settings persistence itself fails.** Least likely because other settings persist and the current value is present in SettingsProvider's backing state.

`ro.setupwizard.rotation_locked=true` is not evidence of the cause. It controls Setup Wizard's own orientation behavior and is not the `accelerometer_rotation` setting.

## Recommended fix architecture

Add a small rotation-state keeper to the KernelSU half of `dash-evox-patch`, rather than forcing rotation lock permanently.

The keeper should preserve the user's last choice:

1. Store the current `accelerometer_rotation` and `user_rotation` values in `/data/adb/dash-evox-patch/rotation-state`.
2. During `service.sh`, wait until SettingsProvider answers queries.
3. Restore the saved values before waiting for full boot completion.
4. After boot stabilizes, monitor the settings and update the saved state when the user changes the rotation toggle.
5. Never hard-code rotation disabled. If the user enables auto-rotate later, that enabled state becomes the state restored on the next boot.

A root-side keeper is preferable to an LSPosed-only hook because rotation can be changed from Quick Settings, LineageParts, Settings, accessibility, or another privileged process. All of those paths converge on the same two persisted settings.

## Diagnostic addition

Before enforcing the state, the first test build should log:

- saved values
- first readable values during boot
- values immediately before restoration
- values after restoration
- timestamp and boot-completed state

This will prove that the boot value changed and show whether the reset occurs before or after KernelSU late-start scripts. If it occurs later, the keeper can perform one second restore pass after boot completion and then enter monitoring mode.
