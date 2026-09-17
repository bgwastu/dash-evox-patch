#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"
. "$MODDIR/rotation-state.sh"

resolve_resetprop || exit 1

i=0
while [ "$i" -lt 60 ]; do
  if load_rotation_state; then
    break
  fi
  sleep 1
  i=$((i + 1))
done

if load_rotation_state; then
  log_rotation_state before-early-restore
  restore_rotation_state
  log_rotation_state after-early-restore
fi

i=0
while [ "$i" -lt 120 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
  i=$((i + 1))
done

"$RESETPROP" persist.vendor.audiohal.besloudness_state 1
delete_oem_unlock_prop || true
log_property_state


"$MODDIR/charge-state.sh" &
sleep 5
if load_rotation_state; then
  current_accelerometer=$(read_system_setting accelerometer_rotation)
  current_user_rotation=$(read_system_setting user_rotation)
  if [ "$current_accelerometer" != "$SAVED_ACCELEROMETER_ROTATION" ] || \
     [ "$current_user_rotation" != "$SAVED_USER_ROTATION" ]; then
    log_rotation_state before-post-boot-restore
    restore_rotation_state
    log_rotation_state after-post-boot-restore
  else
    log_rotation_state post-boot-state-ok
  fi
fi
