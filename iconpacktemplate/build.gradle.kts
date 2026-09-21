import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

abstract class GenerateIconSlotsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()

        val drawableDir = root.resolve("drawable-nodpi")
        drawableDir.mkdirs()

        val transparentPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg=="
        )

        repeat(1024) { index ->
            drawableDir
                .resolve("icon_%04d.png".format(index))
                .writeBytes(transparentPng)
        }
    }
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

val generateIconSlots = tasks.register<GenerateIconSlotsTask>("generateIconSlots") {
    outputDir.set(layout.buildDirectory.dir("generated/slotRes"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateIconSlots,
            GenerateIconSlotsTask::outputDir
        )
    }
}
