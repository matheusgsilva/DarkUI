package com.matheus.darkui.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.matheus.darkui.model.AppIconItem

class IconExporter(private val context: Context) {
    fun export(items: List<AppIconItem>): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var count = 0
        items.forEach { item ->
            val bitmap = item.generated?.bitmap ?: return@forEach
            val name = (item.app.label + "_" + item.app.packageName)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DarkUI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@forEach
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("Could not open output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                count++
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
            }
        }
        return count
    }
}
