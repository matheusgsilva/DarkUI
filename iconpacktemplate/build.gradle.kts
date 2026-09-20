import java.util.Base64

plugins {
    id("com.android.application")
}

android {
    namespace = "com.matheus.darkui.generatedpack"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.matheus.darkui.generatedpack"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val slotResDir = layout.buildDirectory.dir("generated/slotRes")
val generateIconSlots by tasks.registering {
    outputs.dir(slotResDir)
    doLast {
        val drawableDir = slotResDir.get().dir("drawable-nodpi").asFile
        drawableDir.mkdirs()
        val transparentPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+5qVqGQAAAABJRU5ErkJggg=="
        )
        repeat(1024) { index ->
            drawableDir.resolve("icon_%04d.png".format(index)).writeBytes(transparentPng)
        }
    }
}

android.sourceSets["main"].res.srcDir(slotResDir.get().asFile)

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Resources")) dependsOn(generateIconSlots)
}
