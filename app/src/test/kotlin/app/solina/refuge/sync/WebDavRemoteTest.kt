package app.solina.refuge.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** سقف خواندن پاسخ WebDAV: سرور به‌خطرافتاده نمی‌تواند با پاسخ غول‌پیکر حافظه را پر کند. */
class WebDavRemoteTest {

    @Test fun readCappedReturnsSmallInput() {
        val data = "hello".toByteArray()
        val out = WebDavRemote.readCapped(ByteArrayInputStream(data))
        assertArrayEquals(data, out)
    }

    @Test fun readCappedAcceptsExactlyAtCap() {
        val data = ByteArray(64) { it.toByte() }
        val out = WebDavRemote.readCapped(ByteArrayInputStream(data), 64)
        assertArrayEquals(data, out)
    }

    @Test fun readCappedRejectsOversizedInput() {
        val data = ByteArray(65) { it.toByte() }
        assertNull(WebDavRemote.readCapped(ByteArrayInputStream(data), 64))
    }

    @Test fun readCappedDefaultCapIsEightMegabytes() {
        assertEquals(8 * 1024 * 1024, WebDavRemote.MAX_READ_BYTES)
    }
}
