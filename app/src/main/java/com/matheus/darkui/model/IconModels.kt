package com.matheus.darkui.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

enum class IconStyle(val title: String) {
    DARK("Dark"),
    AMOLED("AMOLED"),
    TINTED("Tinted")
}

enum class AppIconMode(val title: String) {
    AUTO("Auto"),
    DARK("Dark"),
    AMOLED("AMOLED"),
    TINTED("Tinted"),
    ORIGINAL("Original");

    fun next(): AppIconMode = entries[(ordinal + 1) % entries.size]
}

data class LaunchComponent(
    val packageName: String,
    val className: String
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val components: List<LaunchComponent>,
    val sourceDrawable: Drawable,
    val originalBitmap: Bitmap
)

data class SmartIconResult(
    val bitmap: Bitmap,
    val method: String,
    val confidence: Float
)

data class AppIconItem(
    val app: InstalledApp,
    val generated: SmartIconResult? = null,
    val mode: AppIconMode = AppIconMode.AUTO
)
