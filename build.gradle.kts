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
    archiveFileName.set("dash-evox-patch-kernelsu-1.4.0-debug.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/kernelsu"))
}
