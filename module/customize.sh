#!/system/bin/sh

DEVICE=$(getprop ro.product.device)
SDK=$(getprop ro.build.version.sdk)
EVOLUTION_VERSION=$(getprop ro.evolution.version)

[ "$DEVICE" = "dash" ] || abort "Unsupported device: $DEVICE (expected dash)"
case "$SDK" in
  36|37) ;;
  *) abort "Unsupported Android SDK: $SDK (expected 36 or 37)" ;;
esac

if [ -z "$EVOLUTION_VERSION" ]; then
  abort "EvolutionX was not detected (ro.evolution.version is empty)"
fi

APK="$MODPATH/dash-evox-patch.apk"
[ -f "$APK" ] || abort "Embedded APK is missing"

ui_print "- Device: $DEVICE"
ui_print "- EvolutionX: $EVOLUTION_VERSION"
ui_print "- Installing SystemUI hook APK"

if ! pm install -r "$APK" >/dev/null 2>&1; then
  pm uninstall net.wastu.dashevoxpatch >/dev/null 2>&1 || true
  pm install "$APK" >/dev/null 2>&1 || abort "Could not install dash-evox-patch.apk"
  ui_print "- Reinstalled APK with the stable release signature"
fi

if [ -x /data/adb/lspd/cli ]; then
  /data/adb/lspd/cli modules enable net.wastu.dashevoxpatch >/dev/null 2>&1 || true
  /data/adb/lspd/cli scope add net.wastu.dashevoxpatch android/0 >/dev/null 2>&1 || true
  /data/adb/lspd/cli scope add net.wastu.dashevoxpatch com.android.systemui/0 >/dev/null 2>&1 || true
  ui_print "- Configured Vector scope: android + com.android.systemui"
fi

if pm path io.github.bgwastu.dashevoxpatch >/dev/null 2>&1; then
  pm uninstall io.github.bgwastu.dashevoxpatch >/dev/null 2>&1 || true
  ui_print "- Removed legacy APK package: io.github.bgwastu.dashevoxpatch"
fi

if pm path dev.wastu.hypercharge >/dev/null 2>&1; then
  pm uninstall dev.wastu.hypercharge >/dev/null 2>&1 || true
  ui_print "- Removed standalone HyperCharge module (now integrated)"
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
set_perm "$MODPATH/charge-state.sh" 0 0 0755
set_perm "$APK" 0 0 0644

ui_print "- Enable dash-evox-patch for System UI in LSPosed, then reboot"
