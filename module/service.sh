#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"
. "$MODDIR/rotation-state.sh"

resolve_resetprop || exit 1

i=0
while [ "$i" -lt 60 ]; do
  current_accelerometer=$(read_system_setting accelerometer_rotation)
  if is_accelerometer_rotation "$current_accelerometer"; then
    break
  fi
  sleep 1
  i=$((i + 1))
done

if ! load_rotation_state; then
  capture_rotation_state || true
  load_rotation_state || true
fi

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

delete_oem_unlock_prop || true
log_property_state

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

while true; do
  sleep 3
  current_accelerometer=$(read_system_setting accelerometer_rotation)
  current_user_rotation=$(read_system_setting user_rotation)

  is_accelerometer_rotation "$current_accelerometer" || continue
  is_user_rotation "$current_user_rotation" || current_user_rotation=0
  load_rotation_state || continue

  if [ "$current_accelerometer" != "$SAVED_ACCELEROMETER_ROTATION" ] || \
     [ "$current_user_rotation" != "$SAVED_USER_ROTATION" ]; then
    write_rotation_state "$current_accelerometer" "$current_user_rotation"
    load_rotation_state
    log_rotation_state user-state-updated
  fi
done
