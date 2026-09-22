import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


abstract class DownloadAiModelTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val destination = outputDir.get().asFile
        destination.mkdirs()

        val modelFile = destination.resolve("u2netp.onnx")
        if (modelFile.isFile && modelFile.length() > 4_000_000L) {
            return
        }

        val temp = destination.resolve("u2netp.onnx.part")
        temp.delete()

        URL(
            "https://raw.githubusercontent.com/ChiangyangNPU/MattingDemo-Android/" +
                "636044e100bcc5bfc2af0a7d097eeed8e28bdc2a/" +
                "app/src/main/assets/u2netp.onnx"
        ).openStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        check(temp.length() > 4_000_000L) {
            "Downloaded U2NetP model is unexpectedly small: ${temp.length()} bytes"
        }

        temp.copyTo(modelFile, overwrite = true)
        temp.delete()
    }
}


abstract class PrepareTemplateApkTask : DefaultTask() {
    // The APK is produced by :iconpacktemplate:assembleRelease. Gradle 9.x validates
    // @InputFile properties before dependency tasks have produced their outputs, so
    // keeping this as @InputFile makes a clean build fail even though dependsOn is
    // correct. The task verifies the produced file explicitly at execution time.
    @get:Internal
    abstract val stagedTemplateDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDir.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        val source = stagedTemplateDir.get().asFile.resolve("darkui-template.apk")
        check(source.isFile && source.length() > 0L) {
            "Staged icon-pack template APK is missing or empty: $source"
        }

        source.copyTo(
            destination.resolve("darkui-template.apk"),
            overwrite = true
        )
    }
}

android {
    namespace = "com.matheus.darkui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.matheus.darkui"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES"
        )
    }
}


val downloadAiModel = tasks.register<DownloadAiModelTask>("downloadAiModel") {
    outputDir.set(layout.buildDirectory.dir("generated/aiAssets"))
}


val prepareTemplateApk = tasks.register<PrepareTemplateApkTask>("prepareTemplateApk") {
    dependsOn(":iconpacktemplate:stageTemplateApk")
    stagedTemplateDir.set(
        project(":iconpacktemplate").layout.buildDirectory.dir(
            "stagedTemplate"
        )
    )
    outputDir.set(layout.buildDirectory.dir("generated/templateAssets"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareTemplateApk,
            PrepareTemplateApkTask::outputDir
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadAiModel,
            DownloadAiModelTask::outputDir
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.github.MuntashirAkon:apksig-android:4.4.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
