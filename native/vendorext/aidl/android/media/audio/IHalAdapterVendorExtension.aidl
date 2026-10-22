// Android 17 definition, copied from AOSP frameworks/av (Apache-2.0). Android 16 used a plain
// ParameterScope enum; Android 17 wraps it in a parcelable with the module instance name.
package android.media.audio;

import android.hardware.audio.core.VendorParameter;

interface IHalAdapterVendorExtension {
    enum ScopeType {
        MODULE = 0,
        STREAM = 1,
    }

    parcelable ParameterScope {
        ScopeType scope;
        @utf8InCpp String instanceName;
    }

    @utf8InCpp String[] parseVendorParameterIds(
            in ParameterScope scope, in @utf8InCpp String rawKeys);

    void parseVendorParameters(
            in ParameterScope scope, in @utf8InCpp String rawKeysAndValues,
            out VendorParameter[] syncParameters, out VendorParameter[] asyncParameters);

    VendorParameter[] parseBluetoothA2dpReconfigureOffload(in @utf8InCpp String rawValue);

    VendorParameter[] parseBluetoothLeReconfigureOffload(in @utf8InCpp String rawValue);

    @utf8InCpp String processVendorParameters(
            in ParameterScope scope, in VendorParameter[] parameters);
}
