package com.matheus.darkui.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

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
    val originalBitmap: Bitmap,
    val isGame: Boolean = false
)

data class SmartIconResult(
    val bitmap: Bitmap,
    val method: String,
    val confidence: Float
)

data class AppIconItem(
    val app: InstalledApp,
    val generated: SmartIconResult? = null
)
