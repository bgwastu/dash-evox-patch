#!/bin/sh
# Builds libdashvendorext.so. Needs the NDK (ANDROID_NDK_HOME) and the SDK aidl tool (AIDL).
set -e
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
: "${AIDL:?set AIDL to the build-tools aidl binary}"
HOST=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" | head -1)
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"

rm -rf gen out
"$AIDL" --lang=ndk --structured --min_sdk_version=34 --stability=vintf -I aidl \
    -o gen/src -h gen/include aidl/android/hardware/audio/core/VendorParameter.aidl
"$AIDL" --lang=ndk --structured --min_sdk_version=34 -I aidl \
    -o gen/src -h gen/include aidl/android/media/audio/IHalAdapterVendorExtension.aidl

# Out-array size prefixes are optional for us: newer clients omit them, and the stub would fail
# with STATUS_NOT_ENOUGH_DATA before reaching the implementation.
STUB=gen/src/android/media/audio/IHalAdapterVendorExtension.cpp
perl -0pi -e 's/(_aidl_ret_status = ::ndk::AParcel_resizeVector\(_aidl_in, &out_\w+\);\n\s*)if \(_aidl_ret_status != STATUS_OK\) break;/$1_aidl_ret_status = STATUS_OK;/g' "$STUB"
grep -q "AParcel_resizeVector" "$STUB" && ! grep -A1 "AParcel_resizeVector" "$STUB" | grep -q "break;" || {
  echo "stub patch did not apply" >&2
  exit 1
}

mkdir -p out
"$BIN/aarch64-linux-android34-clang++" -std=c++20 -O2 -fPIC -shared -fvisibility=hidden \
    -Ithird_party/libbinder_ndk -Igen/include \
    src/VendorExtension.cpp \
    gen/src/android/media/audio/IHalAdapterVendorExtension.cpp \
    gen/src/android/hardware/audio/core/VendorParameter.cpp \
    -static-libstdc++ -lbinder_ndk -llog -ldl \
    -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
    -o out/libdashvendorext.so
"$BIN/llvm-strip" --strip-unneeded out/libdashvendorext.so
