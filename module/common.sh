#!/system/bin/sh

resolve_resetprop() {
  for candidate in \
    /data/adb/ksu/bin/resetprop \
    /data/adb/ap/bin/resetprop \
    /data/adb/magisk/resetprop
  do
    if [ -x "$candidate" ]; then
      RESETPROP="$candidate"
      return 0
    fi
  done

  RESETPROP=resetprop
  command -v "$RESETPROP" >/dev/null 2>&1
}

delete_oem_unlock_prop() {
  [ -n "$(getprop sys.oem_unlock_allowed)" ] || return 0

  "$RESETPROP" --skip-svc --delete --rebuild sys.oem_unlock_allowed 2>/dev/null && return 0
  "$RESETPROP" --delete sys.oem_unlock_allowed 2>/dev/null || return 1
}

log_property_state() {
  {
    echo "timestamp=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "sys.brightness.disable_gamma_conversion=$(getprop sys.brightness.disable_gamma_conversion)"
    echo "ro.config.media_vol_steps=$(getprop ro.config.media_vol_steps)"
    echo "ro.vendor.audio.media.volume.steps=$(getprop ro.vendor.audio.media.volume.steps)"
    echo "persist.vendor.audiohal.besloudness_state=$(getprop persist.vendor.audiohal.besloudness_state)"
    echo "sys.oem_unlock_allowed=$(getprop sys.oem_unlock_allowed)"
  } > "$MODDIR/property-state.log"
}
