package app.solina.refuge.core.backup

import app.solina.refuge.core.crypto.AuthenticationFailedException
import app.solina.refuge.core.crypto.KdfParams
import app.solina.refuge.core.storage.ItemKind
import app.solina.refuge.core.storage.ItemPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** بکاپ RFGB v2 (با پیوست) و سازگاری عقب‌رو با v1. */
class BackupV2Test {
    private val fast = KdfParams(64, 1, 1)
    private val items = listOf(
        ItemPayload(ItemKind.Login, "GitHub", "ali", "SuperSecret!42", "github.com", createdAt = 5, editedAt = 9),
        ItemPayload(ItemKind.Card, "کارت", cardNumber = "6037997712345678", cardCvv = "123"),
    )
    private val attachments = listOf(
        BackupAttachmentData(0, "file-1", "contract.pdf", "application/pdf", "PDF-BYTES".toByteArray()),
        BackupAttachmentData(0, "file-2", "empty.txt", "text/plain", ByteArray(0)),
        BackupAttachmentData(1, "file-3", "card.jpg", "image/jpeg", byteArrayOf(0, 1, 2, 3, -1)),
    )

    @Test fun v2RoundTrip() {
        val b = Backup.encode(items, "رمز-بکاپ-قوی".toCharArray(), 1L, fast, null, attachments)
        assertEquals(2, Backup.inspect(b).version)
        val decoded = Backup.decodeFull(b, "رمز-بکاپ-قوی".toCharArray())
        assertEquals(items, decoded.items)
        assertEquals(attachments, decoded.attachments)
        assertArrayEquals("PDF-BYTES".toByteArray(), decoded.attachments[0].data)
        assertEquals(0, decoded.attachments[1].data.size)
    }

    @Test fun noAttachmentsStillProducesV1() {
        val b = Backup.encode(items, "رمز-بکاپ-قوی".toCharArray(), 1L, fast)
        assertEquals(1, Backup.inspect(b).version)
        val decoded = Backup.decodeFull(b, "رمز-بکاپ-قوی".toCharArray())
        assertEquals(items, decoded.items)
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test fun v1BackupRestoresExactlyAsBefore() {
        // بکاپ v1 قدیمی (بدون پیوست) با همان رمز باز می‌شود و پیوست ندارد.
        val b = Backup.encode(items, "pw-pw-pw-pw-1".toCharArray(), 1L, fast)
        assertEquals(items, Backup.decode(b, "pw-pw-pw-pw-1".toCharArray()))
        assertTrue(Backup.decodeFull(b, "pw-pw-pw-pw-1".toCharArray()).attachments.isEmpty())
    }

    @Test fun v2WrongPasswordFails() {
        val b = Backup.encode(items, "right-password".toCharArray(), 1L, fast, null, attachments)
        assertThrows(AuthenticationFailedException::class.java) {
            Backup.decodeFull(b, "wrong-password".toCharArray())
        }
    }

    @Test fun v2TamperingFails() {
        val b = Backup.encode(items, "right-password".toCharArray(), 1L, fast, null, attachments)
        val body = b.copyOf()
        body[body.size - 3] = (body[body.size - 3].toInt() xor 1).toByte()
        assertThrows(AuthenticationFailedException::class.java) {
            Backup.decodeFull(body, "right-password".toCharArray())
        }
        // دست‌کاری بایت نسخهٔ هدر (AAD): نسخهٔ ۳ پشتیبانی نمی‌شود.
        val hdr = b.copyOf()
        hdr[4] = 3
        assertThrows(BackupFormatException::class.java) {
            Backup.decodeFull(hdr, "right-password".toCharArray())
        }
    }

    @Test fun v2RejectsAttachmentPointingOutsideItems() {
        val bad = listOf(BackupAttachmentData(5, "file-x", "a.txt", "text/plain", "A".toByteArray()))
        val b = Backup.encode(items, "right-password".toCharArray(), 1L, fast, null, bad)
        assertEquals(2, Backup.inspect(b).version)
        assertThrows(BackupFormatException::class.java) {
            Backup.decodeFull(b, "right-password".toCharArray())
        }
    }

    @Test fun v2KeepsVaultHeader() {
        val header = byteArrayOf(9, 8, 7, 6, 5)
        val b = Backup.encode(items, "right-password".toCharArray(), 1L, fast, header, attachments)
        val decoded = Backup.decodeFull(b, "right-password".toCharArray())
        assertArrayEquals(header, decoded.vaultHeader)
        assertEquals(3, decoded.attachments.size)
    }

    @Test fun v2AttachmentDataNotPlaintextInFile() {
        val b = Backup.encode(items, "pw-pw-pw-pw-1".toCharArray(), 1L, fast, null, attachments)
        val s = String(b, Charsets.ISO_8859_1)
        assertTrue(!s.contains("PDF-BYTES"))
        assertTrue(!s.contains("contract.pdf"))
        assertTrue(!s.contains("GitHub"))
    }
}
