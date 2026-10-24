#!/system/bin/sh

MODDIR=${0%/*}

RESETPROP=/data/adb/ksu/bin/resetprop
if [ ! -x "$RESETPROP" ]; then
  RESETPROP=resetprop
fi

# The ROM sets this true, which makes the brightness slider linear.
"$RESETPROP" -n sys.brightness.disable_gamma_conversion false

# Vendor build.prop turns Xiaomi's speaker tuning off. Clear that before
# audioserver starts. misound-mount.sh then puts the stock DSP library back.
"$RESETPROP" -n persist.vendor.audio.misound.disable false
"$RESETPROP" -n vendor.audio.misound.bypass false

# The framework reads this. The vendor property is already 30, which stock uses,
# but this ROM leaves the framework property unset and keeps the 15-step slider.
"$RESETPROP" -n ro.config.media_vol_steps 30

VOL="$MODDIR/volume/default_volume_tables.xml"
if [ -f "$VOL" ]; then
  chcon u:object_r:vendor_file:s0 "$VOL"
  if ! awk '$2=="/vendor/etc/default_volume_tables.xml" { found = 1 } END { exit !found }' /proc/mounts; then
    mount --bind "$VOL" /vendor/etc/default_volume_tables.xml
  fi
fi

"$MODDIR/misound-mount.sh"

# HyperOS Super Volume (200%/400% speaker boost). The vendor HAL supports it, but the ROM
# leaves the switch off, and its Android 16 parameter parser cannot decode Android 17 calls,
# so no vendor key reaches the HAL. libdashvendorext.so replaces that parser's logic and
# the system_server hook drives the boost.
"$RESETPROP" -n ro.vendor.audio.supervolume.support true
if sh "$MODDIR/vendorext-mount.sh"; then
  "$RESETPROP" -n ro.audio.ihaladaptervendorextension_libname libdashvendorext.so
fi
