package com.matheus.darkui.pack

import android.content.Context
import android.graphics.Bitmap
import com.matheus.darkui.model.AppIconItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GeneratedPackBuilder(
    private val context: Context,
    private val signer: ApkSignerService = ApkSignerService()
) {
    companion object {
        const val MAX_ICONS = 1024
        private val SLOT_REGEX = Regex("^res/.*/icon_(\\d{4})\\.png$")
        private val REPLACED_ASSETS = setOf(
            "assets/appfilter.xml",
            "assets/drawable.xml",
            "assets/icon_pack.xml"
        )
    }

    data class BuildResult(val apk: File, val iconCount: Int, val componentCount: Int)

    fun build(items: List<AppIconItem>): BuildResult {
        val usable = items.filter { it.generated != null }.take(MAX_ICONS)
        require(usable.isNotEmpty()) { "No generated icons available" }

        val outDir = File(context.cacheDir, "generated-apks").apply { mkdirs() }
        val unsigned = File(outDir, "darkui-generated-unsigned.apk")
        val signed = File(outDir, "darkui-generated.apk")
        val generatedPngs = usable.map { item -> bitmapPng(item.generated!!.bitmap) }

        context.assets.open("darkui-template.apk").use { templateStream ->
            ZipInputStream(templateStream.buffered()).use { zin ->
                ZipOutputStream(unsigned.outputStream().buffered()).use { zout ->
                    var slotCount = 0
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val bytes = zin.readBytes()
                        when {
                            name.startsWith("META-INF/") -> Unit
                            name in REPLACED_ASSETS -> Unit
                            else -> {
                                val match = SLOT_REGEX.matchEntire(name)
                                if (match != null) {
                                    val index = match.groupValues[1].toInt()
                                    if (index < generatedPngs.size) {
                                        writeEntry(zout, name, generatedPngs[index], ZipEntry.DEFLATED)
                                        slotCount++
                                    } else {
                                        writeEntry(zout, name, bytes, entry.method)
                                    }
                                } else {
                                    writeEntry(zout, name, bytes, entry.method)
                                }
                            }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                    check(slotCount >= generatedPngs.size) {
                        "Template contains only $slotCount usable icon slots for ${generatedPngs.size} icons"
                    }
                    writeEntry(zout, "assets/appfilter.xml", buildAppFilter(usable).toByteArray(), ZipEntry.DEFLATED)
                    writeEntry(zout, "assets/drawable.xml", buildDrawableList(usable.size).toByteArray(), ZipEntry.DEFLATED)
                    writeEntry(zout, "assets/icon_pack.xml", buildDrawableList(usable.size).toByteArray(), ZipEntry.DEFLATED)
                }
            }
        }

        signer.sign(unsigned, signed)
        unsigned.delete()
        val components = usable.sumOf { it.app.components.size }
        return BuildResult(signed, usable.size, components)
    }

    private fun bitmapPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        out.toByteArray()
    }

    private fun buildAppFilter(items: List<AppIconItem>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        items.forEachIndexed { index, item ->
            val drawable = "icon_%04d".format(index)
            item.app.components.forEach { component ->
                val cls = if (component.className.startsWith('.')) component.packageName + component.className else component.className
                append("    <item component=\"ComponentInfo{")
                append(xml(component.packageName)).append('/').append(xml(cls))
                append("}\" drawable=\"").append(drawable).append("\" />\n")
            }
        }
        append("</resources>\n")
    }

    private fun buildDrawableList(count: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        append("    <category title=\"DarkUI Generated\" />\n")
        repeat(count) { index -> append("    <item drawable=\"icon_%04d\" />\n".format(index)) }
        append("</resources>\n")
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")

    private fun writeEntry(zout: ZipOutputStream, name: String, bytes: ByteArray, originalMethod: Int) {
        val entry = ZipEntry(name).apply {
            time = 0L
            method = if (originalMethod == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            if (method == ZipEntry.STORED) {
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                val crc = java.util.zip.CRC32().apply { update(bytes) }
                this.crc = crc.value
            }
        }
        zout.putNextEntry(entry)
        zout.write(bytes)
        zout.closeEntry()
    }
}
