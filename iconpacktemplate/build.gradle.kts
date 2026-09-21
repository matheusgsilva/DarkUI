import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import com.android.build.api.artifact.SingleArtifact

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

        repeat(1024) { index ->
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            val rgb = ((index.toLong() * 2654435761L) and 0x00FFFFFFL).toInt()
            val color = 0xFF000000.toInt() or rgb

            for (y in 0 until 2) {
                for (x in 0 until 2) {
                    image.setRGB(x, y, color)
                }
            }

            val file = drawableDir.resolve("icon_%04d.png".format(index))
            check(ImageIO.write(image, "png", file)) {
                "Could not generate $file"
            }
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


val stageTemplateApk = tasks.register<Sync>("stageTemplateApk") {
    into(layout.buildDirectory.dir("stagedTemplate"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        stageTemplateApk.configure {
            dependsOn(variant.assembleProvider)
            from(apkDir) {
                include("*.apk")
                rename { "darkui-template.apk" }
            }
        }
    }
}
