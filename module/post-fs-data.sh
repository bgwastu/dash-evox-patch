#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

resolve_resetprop || exit 1

"$RESETPROP" -n sys.brightness.disable_gamma_conversion false
"$RESETPROP" -n ro.config.media_vol_steps 30
"$RESETPROP" -n ro.vendor.audio.media.volume.steps 30
"$RESETPROP" -n persist.vendor.audiohal.besloudness_state 1
"$RESETPROP" -n ro.hardware.consumerir common
delete_oem_unlock_prop || true
