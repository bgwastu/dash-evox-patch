// Copied from AOSP hardware/interfaces/audio/aidl (Apache-2.0).
package android.hardware.audio.core;

@VintfStability
parcelable VendorParameter {
    @utf8InCpp String id;
    ParcelableHolder ext;
}
