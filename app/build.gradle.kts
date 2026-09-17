plugins {
    id("com.android.application")
}

fun gitValue(vararg arguments: String): String = providers.exec {
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

val baseVersion = providers.gradleProperty("baseVersion").get()
val gitCommit = gitValue("rev-parse", "--short=8", "HEAD")
val gitCommitCount = gitValue("rev-list", "--count", "HEAD").toInt()
val buildVersionName = "$baseVersion+$gitCommit"
val buildVersionCode = 1000 + gitCommitCount

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
        versionCode = buildVersionCode
        versionName = buildVersionName
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
