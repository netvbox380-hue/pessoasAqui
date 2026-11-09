package br.com.pessoasaqui.core.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Movie
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateGifStudioInstrumentedTest {
    @Test fun encodedGifDecodesAndRemainsPrivate() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val studio = PrivateGifStudio(ctx)
        val red = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = studio.save(listOf(red, blue), 180, "Olá")
        try {
            assertTrue(file.exists())
            assertTrue(file.length() <= PrivateGifStudio.MAX_GIF_BYTES)
            assertEquals("private_gif_studio", file.parentFile?.name)
            val bytes = file.readBytes()
            assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
            val decoded = Movie.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(decoded)
            assertEquals(240, decoded!!.width())
            assertEquals(240, decoded.height())
            assertTrue(decoded.duration() >= 300)
            assertNotNull(studio.payload(file))
            assertNotNull(studio.thumbnail(file))
        } finally {
            assertTrue(studio.delete(file))
        }
    }

    @Test fun oneFrameIsRejected() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val studio = PrivateGifStudio(ctx)
        val frame = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        try {
            studio.save(listOf(frame), 180, "")
            fail("One frame must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
