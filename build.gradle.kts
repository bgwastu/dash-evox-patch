plugins {
    id("com.android.application") version "8.9.1" apply false
}

fun gitValue(vararg arguments: String): String = providers.exec {
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

val baseVersion = providers.gradleProperty("baseVersion").get()
val gitCommit = gitValue("rev-parse", "--short=8", "HEAD")
val gitCommitCount = gitValue("rev-list", "--count", "HEAD").toInt()
val buildVersionName = "$baseVersion+$gitCommit"
val buildVersionCode = 1000 + gitCommitCount

fun writeModuleVersion(destinationDir: File) {
    val moduleProp = destinationDir.resolve("module.prop")
    val versionedContent = moduleProp.readText()
        .replace(Regex("(?m)^version=.*$"), "version=$buildVersionName")
        .replace(Regex("(?m)^versionCode=.*$"), "versionCode=$buildVersionCode")
    moduleProp.writeText(versionedContent)
}

val stageKernelSuModule = tasks.register<Sync>("stageKernelSuModule") {
    dependsOn(":app:assembleDebug")
    from(layout.projectDirectory.dir("module"))
    from(layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")) {
        rename { "dash-evox-patch.apk" }
    }
    into(layout.buildDirectory.dir("kernelsu/staged"))
    doLast {
        writeModuleVersion(destinationDir)
    }
}

tasks.register<Zip>("packageKernelSuDebug") {
    dependsOn(stageKernelSuModule)
    from(stageKernelSuModule.map { it.destinationDir })
    archiveFileName.set("dash-evox-patch-kernelsu-$buildVersionName-debug.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/kernelsu"))
}

val stageKernelSuRelease = tasks.register<Sync>("stageKernelSuRelease") {
    dependsOn(":app:assembleRelease")
    from(layout.projectDirectory.dir("module"))
    from(layout.projectDirectory.file("app/build/outputs/apk/release/app-release.apk")) {
        rename { "dash-evox-patch.apk" }
    }
    into(layout.buildDirectory.dir("kernelsu/release-staged"))
    doLast {
        writeModuleVersion(destinationDir)
    }
}

tasks.register<Zip>("packageKernelSuRelease") {
    dependsOn(stageKernelSuRelease)
    from(stageKernelSuRelease.map { it.destinationDir })
    archiveFileName.set("dash-evox-patch-kernelsu-$buildVersionName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/kernelsu"))
}
