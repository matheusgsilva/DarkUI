package com.matheus.darkui.pack

import android.content.Context
import android.graphics.Bitmap
import com.android.apksig.ApkVerifier
import com.matheus.darkui.model.AppIconItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
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

    data class BuildResult(
        val apk: File,
        val iconCount: Int,
        val componentCount: Int
    )

    fun build(items: List<AppIconItem>): BuildResult {
        val generated = items.filter { it.generated != null }
        require(generated.isNotEmpty()) { "No generated icons available" }
        require(generated.size <= MAX_ICONS) {
            "DarkUI supports up to $MAX_ICONS icons per pack; found ${generated.size}"
        }

        val outDir = File(context.cacheDir, "generated-apks").apply { mkdirs() }
        val unsigned = File(outDir, "darkui-generated-unsigned.apk")
        val signed = File(outDir, "darkui-generated.apk")

        unsigned.delete()
        signed.delete()

        val generatedPngs = generated.map { item ->
            bitmapPng(requireNotNull(item.generated).bitmap)
        }

        context.assets.open("darkui-template.apk").use { templateStream ->
            ZipInputStream(templateStream.buffered()).use { zin ->
                val counting = CountingOutputStream(unsigned.outputStream().buffered())

                ZipOutputStream(counting).use { zout ->
                    var replacedSlots = 0
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
                                        writeEntry(
                                            zout = zout,
                                            counter = counting,
                                            name = name,
                                            bytes = generatedPngs[index],
                                            originalMethod = ZipEntry.DEFLATED
                                        )
                                        replacedSlots++
                                    } else {
                                        writeEntry(
                                            zout,
                                            counting,
                                            name,
                                            bytes,
                                            entry.method
                                        )
                                    }
                                } else {
                                    writeEntry(
                                        zout,
                                        counting,
                                        name,
                                        bytes,
                                        entry.method
                                    )
                                }
                            }
                        }

                        zin.closeEntry()
                        entry = zin.nextEntry
                    }

                    check(replacedSlots == generatedPngs.size) {
                        "Template has $replacedSlots usable slots for ${generatedPngs.size} icons"
                    }

                    writeEntry(
                        zout,
                        counting,
                        "assets/appfilter.xml",
                        PackMetadata.buildAppFilter(generated).toByteArray(),
                        ZipEntry.DEFLATED
                    )
                    writeEntry(
                        zout,
                        counting,
                        "assets/drawable.xml",
                        PackMetadata.buildDrawableList(generated.size).toByteArray(),
                        ZipEntry.DEFLATED
                    )
                    writeEntry(
                        zout,
                        counting,
                        "assets/icon_pack.xml",
                        PackMetadata.buildDrawableList(generated.size).toByteArray(),
                        ZipEntry.DEFLATED
                    )
                }
            }
        }

        check(unsigned.isFile && unsigned.length() > 0L) {
            "Generated unsigned pack is empty"
        }

        signer.sign(unsigned, signed)
        unsigned.delete()

        check(signed.isFile && signed.length() > 0L) {
            "Signed icon pack was not created"
        }

        val verification = ApkVerifier.Builder(signed).build().verify()
        check(verification.isVerified) {
            "Generated icon pack signature verification failed: " +
                verification.errors.joinToString()
        }

        val components = generated.sumOf { it.app.components.size }

        return BuildResult(
            apk = signed,
            iconCount = generated.size,
            componentCount = components
        )
    }

    private fun bitmapPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Could not encode generated icon"
            }
            out.toByteArray()
        }

    private fun writeEntry(
        zout: ZipOutputStream,
        counter: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        originalMethod: Int
    ) {
        val method =
            if (originalMethod == ZipEntry.STORED) ZipEntry.STORED
            else ZipEntry.DEFLATED

        val entry = ZipEntry(name).apply {
            time = 0L
            this.method = method

            if (method == ZipEntry.STORED) {
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
                extra = alignmentExtra(
                    currentOffset = counter.count,
                    name = name,
                    alignment = 4
                )
            }
        }

        zout.putNextEntry(entry)
        zout.write(bytes)
        zout.closeEntry()
    }

    /**
     * Android expects uncompressed APK entries such as resources.arsc to start on
     * a 4-byte boundary. ZipOutputStream doesn't do this automatically, so add a
     * valid ZIP extra field that provides the necessary padding before signing.
     */
    private fun alignmentExtra(
        currentOffset: Long,
        name: String,
        alignment: Int
    ): ByteArray {
        val localHeaderSize = 30L
        val nameSize = name.toByteArray(Charsets.UTF_8).size.toLong()
        val base = currentOffset + localHeaderSize + nameSize

        var payloadSize = 0
        while ((base + 4L + payloadSize) % alignment != 0L) {
            payloadSize++
        }

        return ByteArray(4 + payloadSize).apply {
            this[0] = 0xFE.toByte()
            this[1] = 0xCA.toByte()
            this[2] = (payloadSize and 0xFF).toByte()
            this[3] = ((payloadSize ushr 8) and 0xFF).toByte()
        }
    }

    private class CountingOutputStream(
        output: OutputStream
    ) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int
        ) {
            out.write(b, off, len)
            count += len
        }
    }
}
