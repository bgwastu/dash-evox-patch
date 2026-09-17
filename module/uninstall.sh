#!/system/bin/sh

if [ -x /data/adb/ksu/bin/resetprop ]; then
  /data/adb/ksu/bin/resetprop -p persist.vendor.audiohal.besloudness_state 0
elif command -v resetprop >/dev/null 2>&1; then
  resetprop -p persist.vendor.audiohal.besloudness_state 0
fi

if [ "$(getprop sys.boot_completed)" = "1" ]; then
  pm uninstall net.wastu.dashevoxpatch >/dev/null 2>&1 || true
  pm uninstall io.github.bgwastu.dashevoxpatch >/dev/null 2>&1 || true
fi

rm -rf /data/adb/dash-evox-patch
