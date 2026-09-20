plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.matheus.darkui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.matheus.darkui"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

val generatedTemplateAssets = layout.buildDirectory.dir("generated/templateAssets")
val prepareTemplateApk by tasks.registering(Copy::class) {
    dependsOn(":iconpacktemplate:assembleRelease")
    val templateApk = project(":iconpacktemplate").layout.buildDirectory.file(
        "outputs/apk/release/iconpacktemplate-release-unsigned.apk"
    )
    from(templateApk)
    into(generatedTemplateAssets)
    rename { "darkui-template.apk" }
}

android.sourceSets["main"].assets.srcDir(generatedTemplateAssets.get().asFile)

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(prepareTemplateApk)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Android port of the APK Signature Scheme implementation used on-device.
    implementation("com.github.MuntashirAkon:apksig-android:4.4.0")

    testImplementation("junit:junit:4.13.2")
}
