plugins {
    id("com.android.application")
}

android {
    namespace = "net.wastu.dashevoxpatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.wastu.dashevoxpatch"
        minSdk = 34
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
