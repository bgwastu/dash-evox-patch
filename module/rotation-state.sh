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

restore_loaded_rotation_state() {
  [ -n "$SAVED_ACCELEROMETER_ROTATION" ] || return 1
  settings --user 0 put secure "$ROTATION_SETTING" \
    "$SAVED_ACCELEROMETER_ROTATION:$SAVED_USER_ROTATION" >/dev/null 2>&1
  settings --user 0 put system user_rotation "$SAVED_USER_ROTATION" >/dev/null 2>&1
  settings --user 0 put system accelerometer_rotation \
    "$SAVED_ACCELEROMETER_ROTATION" >/dev/null 2>&1
}

save_live_rotation_state() {
  accelerometer=$(read_system_setting accelerometer_rotation)
  rotation=$(read_system_setting user_rotation)
  saved="$accelerometer:$rotation"
  is_rotation_state "$saved" || return 1

  mkdir -p "$ROTATION_STATE_DIR"
  chmod 0700 "$ROTATION_STATE_DIR"
  {
    echo "accelerometer_rotation=$accelerometer"
    echo "user_rotation=$rotation"
  } > "$ROTATION_STATE_FILE"
  chmod 0600 "$ROTATION_STATE_FILE"
  settings --user 0 put secure "$ROTATION_SETTING" "$saved" >/dev/null 2>&1
  SAVED_ACCELEROMETER_ROTATION=$accelerometer
  SAVED_USER_ROTATION=$rotation
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

live_rotation_matches_saved() {
  [ "$(read_system_setting accelerometer_rotation)" = "$SAVED_ACCELEROMETER_ROTATION" ] && \
    [ "$(read_system_setting user_rotation)" = "$SAVED_USER_ROTATION" ]
}

# True when the newest WindowManager rotation-lock entry, written within the last few
# seconds, came from adb automation rather than a UI path.
rotation_change_is_automation() {
  entry=$(dumpsys window displays 2>/dev/null | sed -n '/RotationLockHistory/,/^ *$/p' \
    | grep 'caller=' | tail -1)
  case "$entry" in
    *MonkeyRotationEvent*|*MonkeyRotationEven*|*UiAutomationConnection*) ;;
    *) return 1 ;;
  esac
  stamp=$(echo "$entry" | sed -n 's/^ *\([0-9][0-9]-[0-9][0-9] [0-9:]*\)\..*/\1/p')
  entry_epoch=$(date -d "$(date +%Y)-$stamp" +%s 2>/dev/null) || return 0
  [ -n "$entry_epoch" ] || return 0
  [ $(( $(date +%s) - entry_epoch )) -le 15 ]
}

watch_rotation_state() {
  i=0
  while [ "$i" -lt 60 ]; do
    load_rotation_state && break
    sleep 1
    i=$((i + 1))
  done
  load_rotation_state || exit 0

  log_rotation_state before-early-restore
  restore_loaded_rotation_state
  log_rotation_state after-early-restore

  i=0
  while [ "$i" -lt 120 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 1
    i=$((i + 1))
  done

  boot_at=$(date +%s)
  for target in 3 10 30; do
    now=$(date +%s)
    delay=$((target - (now - boot_at)))
    if [ "$delay" -gt 0 ]; then
      sleep "$delay"
    fi
    if live_rotation_matches_saved; then
      log_rotation_state "settled-${target}s"
    else
      log_rotation_state "before-restore-${target}s"
      restore_loaded_rotation_state
      log_rotation_state "after-restore-${target}s"
    fi
  done

  while true; do
    sleep 3
    if live_rotation_matches_saved; then
      continue
    fi
    if rotation_change_is_automation; then
      # adb tooling (monkey, uiautomator) thaws rotation as a side effect. That is not the
      # user's choice, so put the saved state back instead of remembering it.
      log_rotation_state "before-automation-restore"
      restore_loaded_rotation_state
      log_rotation_state "after-automation-restore"
      continue
    fi
    previous_accelerometer=$SAVED_ACCELEROMETER_ROTATION
    save_live_rotation_state || continue
    log_rotation_state user-change
    # Keep the writer history for diagnosing unexpected auto-rotate changes.
    if [ "$previous_accelerometer" = "0" ] && [ "$SAVED_ACCELEROMETER_ROTATION" = "1" ]; then
      dumpsys window displays 2>/dev/null | sed -n '/RotationLockHistory/,/^ *$/p' \
        >> "$ROTATION_LOG_FILE"
    fi
  done
}

case "$0" in
  */rotation-state.sh|rotation-state.sh)
    watch_rotation_state
    ;;
esac
