import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PrepareTemplateApkTask : DefaultTask() {
    // The APK is produced by :iconpacktemplate:assembleRelease. Gradle 9.x validates
    // @InputFile properties before dependency tasks have produced their outputs, so
    // keeping this as @InputFile makes a clean build fail even though dependsOn is
    // correct. The task verifies the produced file explicitly at execution time.
    @get:Internal
    abstract val templateApkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDir.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        val apkDir = templateApkDir.get().asFile
        val candidates = apkDir
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .toList()

        check(candidates.isNotEmpty()) {
            "Icon-pack template APK was not produced by :iconpacktemplate:assembleRelease. " +
                "No APK was found under $apkDir"
        }

        val source = candidates.singleOrNull()
            ?: candidates.firstOrNull { it.name.contains("unsigned", ignoreCase = true) }
            ?: error(
                "Multiple icon-pack template APKs were produced under $apkDir: " +
                    candidates.joinToString { it.name }
            )

        check(source.length() > 0L) {
            "Icon-pack template APK is empty: $source"
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

val prepareTemplateApk = tasks.register<PrepareTemplateApkTask>("prepareTemplateApk") {
    dependsOn(":iconpacktemplate:assembleRelease")
    templateApkDir.set(
        project(":iconpacktemplate").layout.buildDirectory.dir(
            "outputs/apk/release"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
