# HyperCharge and speaker-volume research

## HyperCharge detection failure

The installed `dev.wastu.hypercharge` APK hooks SystemUI's `KeyguardIndicationController` and classifies HyperCharge only when all of these files report the expected values:

- `/sys/class/power_supply/usb/apdo_max >= 100`
- `/sys/class/power_supply/usb/pd_authentication == 1`
- `/sys/class/power_supply/usb/pd_verify_done == 1`
- `/sys/class/power_supply/bms/fastcharge_mode == 1`

On `dash`, `bms/fastcharge_mode` does not exist. This makes the full HyperCharge condition permanently false. The implementation also uses SystemUI's framework wattage field instead of the device's live MediaTek USB measurements.

The Xiaomi MediaTek charging driver defines `quick_charge_type` as:

- `0`: normal
- `1`: fast
- `2`: flash
- `3`: turbo
- `4`: super

Source: [`XagaForge/android_kernel_xiaomi_mt6895`, `drivers/power/xm_charge/xmc_core.h`](https://github.com/XagaForge/android_kernel_xiaomi_mt6895/blob/f89055e3e87b498c92be09a06f46c97b4e068f30/drivers/power/xm_charge/xmc_core.h)

The merged implementation therefore uses `usb/quick_charge_type` as the primary classification signal, with authenticated high-power APDO data as a fallback. Live input power is calculated from `usb/input_current_now` and `usb/pmic_vbus`, whose units on this device are mA and mV. Battery current for the time estimate comes from `battery/current_now` in µA.

A device sample while connected to a 33 W PD source reported:

- `quick_charge_type=1`
- `apdo_max=33`
- `real_type=USB_PD`
- `input_current_now=953`
- `pmic_vbus=4935`

This correctly classifies as fast charging and yields about 4.7 W of live input power at that moment, rather than presenting the charger's 33 W capability as instantaneous power.

## Speaker volume response

The stock speaker media curve in `default_volume_tables.xml` is:

- `1: -58 dB`
- `20: -40 dB`
- `60: -17 dB`
- `100: 0 dB`

Linear interpolation places the 50% slider position near `-22.75 dB`, which explains why the middle of the slider sounds unusually quiet. Audio volume is logarithmic and should not copy brightness-slider scaling.

The replacement speaker-only curve is:

- `1: -50 dB`
- `20: -26 dB`
- `50: -9 dB`
- `75: -4 dB`
- `100: 0 dB`

Headphone, Bluetooth, earpiece, and maximum-volume endpoints remain unchanged. This produces a deliberately stronger midrange while preserving mute behavior and the calibrated 100% endpoint.

Reference: [`LineageOS/android_frameworks_av`, `default_volume_tables.xml`](https://github.com/LineageOS/android_frameworks_av/blob/lineage-23.2/services/audiopolicy/config/default_volume_tables.xml)
