// IHalAdapterVendorExtension for the MediaTek audio HAL on dash.
//
// The ROM ships HyperOS's Android 16 parameter parser, but Android 17 changed ParameterScope
// from an enum to a parcelable, so every call fails to unmarshal and legacy setParameters()
// strings such as "SpkVolIdx=17;SuperStream=3" never reach the HAL. The MediaTek HAL's
// Module/Stream::setVendorParameters() forwards any VendorParameter id it does not know to
// the legacy HAL as a raw key/value string, so this passes the raw string through as the id.

#include <aidl/android/media/audio/BnHalAdapterVendorExtension.h>
#include <android/log.h>

#include <dlfcn.h>

#include <string>
#include <vector>

#define LOG_TAG "DashVendorExt"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using aidl::android::hardware::audio::core::VendorParameter;
using aidl::android::media::audio::BnHalAdapterVendorExtension;
using aidl::android::media::audio::IHalAdapterVendorExtension;
using ScopeType = IHalAdapterVendorExtension::ScopeType;

namespace {

class VendorExtension : public BnHalAdapterVendorExtension {
  public:
    ndk::ScopedAStatus parseVendorParameterIds(const ParameterScope&, const std::string& rawKeys,
                                               std::vector<std::string>* ids) override {
        if (!rawKeys.empty()) ids->push_back(rawKeys);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus parseVendorParameters(const ParameterScope& scope,
                                             const std::string& rawKeysAndValues,
                                             std::vector<VendorParameter>* syncParameters,
                                             std::vector<VendorParameter>*) override {
        if (rawKeysAndValues.empty()) return ndk::ScopedAStatus::ok();
        ALOGD("set %s %s: %s", scope.scope == ScopeType::MODULE ? "module" : "stream",
              scope.instanceName.c_str(), rawKeysAndValues.c_str());
        VendorParameter parameter;
        parameter.id = rawKeysAndValues;
        syncParameters->push_back(std::move(parameter));
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus parseBluetoothA2dpReconfigureOffload(
            const std::string&, std::vector<VendorParameter>*) override {
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus parseBluetoothLeReconfigureOffload(
            const std::string&, std::vector<VendorParameter>*) override {
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus processVendorParameters(const ParameterScope&,
                                               const std::vector<VendorParameter>& parameters,
                                               std::string* result) override {
        // For a raw get, the MediaTek HAL answers with the legacy "key=value" reply as the id
        // and only a Boolean success flag in ext.
        for (const auto& parameter : parameters) {
            if (parameter.id.find('=') == std::string::npos) continue;
            if (!result->empty()) result->append(";");
            result->append(parameter.id);
        }
        return ndk::ScopedAStatus::ok();
    }
};

}  // namespace

// Loaded by android.hardware.audio.parameter_parser.service through
// ro.audio.ihaladaptervendorextension_libname.
extern "C" __attribute__((visibility("default"))) int createIHalAdapterVendorExtension() {
    // AServiceManager_addService is a platform API missing from the NDK stubs.
    using AddService = binder_status_t (*)(AIBinder*, const char*);
    auto addService = reinterpret_cast<AddService>(
            dlsym(RTLD_DEFAULT, "AServiceManager_addService"));
    if (addService == nullptr) {
        ALOGE("AServiceManager_addService not found");
        return STATUS_BAD_VALUE;
    }
    static std::shared_ptr<VendorExtension> extension =
            ndk::SharedRefBase::make<VendorExtension>();
    const std::string name = std::string(IHalAdapterVendorExtension::descriptor) + "/default";
    binder_status_t status = addService(extension->asBinder().get(), name.c_str());
    ALOGI("registered %s: %d", name.c_str(), status);
    return status;
}
