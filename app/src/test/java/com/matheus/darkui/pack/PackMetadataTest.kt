package com.matheus.darkui.pack

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.matheus.darkui.model.AppIconItem
import com.matheus.darkui.model.InstalledApp
import com.matheus.darkui.model.LaunchComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackMetadataTest {
    @Test
    fun resolvesRelativeShortAndFullyQualifiedActivityNames() {
        assertEquals(
            "com.example.app.MainActivity",
            PackMetadata.resolveClassName("com.example.app", ".MainActivity")
        )
        assertEquals(
            "com.example.app.MainActivity",
            PackMetadata.resolveClassName("com.example.app", "MainActivity")
        )
        assertEquals(
            "com.other.Entry",
            PackMetadata.resolveClassName("com.example.app", "com.other.Entry")
        )
    }

    @Test
    fun appFilterMapsEveryLauncherComponentToStableSlotAndEscapesXml() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)

        val app = InstalledApp(
            packageName = "com.example&brand",
            label = "Example",
            versionCode = 1L,
            components = listOf(
                LaunchComponent("com.example&brand", ".MainActivity"),
                LaunchComponent("com.example&brand", "ShareActivity")
            ),
            sourceDrawable = ColorDrawable(Color.BLUE),
            originalBitmap = bitmap
        )

        val xml = PackMetadata.buildAppFilter(listOf(AppIconItem(app)))

        assertTrue(xml.contains("com.example&amp;brand/com.example&amp;brand.MainActivity"))
        assertTrue(xml.contains("com.example&amp;brand/com.example&amp;brand.ShareActivity"))
        assertEquals(2, Regex("""drawable="icon_0000"""").findAll(xml).count())
        assertFalse(xml.contains("com.example&brand/"))
    }

    @Test
    fun drawableListContainsExactlyRequestedSlots() {
        val xml = PackMetadata.buildDrawableList(3)

        assertTrue(xml.contains("icon_0000"))
        assertTrue(xml.contains("icon_0001"))
        assertTrue(xml.contains("icon_0002"))
        assertFalse(xml.contains("icon_0003"))
        assertEquals(3, Regex("""<item drawable="icon_\d{4}" />""").findAll(xml).count())
    }
}
