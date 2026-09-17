plugins {
    id("com.android.application")
}

android {
    namespace = "net.wastu.dashevoxpatch"
    compileSdk = 34

    val signingStore = System.getenv("DASH_SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("DASH_SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("DASH_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("DASH_SIGNING_KEY_PASSWORD")

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "net.wastu.dashevoxpatch"
        minSdk = 34
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
