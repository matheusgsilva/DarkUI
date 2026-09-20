package com.matheus.darkui.pack

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class ThemeParkLauncher(private val context: Context) {
    companion object { const val THEME_PARK_PACKAGE = "com.samsung.android.themedesigner" }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(THEME_PARK_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun launchIntent(): Intent? {
        val direct = context.packageManager.getLaunchIntentForPackage(THEME_PARK_PACKAGE)
        if (direct != null) return direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val candidates = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(THEME_PARK_PACKAGE),
            0
        )
        val activity = candidates.firstOrNull()?.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$THEME_PARK_PACKAGE")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
