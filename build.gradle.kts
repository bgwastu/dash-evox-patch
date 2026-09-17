plugins {
    id("com.android.application") version "8.9.1" apply false
}

val stageKernelSuModule = tasks.register<Sync>("stageKernelSuModule") {
    dependsOn(":app:assembleDebug")
    from(layout.projectDirectory.dir("module"))
    from(layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")) {
        rename { "dash-evox-patch.apk" }
    }
    into(layout.buildDirectory.dir("kernelsu/staged"))
}

tasks.register<Zip>("packageKernelSuDebug") {
    dependsOn(stageKernelSuModule)
    from(stageKernelSuModule.map { it.destinationDir })
    archiveFileName.set("dash-evox-patch-kernelsu-1.5.0-debug.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/kernelsu"))
}

val stageKernelSuRelease = tasks.register<Sync>("stageKernelSuRelease") {
    dependsOn(":app:assembleRelease")
    from(layout.projectDirectory.dir("module"))
    from(layout.projectDirectory.file("app/build/outputs/apk/release/app-release.apk")) {
        rename { "dash-evox-patch.apk" }
    }
    into(layout.buildDirectory.dir("kernelsu/release-staged"))
}

tasks.register<Zip>("packageKernelSuRelease") {
    dependsOn(stageKernelSuRelease)
    from(stageKernelSuRelease.map { it.destinationDir })
    archiveFileName.set("dash-evox-patch-kernelsu-1.5.0.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/kernelsu"))
}
