package app.solina.refuge.core.storage

import app.solina.refuge.core.backup.Backup
import app.solina.refuge.core.backup.BackupAttachmentData
import app.solina.refuge.core.backup.DecodedBackup
import app.solina.refuge.core.crypto.AttachmentCrypto
import app.solina.refuge.core.crypto.KdfParams
import app.solina.refuge.core.crypto.randomBytes
import app.solina.refuge.core.security.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** ذخیرهٔ آزمایشی با پشتیبانی پیوست (روی FakeStore). */
class FakeAttachmentStore : FakeStore() {
    val atts = LinkedHashMap<String, StoredAttachment>()
    override suspend fun attachments(itemId: String) =
        atts.values.filter { it.itemId == itemId }.sortedBy { it.addedAt }
    override suspend fun attachment(fileId: String) = atts[fileId]
    override suspend fun allAttachments() = atts.values.toList()
    override suspend fun saveAttachment(attachment: StoredAttachment) { atts[attachment.fileId] = attachment }
    override suspend fun deleteAttachment(fileId: String) { atts.remove(fileId) }
    override suspend fun deleteAttachmentsForItem(itemId: String) { atts.values.removeAll { it.itemId == itemId } }
    override suspend fun wipeAttachments() { atts.clear() }
}

/** پیوست‌های رمزگذاری‌شده: افزودن/بازکردن/حذف، جداسازی ورودی‌ها، و بازیابی بکاپ v2. */
class AttachmentsTest {
    private var t = 1_000L
    private fun unlockedSession() = VaultSession().also { it.unlock(randomBytes(32)) }

    private fun repo(store: FakeAttachmentStore = FakeAttachmentStore(), session: VaultSession = unlockedSession()): VaultRepository {
        var n = 0
        var m = 0
        return VaultRepository(store, session, clock = { t += 10; t }, newId = { "id${++n}" }, newFileId = { "file${++m}" })
    }

    private fun login(title: String) = ItemPayload(title = title, username = "u", password = "Tr0ub4dor&3-x")

    @Test fun addListAndOpen() = runBlocking {
        val r = repo()
        val item = r.add(login("GitHub"))
        val data = "متن پیوست آزمایشی".toByteArray(Charsets.UTF_8)
        val meta = r.addAttachment(item.id, "contract.pdf", "application/pdf", data)
        assertEquals("file1", meta.fileId)
        assertEquals(item.id, meta.itemId)
        assertEquals("contract.pdf", meta.name)
        assertEquals("application/pdf", meta.mime)
        assertEquals(data.size.toLong(), meta.size)
        val list = r.attachments(item.id)
        assertEquals(1, list.size)
        assertEquals(meta, list.single())
        assertArrayEquals(data, r.openAttachment(meta.fileId))
    }

    @Test fun nothingPlaintextInStore() = runBlocking {
        val store = FakeAttachmentStore()
        val r = repo(store)
        val item = r.add(login("SecretTitle"))
        r.addAttachment(item.id, "secret.txt", "text/plain", "TOPSECRETPLAINTEXT".toByteArray())
        val row = store.atts.values.single()
        val raw = String(row.sealedData, Charsets.ISO_8859_1) + String(row.wrappedDek, Charsets.ISO_8859_1)
        assertTrue(!raw.contains("TOPSECRETPLAINTEXT"))
        assertTrue(!raw.contains("secret.txt"))
    }

    @Test fun oversizedAttachmentRejected() {
        val r = repo()
        val item = runBlocking { r.add(login("GitHub")) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { r.addAttachment(item.id, "big.bin", "application/octet-stream", ByteArray(AttachmentCrypto.MAX_ATTACHMENT_BYTES + 1)) }
        }
        assertTrue(runBlocking { r.attachments(item.id) }.isEmpty())
    }

    @Test fun addToUnknownOrDeletedItemRejected() {
        val r = repo()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { r.addAttachment("no-such-id", "a.txt", "text/plain", "x".toByteArray()) }
        }
        val item = runBlocking { r.add(login("GitHub")) }
        runBlocking { r.delete(item.id) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { r.addAttachment(item.id, "a.txt", "text/plain", "x".toByteArray()) }
        }
    }

    /** رگرسیون بازبینی امنیتی: افزودن پیوست در خزانهٔ غیرپیش‌فرض نباید با
     * «item not found» رد شود (قبلاً store.get بدون شناسهٔ خزانه صدا زده می‌شد). */
    @Test fun addAttachmentWorksInNonDefaultVault() = runBlocking {
        val store = FakeAttachmentStore()
        val session = unlockedSession()
        var n = 0
        var m = 0
        val r = VaultRepository(store, session, clock = { t += 10; t }, newId = { "id${++n}" }, newFileId = { "file${++m}" }, vaultId = { "work" })
        val item = r.add(login("GitHub"))
        val meta = r.addAttachment(item.id, "a.txt", "text/plain", "hello".toByteArray())
        assertEquals(item.id, meta.itemId)
        assertArrayEquals("hello".toByteArray(), r.openAttachment(meta.fileId))
    }

    /** پیوستِ خزانهٔ دیگر با شناسهٔ فایلِ شناخته‌شده باز یا حذف نمی‌شود. */
    @Test fun attachmentOfOtherVaultIsNotOpenable() = runBlocking {
        val store = FakeAttachmentStore()
        var n = 0
        var m = 0
        val sessionA = unlockedSession()
        val repoA = VaultRepository(store, sessionA, clock = { t += 10; t }, newId = { "id${++n}" }, newFileId = { "file${++m}" }, vaultId = { "a" })
        val item = repoA.add(login("GitHub"))
        val meta = repoA.addAttachment(item.id, "a.txt", "text/plain", "hello".toByteArray())

        val sessionB = unlockedSession()
        val repoB = VaultRepository(store, sessionB, clock = { t += 10; t }, newId = { "id${++n}" }, newFileId = { "file${++m}" }, vaultId = { "b" })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repoB.openAttachment(meta.fileId) }
        }
        repoB.removeAttachment(meta.fileId)
        // حذفِ خزانهٔ دیگر بی‌اثر است؛ پیوست سر جایش می‌ماند.
        assertArrayEquals("hello".toByteArray(), repoA.openAttachment(meta.fileId))
    }

    @Test fun removeAttachment() = runBlocking {
        val r = repo()
        val item = r.add(login("GitHub"))
        val a = r.addAttachment(item.id, "a.txt", "text/plain", "A".toByteArray())
        r.addAttachment(item.id, "b.txt", "text/plain", "B".toByteArray())
        r.removeAttachment(a.fileId)
        assertEquals(listOf("b.txt"), r.attachments(item.id).map { it.name })
    }

    @Test fun openRemovedAttachmentFails() {
        val r = repo()
        val item = runBlocking { r.add(login("GitHub")) }
        val a = runBlocking { r.addAttachment(item.id, "a.txt", "text/plain", "A".toByteArray()) }
        runBlocking { r.removeAttachment(a.fileId) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { r.openAttachment(a.fileId) }
        }
    }

    @Test fun attachmentsAreIsolatedBetweenItems() = runBlocking {
        val r = repo()
        val a = r.add(login("A"))
        val b = r.add(login("B"))
        val ma = r.addAttachment(a.id, "only-a.txt", "text/plain", "data-A".toByteArray())
        val mb = r.addAttachment(b.id, "only-b.txt", "text/plain", "data-B".toByteArray())
        assertEquals(listOf("only-a.txt"), r.attachments(a.id).map { it.name })
        assertEquals(listOf("only-b.txt"), r.attachments(b.id).map { it.name })
        assertArrayEquals("data-A".toByteArray(), r.openAttachment(ma.fileId))
        assertArrayEquals("data-B".toByteArray(), r.openAttachment(mb.fileId))
        // حذف پیوست ورودی A به B دست نمی‌زند.
        r.removeAttachment(ma.fileId)
        assertTrue(r.attachments(a.id).isEmpty())
        assertEquals(1, r.attachments(b.id).size)
        assertArrayEquals("data-B".toByteArray(), r.openAttachment(mb.fileId))
    }

    @Test fun deleteKeepsAttachmentsButPurgeRemovesThem() = runBlocking {
        val store = FakeAttachmentStore()
        val r = repo(store)
        val item = r.add(login("GitHub"))
        r.addAttachment(item.id, "a.txt", "text/plain", "A".toByteArray())
        r.delete(item.id)
        // سطل زباله: پیوست می‌ماند تا بازگردانی آن را هم برگرداند.
        assertEquals(1, r.attachments(item.id).size)
        val restored = r.restoreTrashed(item.id)!!
        assertEquals(1, r.attachments(restored.id).size)
        // حذف دائمی: پیوست هم می‌رود.
        r.delete(item.id)
        r.purgeTrashed(item.id)
        assertTrue(r.attachments(item.id).isEmpty())
        assertTrue(store.atts.isEmpty())
    }

    @Test fun duplicateDoesNotCopyAttachments() = runBlocking {
        val r = repo()
        val item = r.add(login("GitHub"))
        r.addAttachment(item.id, "a.txt", "text/plain", "A".toByteArray())
        val copy = r.duplicate(item.id)!!
        assertTrue(r.attachments(copy.id).isEmpty())
        assertEquals(1, r.attachments(item.id).size)
    }

    @Test fun importBackupV2RoundTripAcrossVaults() = runBlocking {
        // خزانهٔ مبدأ با کلید خودش؛ خزانهٔ مقصد با کلید دیگر (بازیابی روی دستگاه دیگر).
        val storeA = FakeAttachmentStore()
        val repoA = repo(storeA)
        val itemA = repoA.add(login("GitHub"))
        val itemB = repoA.add(login("Bank"))
        repoA.addAttachment(itemA.id, "a.pdf", "application/pdf", "PDF-DATA-A".toByteArray())
        repoA.addAttachment(itemA.id, "note.txt", "text/plain", "NOTE-A".toByteArray())
        repoA.addAttachment(itemB.id, "b.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4))

        val items = listOf(itemA.payload, itemB.payload)
        val ids = listOf(itemA.id, itemB.id)
        val exported = repoA.exportAttachmentsForBackup(ids)
        val fast = KdfParams(64, 1, 1)
        val bytes = Backup.encode(items, "رمز-بکاپ-قوی".toCharArray(), 1L, fast, null, exported)
        assertEquals(2, Backup.inspect(bytes).version)

        val decoded = Backup.decodeFull(bytes, "رمز-بکاپ-قوی".toCharArray())
        assertEquals(3, decoded.attachments.size)

        val repoB = repo(FakeAttachmentStore(), unlockedSession())
        val stats = repoB.importBackup(decoded)
        assertEquals(2, stats.added)
        assertEquals(3, stats.attachmentsAdded)
        assertEquals(0, stats.attachmentsSkipped)
        val itemsB = repoB.items.value.sortedBy { it.payload.title }
        // جداسازی حفظ شده: GitHub دو پیوست، Bank یک پیوست.
        val gitHub = itemsB.first { it.payload.title == "GitHub" }
        val bank = itemsB.first { it.payload.title == "Bank" }
        assertEquals(listOf("a.pdf", "note.txt"), repoB.attachments(gitHub.id).map { it.name })
        assertEquals(listOf("b.jpg"), repoB.attachments(bank.id).map { it.name })
        val opened = repoB.attachments(gitHub.id).associate { it.name to repoB.openAttachment(it.fileId) }
        assertArrayEquals("PDF-DATA-A".toByteArray(), opened["a.pdf"])
        assertArrayEquals("NOTE-A".toByteArray(), opened["note.txt"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), repoB.openAttachment(repoB.attachments(bank.id).single().fileId))
    }

    @Test fun importBackupV1StillWorksWithoutAttachments() = runBlocking {
        val items = listOf(login("GitHub"), login("Bank"))
        val fast = KdfParams(64, 1, 1)
        val bytes = Backup.encode(items, "رمز-بکاپ-قوی".toCharArray(), 1L, fast)
        assertEquals(1, Backup.inspect(bytes).version)
        val decoded = Backup.decodeFull(bytes, "رمز-بکاپ-قوی".toCharArray())
        assertTrue(decoded.attachments.isEmpty())
        val r = repo()
        val stats = r.importBackup(decoded)
        assertEquals(2, stats.added)
        assertEquals(0, stats.attachmentsAdded)
        assertEquals(2, r.items.value.size)
    }

    @Test fun importBackupTwiceIsIdempotentForAttachments() = runBlocking {
        val storeA = FakeAttachmentStore()
        val repoA = repo(storeA)
        val item = repoA.add(login("GitHub"))
        repoA.addAttachment(item.id, "a.txt", "text/plain", "A".toByteArray())
        val fast = KdfParams(64, 1, 1)
        val bytes = Backup.encode(
            listOf(item.payload), "رمز-بکاپ-قوی".toCharArray(), 1L, fast, null,
            repoA.exportAttachmentsForBackup(listOf(item.id)),
        )
        val decoded = Backup.decodeFull(bytes, "رمز-بکاپ-قوی".toCharArray())
        val repoB = repo(FakeAttachmentStore(), unlockedSession())
        val first = repoB.importBackup(decoded)
        assertEquals(1, first.added)
        assertEquals(1, first.attachmentsAdded)
        // بار دوم: ورودی تکراری است و پیوست هم با همان fileId هست → هر دو رد می‌شوند.
        val second = repoB.importBackup(decoded)
        assertEquals(0, second.added)
        assertEquals(1, second.skipped)
        assertEquals(0, second.attachmentsAdded)
        assertEquals(1, second.attachmentsSkipped)
        assertEquals(1, repoB.items.value.size)
        assertEquals(1, repoB.attachments(repoB.items.value.single().id).size)
    }

    @Test fun importBackupAttachesToExistingDuplicateItem() = runBlocking {
        // ورودی از پیش در خزانهٔ مقصد هست؛ پیوست بکاپ باید به همان ورودی بچسبد.
        val repoB = repo()
        val existing = repoB.add(login("GitHub"))
        val payload = ItemPayload(title = "GitHub", username = "u", password = "Tr0ub4dor&3-x")
        val decoded = DecodedBackup(
            items = listOf(payload),
            vaultHeader = null,
            attachments = listOf(BackupAttachmentData(0, "file-x", "a.txt", "text/plain", "A".toByteArray())),
        )
        val stats = repoB.importBackup(decoded)
        assertEquals(0, stats.added)
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.attachmentsAdded)
        assertEquals(listOf("a.txt"), repoB.attachments(existing.id).map { it.name })
        assertArrayEquals("A".toByteArray(), repoB.openAttachment("file-x"))
    }

    @Test fun exportSkipsUnreadableAttachment() = runBlocking {
        val store = FakeAttachmentStore()
        val r = repo(store)
        val item = r.add(login("GitHub"))
        r.addAttachment(item.id, "good.txt", "text/plain", "good".toByteArray())
        val bad = r.addAttachment(item.id, "bad.txt", "text/plain", "bad".toByteArray())
        // دست‌کاری بلاب در ذخیره: بکاپ نباید به‌خاطر یک پیوست خراب بمیرد.
        val row = store.atts[bad.fileId]!!
        val tampered = row.sealedData.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        store.atts[bad.fileId] = StoredAttachment(row.fileId, row.itemId, row.name, row.mime, row.size, row.addedAt, row.wrappedDek, tampered)
        val exported = r.exportAttachmentsForBackup(listOf(item.id))
        assertEquals(listOf("good.txt"), exported.map { it.name })
    }
}
