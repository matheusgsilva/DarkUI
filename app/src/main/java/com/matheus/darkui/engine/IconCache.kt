package com.matheus.darkui.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

class IconCache(context: Context) {
    private val root = File(context.filesDir, "generated-icons/dark").apply { mkdirs() }

    fun get(packageName: String, versionCode: Long): Bitmap? {
        val file = fileFor(packageName, versionCode)
        return if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    fun put(packageName: String, versionCode: Long, bitmap: Bitmap) {
        val file = fileFor(packageName, versionCode)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pruneOld(packageName, keep = file)
    }

    fun clear() = root.parentFile?.deleteRecursively().also { root.mkdirs() }

    private fun fileFor(packageName: String, versionCode: Long): File {
        val raw = "$packageName|$versionCode|dark|${DarkIconEngine.ENGINE_VERSION}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(root, "${safe}_$digest.png")
    }

    private fun pruneOld(packageName: String, keep: File) {
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        keep.parentFile?.listFiles()
            ?.filter { it != keep && it.name.startsWith("${safe}_") }
            ?.forEach { it.delete() }
    }
}
