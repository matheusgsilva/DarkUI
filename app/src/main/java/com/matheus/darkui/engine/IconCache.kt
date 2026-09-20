package com.matheus.darkui.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.matheus.darkui.model.IconStyle
import java.io.File
import java.security.MessageDigest

class IconCache(context: Context) {
    private val root = File(context.filesDir, "generated-icons").apply { mkdirs() }

    fun get(packageName: String, versionCode: Long, style: IconStyle): Bitmap? {
        val file = fileFor(packageName, versionCode, style)
        return if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    fun put(packageName: String, versionCode: Long, style: IconStyle, bitmap: Bitmap) {
        val file = fileFor(packageName, versionCode, style)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pruneOld(packageName, keep = file)
    }

    fun clear() = root.deleteRecursively().also { root.mkdirs() }

    private fun fileFor(packageName: String, versionCode: Long, style: IconStyle): File {
        val raw = "$packageName|$versionCode|${style.name}|${DarkIconEngine.ENGINE_VERSION}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(root, "${style.name.lowercase()}/${safe}_$digest.png")
    }

    private fun pruneOld(packageName: String, keep: File) {
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        keep.parentFile?.listFiles()?.filter { it != keep && it.name.startsWith("${safe}_") }?.forEach { it.delete() }
    }
}
