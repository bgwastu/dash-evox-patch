#!/system/bin/sh
# The ROM's audio parameter parser rejects every vendor key, so AudioSystem.setParameters()
# never reaches the MediaTek HAL. The parser loads ro.audio.ihaladaptervendorextension_libname
# from its own library path, so put libdashvendorext.so into /system_ext/lib64.
# KernelSU's magic mount does not apply on this kernel, so mirror the directory on tmpfs with
# per-file binds, the same way misound-mount.sh handles /vendor/lib64.

MODDIR=${0%/*}
LIB=$MODDIR/vendorext/libdashvendorext.so
TARGET=/system_ext/lib64
MIRROR=$MODDIR/mnt/system_ext_lib64
LOG=$MODDIR/vendorext.log

log() {
  echo "$*" >> "$LOG"
}

[ -f "$LIB" ] || exit 1
if [ -e "$TARGET/libdashvendorext.so" ]; then
  exit 0
fi

: > "$LOG"
log "start"

if mountpoint -q "$MIRROR"; then
  umount -l "$MIRROR" || true
fi
rm -rf "$MIRROR"
mkdir -p "$MIRROR"
mount -t tmpfs -o mode=755 tmpfs "$MIRROR" || {
  log "tmpfs failed"
  exit 1
}
chcon u:object_r:system_lib_file:s0 "$MIRROR"

ok=0
fail=0
for path in "$TARGET"/* "$TARGET"/.[!.]*; do
  [ -e "$path" ] || [ -L "$path" ] || continue
  name=${path##*/}
  if [ -L "$path" ]; then
    ln -s "$(readlink "$path")" "$MIRROR/$name"
  elif [ -d "$path" ]; then
    mkdir -p "$MIRROR/$name"
    if mount --bind "$path" "$MIRROR/$name"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
  else
    touch "$MIRROR/$name"
    if mount --bind "$path" "$MIRROR/$name"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
  fi
done
log "mirrored ok=$ok fail=$fail"
if [ "$fail" -ne 0 ]; then
  umount -l "$MIRROR"
  exit 1
fi

cp "$LIB" "$MIRROR/libdashvendorext.so"
chmod 644 "$MIRROR/libdashvendorext.so"
chcon u:object_r:system_lib_file:s0 "$MIRROR/libdashvendorext.so"

mount -o rbind "$MIRROR" "$TARGET" || {
  log "rbind failed"
  umount -l "$MIRROR"
  exit 1
}
log "done"
