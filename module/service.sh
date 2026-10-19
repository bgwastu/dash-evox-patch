#!/system/bin/sh

MODDIR=${0%/*}

i=0
while [ "$i" -lt 120 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
  i=$((i + 1))
done

"$MODDIR/charge-state.sh" &
