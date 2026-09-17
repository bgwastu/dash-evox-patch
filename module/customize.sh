#!/system/bin/sh

DEVICE=$(getprop ro.product.device)
SDK=$(getprop ro.build.version.sdk)
EVOLUTION_VERSION=$(getprop ro.evolution.version)

[ "$DEVICE" = "dash" ] || abort "Unsupported device: $DEVICE (expected dash)"
[ "$SDK" = "36" ] || abort "Unsupported Android SDK: $SDK (expected 36)"

if [ -z "$EVOLUTION_VERSION" ]; then
  abort "EvolutionX was not detected (ro.evolution.version is empty)"
fi

APK="$MODPATH/dash-evox-patch.apk"
[ -f "$APK" ] || abort "Embedded APK is missing"

ui_print "- Device: $DEVICE"
ui_print "- EvolutionX: $EVOLUTION_VERSION"
ui_print "- Installing SystemUI hook APK"

pm install -r "$APK" >/dev/null 2>&1 || abort "Could not install dash-evox-patch.apk"

if pm path io.github.bgwastu.dashevoxpatch >/dev/null 2>&1; then
  pm uninstall io.github.bgwastu.dashevoxpatch >/dev/null 2>&1 || true
  ui_print "- Removed legacy APK package: io.github.bgwastu.dashevoxpatch"
fi

ROTATION_STATE_DIR=/data/adb/dash-evox-patch
ROTATION_STATE_FILE="$ROTATION_STATE_DIR/rotation-state"
if [ ! -f "$ROTATION_STATE_FILE" ]; then
  accelerometer_rotation=$(settings --user 0 get system accelerometer_rotation 2>/dev/null)
  user_rotation=$(settings --user 0 get system user_rotation 2>/dev/null)
  case "$accelerometer_rotation" in 0|1) ;; *) accelerometer_rotation=0 ;; esac
  case "$user_rotation" in 0|1|2|3) ;; *) user_rotation=0 ;; esac
  mkdir -p "$ROTATION_STATE_DIR"
  {
    echo "accelerometer_rotation=$accelerometer_rotation"
    echo "user_rotation=$user_rotation"
  } > "$ROTATION_STATE_FILE"
  chmod 0700 "$ROTATION_STATE_DIR"
  chmod 0600 "$ROTATION_STATE_FILE"
  ui_print "- Saved current rotation state: $accelerometer_rotation/$user_rotation"
fi

for legacy_module in slider_scaling_fix android16_oem_unlock_prop_cleanup; do
  legacy_path="/data/adb/modules/$legacy_module"
  if [ -d "$legacy_path" ]; then
    touch "$legacy_path/disable"
    ui_print "- Disabled superseded module: $legacy_module"
  fi
done

set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0644
set_perm "$MODPATH/rotation-state.sh" 0 0 0644
set_perm "$APK" 0 0 0644

ui_print "- Enable dash-evox-patch for System UI in LSPosed, then reboot"
