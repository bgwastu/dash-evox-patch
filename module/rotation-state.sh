#!/system/bin/sh

ROTATION_STATE_DIR=/data/adb/dash-evox-patch
ROTATION_STATE_FILE="$ROTATION_STATE_DIR/rotation-state"
ROTATION_LOG_FILE="$ROTATION_STATE_DIR/rotation-state.log"

read_system_setting() {
  settings --user 0 get system "$1" 2>/dev/null
}

is_accelerometer_rotation() {
  [ "$1" = "0" ] || [ "$1" = "1" ]
}

is_user_rotation() {
  case "$1" in
    0|1|2|3) return 0 ;;
    *) return 1 ;;
  esac
}

write_rotation_state() {
  is_accelerometer_rotation "$1" || return 1
  is_user_rotation "$2" || return 1

  mkdir -p "$ROTATION_STATE_DIR"
  {
    echo "accelerometer_rotation=$1"
    echo "user_rotation=$2"
  } > "$ROTATION_STATE_FILE.tmp"
  chmod 0600 "$ROTATION_STATE_FILE.tmp"
  mv -f "$ROTATION_STATE_FILE.tmp" "$ROTATION_STATE_FILE"
}

load_rotation_state() {
  [ -f "$ROTATION_STATE_FILE" ] || return 1

  SAVED_ACCELEROMETER_ROTATION=$(sed -n 's/^accelerometer_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
  SAVED_USER_ROTATION=$(sed -n 's/^user_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
  is_accelerometer_rotation "$SAVED_ACCELEROMETER_ROTATION" || return 1
  is_user_rotation "$SAVED_USER_ROTATION" || return 1
}

capture_rotation_state() {
  current_accelerometer=$(read_system_setting accelerometer_rotation)
  current_user_rotation=$(read_system_setting user_rotation)
  is_accelerometer_rotation "$current_accelerometer" || return 1
  is_user_rotation "$current_user_rotation" || current_user_rotation=0
  write_rotation_state "$current_accelerometer" "$current_user_rotation"
}

restore_rotation_state() {
  load_rotation_state || return 1
  settings --user 0 put system user_rotation "$SAVED_USER_ROTATION" >/dev/null 2>&1
  settings --user 0 put system accelerometer_rotation "$SAVED_ACCELEROMETER_ROTATION" >/dev/null 2>&1
}

log_rotation_state() {
  mkdir -p "$ROTATION_STATE_DIR"
  printf '%s boot_completed=%s saved=%s/%s actual=%s/%s event=%s\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" \
    "$(getprop sys.boot_completed)" \
    "$SAVED_ACCELEROMETER_ROTATION" \
    "$SAVED_USER_ROTATION" \
    "$(read_system_setting accelerometer_rotation)" \
    "$(read_system_setting user_rotation)" \
    "$1" >> "$ROTATION_LOG_FILE"
  chmod 0600 "$ROTATION_LOG_FILE"
}
