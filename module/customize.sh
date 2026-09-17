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

if [ -x /data/adb/ksu/bin/resetprop ]; then
  /data/adb/ksu/bin/resetprop -p persist.vendor.audiohal.besloudness_state 1
elif command -v resetprop >/dev/null 2>&1; then
  resetprop -p persist.vendor.audiohal.besloudness_state 1
fi
ui_print "- Installing SystemUI hook APK"

if ! pm install -r "$APK" >/dev/null 2>&1; then
  pm uninstall net.wastu.dashevoxpatch >/dev/null 2>&1 || true
  pm install "$APK" >/dev/null 2>&1 || abort "Could not install dash-evox-patch.apk"
  ui_print "- Reinstalled APK with the stable release signature"
fi

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

saved_rotation=$(settings --user 0 get secure dash_evox_rotation_state 2>/dev/null)
case "$saved_rotation" in
  0:[0-3]|1:[0-3]) ;;
  *)
    accelerometer_rotation=$(sed -n 's/^accelerometer_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
    user_rotation=$(sed -n 's/^user_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
    settings --user 0 put secure dash_evox_rotation_state \
      "$accelerometer_rotation:$user_rotation" >/dev/null 2>&1
    ;;
esac

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
