# ConsumerIR on the current EvolutionX vendor image

Checked on the updated POCO X8 Pro Max (`dash`) build on 2026-09-27:

- The vendor init service `vendor.ir-default` starts `/vendor/bin/hw/android.hardware.ir-service.example`.
- The framework reports an IR emitter, and a read-only carrier-frequency query returns six ranges.
- The running IR process has `/vendor/lib64/hw/consumerir.common.so` mapped. That vendor library references `/dev/irtx` and MediaTek IRTX controls.
- `ro.hardware.consumerir` is already `common`.

The module's old `android.hardware.ir-service.lineage` file was not the service started by this ROM. Its binary is the generic AOSP AIDL wrapper, which loads a `consumerir` hardware module through `libhardware`; it does not implement the MediaTek device access itself. Shipping it under a different filename did not update the running service.

The module therefore no longer overlays the IR service or forces `ro.hardware.consumerir=common`. The ROM already owns that working chain. A physical IR transmission was not sent during this check.
