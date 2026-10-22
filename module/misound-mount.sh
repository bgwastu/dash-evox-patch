#!/system/bin/sh
# Overlayfs on this kernel returns EINVAL, and /vendor is read-only, so a new
# library cannot be created in place. Mirror the live lib64 with per-file binds
# from a second mount of the vendor image, then add the stock MiSound libraries.

MODDIR=${0%/*}
BLOB=$MODDIR/misound
ORIG=$MODDIR/mnt/vendor
MIRROR=$MODDIR/mnt/lib64
BUSYBOX=/data/adb/ksu/bin/busybox
LOG=$MODDIR/misound.log

log() {
  echo "$*" >> "$LOG"
}

if awk '$2=="/vendor/lib64" { found = 1 } END { exit !found }' /proc/mounts; then
  exit 0
fi

: > "$LOG"
log "start"

mkdir -p "$ORIG" "$MIRROR" || exit 0
if ! mountpoint -q "$ORIG"; then
  dev=$(awk '$2=="/vendor" { print $1; exit }' /proc/mounts)
  if [ -z "$dev" ]; then
    log "vendor device missing"
    exit 0
  fi
  mount -t erofs -o ro "$dev" "$ORIG" || {
    log "erofs mount failed"
    exit 0
  }
fi

if mountpoint -q "$MIRROR"; then
  umount -l "$MIRROR" || true
fi
rm -rf "$MIRROR"
mkdir -p "$MIRROR"
mount -t tmpfs -o mode=755 tmpfs "$MIRROR" || {
  log "tmpfs failed"
  exit 0
}
chcon u:object_r:vendor_file:s0 "$MIRROR" || {
  log "chcon mirror failed"
  exit 0
}

ok=0
fail=0
for path in "$ORIG/lib64"/* "$ORIG/lib64"/.[!.]*; do
  [ -e "$path" ] || [ -L "$path" ] || continue
  name=${path##*/}
  if [ -d "$path" ]; then
    mkdir -p "$MIRROR/$name"
    if mount --bind "$path" "$MIRROR/$name"; then
      ok=$((ok + 1))
    else
      log "FAILDIR $name"
      fail=$((fail + 1))
    fi
  elif [ -e "$path" ]; then
    touch "$MIRROR/$name"
    if mount --bind "$path" "$MIRROR/$name"; then
      ok=$((ok + 1))
    else
      log "FAILFILE $name"
      fail=$((fail + 1))
    fi
  else
    ln -s "$(readlink "$path")" "$MIRROR/$name"
  fi
done
log "mirrored ok=$ok fail=$fail"
if [ "$fail" -ne 0 ]; then
  umount -l "$MIRROR"
  exit 0
fi

for so in "$BLOB"/lib64/*.so; do
  [ -f "$so" ] || continue
  name=${so##*/}
  cp "$so" "$MIRROR/$name"
  chmod 644 "$MIRROR/$name"
  chcon u:object_r:vendor_file:s0 "$MIRROR/$name"
  log "added $name $(wc -c < "$MIRROR/$name")"
done

before=$(wc -c < "$ORIG/lib64/libdlbdap.so")
if [ -x "$BUSYBOX" ]; then
  "$BUSYBOX" mount -o rbind "$MIRROR" /vendor/lib64 || {
    log "rbind failed"
    exit 0
  }
else
  mount -o rbind "$MIRROR" /vendor/lib64 || {
    log "rbind failed"
    exit 0
  }
fi
after=$(wc -c < /vendor/lib64/libdlbdap.so)
log "dlbdap before=$before after=$after"
if [ "$before" != "$after" ]; then
  log "revert lib64"
  umount /vendor/lib64
  umount -l "$MIRROR"
  exit 0
fi

if [ -f "$BLOB/aurisys_config_hifi3.xml" ]; then
  chcon u:object_r:vendor_file:s0 "$BLOB/aurisys_config_hifi3.xml"
  if ! awk '$2=="/vendor/etc/aurisys_config_hifi3.xml" { found = 1 } END { exit !found }' /proc/mounts; then
    mount --bind "$BLOB/aurisys_config_hifi3.xml" /vendor/etc/aurisys_config_hifi3.xml || log "xml bind failed"
  fi
fi

if pidof audioserver >/dev/null 2>&1; then
  setprop ctl.restart audioserver
  log "restarted audioserver"
fi
log "done"
