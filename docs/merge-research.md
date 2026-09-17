# Merge research: APK hooks and root property fixes

## Existing pieces

The current project is an LSPosed APK scoped to `com.android.systemui`. Two separate KernelSU modules are installed on the target device:

- `slider_scaling_fix` sets `sys.brightness.disable_gamma_conversion=false`, `ro.config.media_vol_steps=30`, and `ro.vendor.audio.media.volume.steps=30` in `post-fs-data.sh`.
- `android16_oem_unlock_prop_cleanup` deletes `sys.oem_unlock_allowed` in both `post-fs-data.sh` and `service.sh`.

## Primary-source findings

### KernelSU packaging and lifecycle

KernelSU modules are ZIP archives containing `module.prop` plus optional lifecycle scripts. `post-fs-data.sh` is blocking, runs before Zygote, and has a 10-second limit. KernelSU explicitly warns that normal `setprop` can deadlock there and recommends `resetprop -n`. `service.sh` is non-blocking and is the recommended stage for most scripts. Modules that only use scripts and properties do not require a metamodule or a mounted `system` directory.

Source: [KernelSU module guide](https://kernelsu.org/guide/module.html)

A module can carry arbitrary extra files, so it can include a built APK. A `customize.sh` installer can install that APK while Android is running. An `uninstall.sh` can remove it when the root module is removed. LSPosed scope and activation are framework state, however, not normal APK state. A public installer should not edit a specific LSPosed fork's private database.

Source: [KernelSU module installer and optional files](https://kernelsu.org/guide/module.html#module-installer)

### Property operations

Magisk documents `resetprop -n` as setting a property without going through Android's property service and `--delete` as deleting a property. The installed KernelSU build additionally supports property-area rebuilding, but this is implementation-specific and should be capability-checked before using `--rebuild`.

Source: [Magisk tools: resetprop](https://github.com/topjohnwu/Magisk/blob/master/docs/tools.md#resetprop)

### OEM unlock property cleanup

AOSP removed `sys.oem_unlock_allowed` from property contexts because its Android-side check had already been unused since 2016 and exposing it to untrusted apps was undesirable. The cleanup is therefore defensible on Android 16 builds that still recreate the obsolete property through vendor code.

Source: [AOSP commit 47718268, Remove OEM_UNLOCK_PROP usage](https://android.googlesource.com/platform/system/core.git/+/47718268d4537ea5db1228ab9aa18a176f0c7b92%5E!/)

The cleanup should remain conditional. If the property does not exist, the script should do nothing. Deletion should run once early and once after boot because vendor components may recreate it later.

### Brightness and volume properties

The brightness property is a ROM/device compatibility switch used by Android-derived device trees to bypass or restore framework gamma conversion. It is not a stable public Android interface, so the module must restrict support to the tested ROM/device and verify the property after boot.

The media volume properties are read-only boot properties. They need to be present before audio services initialize, which justifies the early `resetprop -n` stage. `ro.config.media_vol_steps` is the AOSP-compatible property; `ro.vendor.audio.media.volume.steps` is vendor-specific and should be retained only for the tested device.

AOSP source also exposes configurable media-volume defaults through `ro.config.media_vol_default`, demonstrating that media-volume behavior is property-driven, but the exact vendor property remains device-specific.

Source: [AOSP AudioService media-volume property change](https://android.googlesource.com/platform/frameworks/base/+/403bd34%5E!/)

## Packaging options

### Option A: one APK only

Not sufficient. LSPosed hooks cannot reliably apply read-only boot properties before framework services start.

### Option B: one KernelSU ZIP that bundles and installs the APK

Technically possible and closest to a single-package experience. The ZIP contains the property scripts and a prebuilt APK. `customize.sh` installs or upgrades the APK, while `uninstall.sh` optionally removes it.

Tradeoffs:

- Installation and updates are simple.
- The installer cannot portably enable LSPosed or set scope across LSPosed, Vector, and other compatible frameworks.
- APK signing identity must remain stable across releases.
- Uninstall behavior needs an explicit policy: preserve the APK or remove it.

### Option C: one repository and release, with a KernelSU ZIP plus standalone APK

Recommended. Build the APK once, embed the same APK in the KernelSU ZIP, and publish both artifacts in one GitHub release. Users wanting the complete patch flash the ZIP, which installs the APK and boot scripts. The standalone APK remains available for development, upgrades, and users who do not want the property changes.

This keeps one codebase and one version while preserving clean seams:

- `app/`: LSPosed SystemUI hooks.
- `module/`: root lifecycle scripts and installer.
- build task: assembles the APK, copies it into the module payload, and creates the flashable ZIP.

## Risk controls

- Gate installation on device codename `dash`, Android 16, and EvolutionX unless the user explicitly overrides compatibility checks.
- Never mount or replace system files; only use scripts and properties.
- Keep `post-fs-data.sh` short and non-looping.
- Move the wait-for-boot loop to `service.sh` or `boot-completed.sh`.
- Resolve `resetprop` from KernelSU, APatch, Magisk, then PATH.
- Capability-check `--rebuild`; fall back to plain conditional deletion if unsupported.
- Record original property values during installation for diagnostics, but do not attempt to restore immutable `ro.*` values during uninstall. Disabling/removing the module and rebooting naturally restores boot defaults.
- Do not automate LSPosed scope by editing private databases. Document that System UI must be selected.
- Preserve the existing APK signing key for all public updates.

## Recommended verification

1. Build APK and module ZIP reproducibly.
2. Fresh install on the target build.
3. Confirm the APK is installed, LSPosed scope can be enabled, and SystemUI starts without hook errors.
4. Reboot twice and verify the collapsed date, calendar action, header padding, and notification-dismiss haptics.
5. Verify all three target property outcomes after boot.
6. Disable the root module, reboot, and verify stock brightness/volume properties return while the APK remains installed.
7. Remove the APK or LSPosed scope and verify SystemUI returns to stock behavior.
8. Test upgrade from the current standalone APK and separate property modules without package-signature or duplicate-script conflicts.
