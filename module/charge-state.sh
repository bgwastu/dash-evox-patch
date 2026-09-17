#!/system/bin/sh

SETTING=dash_evox_charger_class
USB=/sys/class/power_supply/usb
last=-1

read_value() {
  cat "$USB/$1" 2>/dev/null
}

while true; do
  online=$(read_value online)
  class=0

  if [ "$online" = "1" ]; then
    quick_charge_type=$(read_value quick_charge_type)
    apdo_max=$(read_value apdo_max)
    power_max=$(read_value power_max)
    authenticated=$(read_value pd_authentication)
    verification_done=$(read_value pd_verify_done)
    real_type=$(read_value real_type)

    case "$quick_charge_type" in
      3|4|5) class=2 ;;
      1|2) class=1 ;;
    esac

    if [ "$class" -lt 2 ] && [ "$authenticated" = "1" ] && \
       [ "$verification_done" = "1" ]; then
      if [ "${apdo_max:-0}" -ge 90 ] 2>/dev/null || \
         [ "${power_max:-0}" -ge 90 ] 2>/dev/null; then
        class=2
      fi
    fi

    if [ "$class" -eq 0 ]; then
      if [ "${apdo_max:-0}" -ge 18 ] 2>/dev/null || \
         [ "${power_max:-0}" -ge 18 ] 2>/dev/null || \
         [ "$real_type" = "USB_PD" ]; then
        class=1
      fi
    fi
  fi

  if [ "$class" != "$last" ]; then
    settings --user 0 put secure "$SETTING" "$class" >/dev/null 2>&1
    last=$class
  fi

  if [ "$online" = "1" ]; then
    sleep 2
  else
    sleep 15
  fi
done
