#!/system/bin/sh

if [ "$(getprop sys.boot_completed)" = "1" ]; then
  pm uninstall net.wastu.dashevoxpatch >/dev/null 2>&1 || true
  pm uninstall io.github.bgwastu.dashevoxpatch >/dev/null 2>&1 || true
fi

rm -rf /data/adb/dash-evox-patch
