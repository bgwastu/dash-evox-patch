#!/system/bin/sh

RESETPROP=/data/adb/ksu/bin/resetprop
if [ ! -x "$RESETPROP" ]; then
  RESETPROP=resetprop
fi

# The ROM sets this true, which makes the brightness slider linear.
"$RESETPROP" -n sys.brightness.disable_gamma_conversion false
