package com.matheus.darkui.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.matheus.darkui.model.InstalledApp
import com.matheus.darkui.model.LaunchComponent
import com.matheus.darkui.util.BitmapUtils
import java.text.Collator

class AppScanner(private val context: Context) {
    private val pm = context.packageManager

    fun scan(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        val collator = Collator.getInstance()
        return resolved
            .filter {
                it.activityInfo.packageName != context.packageName &&
                    it.activityInfo.packageName != "com.matheus.darkui.generatedpack"
            }
            .groupBy { it.activityInfo.packageName }
            .mapNotNull { (packageName, activities) ->
                runCatching {
                    val first = activities.first()
                    val label = first.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { packageName }
                    val drawable = first.loadIcon(pm)
                    val bitmap = BitmapUtils.drawableToBitmap(drawable, 192)
                    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(packageName, 0)
                    }
                    val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }

                    val appInfo = first.activityInfo.applicationInfo
                    @Suppress("DEPRECATION")
                    val isGame = appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                        (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0

                    InstalledApp(
                        packageName = packageName,
                        label = label,
                        versionCode = versionCode,
                        components = activities.map {
                            LaunchComponent(it.activityInfo.packageName, it.activityInfo.name)
                        }.distinct(),
                        sourceDrawable = drawable,
                        originalBitmap = bitmap,
                        isGame = isGame
                    )
                }.getOrNull()
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}
