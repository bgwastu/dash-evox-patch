#!/system/bin/sh

MODDIR=${0%/*}

i=0
while [ "$i" -lt 120 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
  i=$((i + 1))
done

# The launcher ignores a navigation-pill hold unless this is on. The ROM leaves it unset.
settings put secure search_all_entrypoints_enabled 1
settings put system navbar_long_press_gesture 1

"$MODDIR/charge-state.sh" &
"$MODDIR/rotation-state.sh" &
