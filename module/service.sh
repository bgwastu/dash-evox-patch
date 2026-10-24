#!/system/bin/sh

MODDIR=${0%/*}

i=0
while [ "$i" -lt 120 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 1
  i=$((i + 1))
done

# The launcher ignores a navigation-pill hold unless this is on. The ROM leaves it unset.
# The stock fstab mounts zram swap at 100% of RAM as its "RAM boost"; this device
# does not need it, so detach and disable the zram device every boot. Runs after
# swapon_all, so swap may or may not be up when this lands.
ZRAM_LOG=/data/adb/dash-evox-patch/ramboost.log
{
  echo "$(date '+%F %T') ramboost disable"
  swapoff /dev/block/zram0 2>&1
  before=$(cat /sys/block/zram0/disksize)
  echo 0 > /sys/block/zram0/disksize 2>/dev/null || echo 1 > /sys/block/zram0/reset 2>/dev/null
  echo "disksize $before -> $(cat /sys/block/zram0/disksize)"
} >> "$ZRAM_LOG" 2>&1

# The launcher ignores a navigation-pill hold unless this is on. The ROM leaves it unset.
settings put secure search_all_entrypoints_enabled 1

"$MODDIR/charge-state.sh" &
"$MODDIR/rotation-state.sh" &
