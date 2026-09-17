#!/system/bin/sh

ROTATION_STATE_DIR=/data/adb/dash-evox-patch
ROTATION_STATE_FILE="$ROTATION_STATE_DIR/rotation-state"
ROTATION_LOG_FILE="$ROTATION_STATE_DIR/rotation-state.log"
ROTATION_SETTING=dash_evox_rotation_state

read_system_setting() {
  settings --user 0 get system "$1" 2>/dev/null
}

is_rotation_state() {
  case "$1" in
    0:[0-3]|1:[0-3]) return 0 ;;
    *) return 1 ;;
  esac
}

load_rotation_state() {
  saved=$(settings --user 0 get secure "$ROTATION_SETTING" 2>/dev/null)
  if ! is_rotation_state "$saved" && [ -f "$ROTATION_STATE_FILE" ]; then
    accelerometer=$(sed -n 's/^accelerometer_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
    rotation=$(sed -n 's/^user_rotation=//p' "$ROTATION_STATE_FILE" | head -1)
    saved="$accelerometer:$rotation"
  fi
  is_rotation_state "$saved" || return 1

  SAVED_ACCELEROMETER_ROTATION=${saved%%:*}
  SAVED_USER_ROTATION=${saved#*:}
}

restore_rotation_state() {
  load_rotation_state || return 1
  settings --user 0 put secure "$ROTATION_SETTING" \
    "$SAVED_ACCELEROMETER_ROTATION:$SAVED_USER_ROTATION" >/dev/null 2>&1
  settings --user 0 put system user_rotation "$SAVED_USER_ROTATION" >/dev/null 2>&1
  settings --user 0 put system accelerometer_rotation \
    "$SAVED_ACCELEROMETER_ROTATION" >/dev/null 2>&1
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
