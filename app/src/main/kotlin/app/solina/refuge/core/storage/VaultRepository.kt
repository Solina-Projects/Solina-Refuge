package app.solina.refuge.core.storage

import app.solina.refuge.core.backup.BackupAttachmentData
import app.solina.refuge.core.backup.DecodedBackup
import app.solina.refuge.core.crypto.AttachmentCrypto
import app.solina.refuge.core.crypto.MultiVaultDesign
import app.solina.refuge.core.crypto.SealedItem
import app.solina.refuge.core.crypto.VaultCrypto
import app.solina.refuge.core.crypto.wipe
import app.solina.refuge.core.security.VaultSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ImportStats(val added: Int, val skipped: Int, val attachmentsAdded: Int = 0, val attachmentsSkipped: Int = 0)

class VaultLockedException : IllegalStateException("vault is locked")

/**
 * منطق خزانه: رمزگذاری/رمزگشایی ورودی‌ها با Vault Key نشست، نگه‌داشتن فهرست در حافظه و تاریخچه.
 * فهرست رمزگشایی‌شده فقط در حافظه است و با [clear] (هنگام قفل) پاک می‌شود.
 */
class VaultRepository(
    private val store: ItemStore,
    private val session: VaultSession,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newFileId: () -> String = { UUID.randomUUID().toString() },
    /** شناسهٔ خزانهٔ فعال؛ همهٔ خواندن‌ها/نوشتن‌ها به همین خزانه محدود می‌شوند. */
    private val vaultId: () -> String = { MultiVaultDesign.DEFAULT_VAULT_ID },
) {
    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    /** تغییرات محلی را با همگام‌سازی سریالی می‌کند (برای جلوگیری از «به‌روزرسانی گم‌شده»). */
    val lock = Mutex()

    private val _localChanges = MutableStateFlow(0L)
    /** با هر افزودن/ویرایش/حذف/ایمپورت محلی بالا می‌رود؛ برای راه‌اندازی همگام‌سازی خودکار. */
    val localChanges: StateFlow<Long> = _localChanges.asStateFlow()

    private val _unreadable = MutableStateFlow(0)
    /** تعداد ردیف‌هایی که رمزگشایی نشدند (خراب یا دستکاری‌شده). */
    val unreadable: StateFlow<Int> = _unreadable.asStateFlow()

    private inline fun <T> withKey(block: (ByteArray) -> T): T {
        val key = session.keyCopy() ?: throw VaultLockedException()
        try {
            return block(key)
        } finally {
            key.wipe()
        }
    }

    suspend fun load() {
        val rows = store.all(vaultId()).filter { it.deletedAt == null }
        var bad = 0
        val list = ArrayList<VaultItem>(rows.size)
        withKey { key ->
            for (r in rows) {
                try {
                    val plain = VaultCrypto.openItem(key, r.id, r.rev, ItemPayload.SCHEMA, SealedItem(r.wrappedItemKey, r.blob))
                    try {
                        list.add(VaultItem(r.id, r.rev, ItemPayload.fromBytes(plain)))
                    } finally {
                        plain.wipe()
                    }
                } catch (e: Exception) {
                    bad++
                }
            }
        }
        _unreadable.value = bad
        _items.value = list.sortedBy { it.payload.title.lowercase() }
        // آشتی فرادادهٔ متن‌باز موعد کارها (فاز ۳): کارهایی که از بکاپ/همگام‌سازی آمده‌اند
        // از مسیر Repository رد نشده‌اند، پس اینجا موعدشان ثبت و موعدهای کهنه پاک می‌شود.
        // فقط شناسه + زمان موعد ذخیره می‌شود؛ هیچ محتوایی.
        runCatching { reconcileTaskDues(list) }
    }

    /** موعدهای متن‌باز را با فهرست فعلی کارها هم‌خوان می‌کند (پس از load یا همگام‌سازی). */
    private suspend fun reconcileTaskDues(list: List<VaultItem>) {
        val existing = store.allTaskDues(vaultId()).associateBy { it.id }
        val pending = list.filter { it.payload.kind == ItemKind.Task && !it.payload.done && it.payload.dueAt > 0 }
        for (item in pending) {
            val old = existing[item.id]
            if (old == null || old.dueAt != item.payload.dueAt) {
                store.saveTaskDue(item.id, item.payload.dueAt, 0L, vaultId())
            }
        }
        for (meta in existing.values) {
            if (pending.none { it.id == meta.id }) store.deleteTaskDue(meta.id, vaultId())
        }
    }

    /** همهٔ موعدهای متن‌باز (برای یادآور پس‌زمینه؛ فقط شناسه + زمان، بدون محتوا). */
    suspend fun taskDues(): List<TaskDueMeta> = store.allTaskDues(vaultId())

    /** ثبت اینکه برای این کار یادآوری فرستاده شد (تا دوباره تکرار نشود). */
    suspend fun markTaskNotified(id: String, at: Long) {
        // عمداً از فهرست حافظه خوانده نمی‌شود: وقتی خزانه قفل است آن فهرست خالی است،
        // ولی یادآور دقیقاً در همان حالت باید بتواند علامت بزند.
        val due = store.allTaskDues(vaultId()).firstOrNull { it.id == id } ?: return
        store.saveTaskDue(id, due.dueAt, at, vaultId())
    }

    /** هم‌خوان‌کردن یک ورودی با جدول موعد: فقط کار ناتمامِ دارای موعد ردیف دارد. */
    private suspend fun syncTaskDue(item: VaultItem) {
        val p = item.payload
        if (p.kind == ItemKind.Task && !p.done && p.dueAt > 0) {
            store.saveTaskDue(item.id, p.dueAt, 0L, vaultId())
        } else {
            store.deleteTaskDue(item.id, vaultId())
        }
    }

    suspend fun add(payload: ItemPayload): VaultItem = lock.withLock {
        val now = clock()
        val id = newId()
        val p = payload.copy(createdAt = now, editedAt = now)
        val item = VaultItem(id, 1, p)
        withKey { key -> store.save(seal(key, item, now), null, MAX_HISTORY) }
        syncTaskDue(item)
        publish(item)
        _localChanges.value++
        item
    }

    /** اگر چیزی تغییر نکرده باشد چیزی ذخیره نمی‌شود. */
    suspend fun update(id: String, payload: ItemPayload): VaultItem = lock.withLock {
        val current = _items.value.firstOrNull { it.id == id } ?: error("item not found")
        val now = clock()
        val next = payload.copy(createdAt = current.payload.createdAt, editedAt = current.payload.editedAt)
        if (next == current.payload) return@withLock current
        val item = VaultItem(id, current.rev + 1, next.copy(editedAt = now))
        withKey { key ->
            val old = store.get(id, vaultId()) ?: error("item not found")
            val archived = StoredHistory(id, old.rev, now, old.wrappedItemKey, old.blob, vaultId())
            store.save(seal(key, item, now), archived, MAX_HISTORY)
        }
        syncTaskDue(item)
        publish(item)
        _localChanges.value++
        item
    }

    private fun fingerprint(p: ItemPayload) =
        listOf(
            p.kind.code, p.title, p.username, p.password, p.website,
            p.totpSecret, p.otpType.code, p.cardNumber, p.bankName,
            p.fullName, p.email, p.phone, p.nationalId, p.category, p.tags.joinToString(","), p.note,
            p.body, p.checklist.joinToString(",") { "${it.text}:${it.done}" },
            p.priority.code, p.dueAt.toString(), p.repeat.code, p.done.toString(), p.linkedItemId, p.linkedNoteId,
        ).joinToString("\u0001")

    /**
     * افزودن دسته‌ای (ایمپورت/بازیابی). ورودی‌ای که عیناً مشابه موجود باشد رد می‌شود؛ تاریخ‌های اصلی حفظ می‌شوند.
     */
    suspend fun importItems(list: List<ItemPayload>): ImportStats = lock.withLock {
        importItemsLocked(list).first
    }

    /**
     * بازیابی کامل بکاپ (v1 یا v2) شامل پیوست‌ها. ورودی‌ها مثل [importItems] وارد
     * می‌شوند؛ پیوست هر ورودی به همان ورودی می‌چسبد — چه تازه ساخته شده باشد چه
     * تکراری و از پیش موجود. دادهٔ پیوست با کلید خزانهٔ فعلی دوباره بسته می‌شود،
     * پس بازیابی روی خزانهٔ دیگر هم کار می‌کند. پیوستی که شناسهٔ فایلش از پیش
     * هست رد می‌شود (بازیابی دوبارهٔ همان بکاپ، تکراری نمی‌سازد).
     */
    suspend fun importBackup(decoded: DecodedBackup): ImportStats = lock.withLock {
        val (stats, idByIndex) = importItemsLocked(decoded.items)
        var attsAdded = 0
        var attsSkipped = 0
        if (decoded.attachments.isNotEmpty()) {
            withKey { key ->
                for (a in decoded.attachments) {
                    val targetId = idByIndex[a.itemIndex]
                    if (targetId == null) { attsSkipped++; continue }
                    if (store.attachment(a.fileId) != null) { attsSkipped++; continue }
                    try {
                        val sealed = AttachmentCrypto.seal(key, a.fileId, a.data)
                        store.saveAttachment(
                            StoredAttachment(
                                a.fileId, targetId, a.name, a.mime,
                                a.data.size.toLong(), clock(), sealed.wrappedDek, sealed.sealedData,
                            ),
                        )
                        attsAdded++
                    } catch (e: Exception) {
                        // پیوست خراب یا بیش از حد بزرگ: فقط همین یکی رد می‌شود، نه کل بازیابی.
                        attsSkipped++
                    }
                }
            }
            if (attsAdded > 0) _localChanges.value++
        }
        ImportStats(stats.added, stats.skipped, attsAdded, attsSkipped)
    }

    /**
     * منطق مشترک ایمپورت ورودی‌ها؛ با قفلِ گرفته‌شده صدا زده می‌شود.
     * برمی‌گرداند: آمار ایمپورت + نگاشت «شمارهٔ ردیف در فهرست ورودی ← شناسهٔ ورودی»
     * (برای چسباندن پیوست‌های بکاپ v2 به ورودیِ درست، حتی وقتی ورودی تکراری است).
     */
    private suspend fun importItemsLocked(list: List<ItemPayload>): Pair<ImportStats, Map<Int, String>> {
        val fingerprintToId = _items.value.associateBy({ fingerprint(it.payload) }, { it.id }).toMutableMap()
        val now = clock()
        val created = ArrayList<VaultItem>()
        val idByIndex = HashMap<Int, String>()
        var skipped = 0
        withKey { key ->
            for ((index, p) in list.withIndex()) {
                val fp = fingerprint(p)
                val existingId = fingerprintToId[fp]
                if (existingId != null) {
                    skipped++
                    idByIndex[index] = existingId
                    continue
                }
                val c = if (p.createdAt > 0) p.createdAt else now
                val e = if (p.editedAt > 0) p.editedAt else c
                val item = VaultItem(newId(), 1, p.copy(createdAt = c, editedAt = e))
                store.save(seal(key, item, now), null, MAX_HISTORY)
                syncTaskDue(item)
                fingerprintToId[fp] = item.id
                idByIndex[index] = item.id
                created += item
            }
        }
        _items.value = (_items.value + created).sortedBy { it.payload.title.lowercase() }
        if (created.isNotEmpty()) _localChanges.value++
        return ImportStats(created.size, skipped) to idByIndex
    }

    // ── پیوست‌های رمزگذاری‌شده (فاز ۴) ──

    /** فرادادهٔ پیوست‌های یک ورودی (بدون باز کردن محتوای رمز). */
    suspend fun attachments(itemId: String): List<AttachmentMeta> =
        store.attachments(itemId).map { AttachmentMeta(it.fileId, it.itemId, it.name, it.mime, it.size, it.addedAt) }

    /**
     * افزودن پیوست به یک ورودیِ زنده. فایل با DEK تصادفیِ خودش بسته می‌شود
     * (AttachmentCrypto) و فقط بلابِ بسته‌شده ذخیره می‌شود.
     * @throws IllegalArgumentException ورودی نباشد، حذف‌شده باشد یا فایل بیش از ۱۰ مگابایت باشد.
     */
    suspend fun addAttachment(itemId: String, name: String, mime: String, data: ByteArray): AttachmentMeta = lock.withLock {
        // شناسهٔ خزانه حتماً داده می‌شود؛ پیش‌فرضِ «default» در نسخهٔ قبل باعث می‌شد
        // افزودن پیوست در خزانه‌های غیرپیش‌فرض همیشه با «item not found» رد شود.
        val row = store.get(itemId, vaultId()) ?: throw IllegalArgumentException("item not found")
        if (row.deletedAt != null) throw IllegalArgumentException("item is deleted")
        val cleanName = name.trim().take(MAX_ATTACHMENT_NAME).ifBlank { "file" }
        val cleanMime = mime.trim().take(MAX_ATTACHMENT_MIME).ifBlank { "application/octet-stream" }
        val fileId = newFileId()
        val now = clock()
        val meta = withKey { key ->
            val sealed = AttachmentCrypto.seal(key, fileId, data)
            store.saveAttachment(StoredAttachment(fileId, itemId, cleanName, cleanMime, data.size.toLong(), now, sealed.wrappedDek, sealed.sealedData))
            AttachmentMeta(fileId, itemId, cleanName, cleanMime, data.size.toLong(), now)
        }
        _localChanges.value++
        meta
    }

    /**
     * باز کردن محتوای یک پیوست. بایت‌های برگشتی متنِ اصلیِ فایل‌اند؛ فراخواننده
     * پس از مصرف باید آن‌ها را پاک کند ([wipe]).
     * @throws IllegalArgumentException پیوست نباشد؛ @throws AuthenticationFailedException دست‌کاری یا کلید اشتباه.
     */
    suspend fun openAttachment(fileId: String): ByteArray {
        val row = store.attachment(fileId) ?: throw IllegalArgumentException("attachment not found")
        // پیوست‌ها ستون خزانه ندارند؛ مالکیت از راه ورودیِ صاحب بررسی می‌شود تا پیوستِ
        // خزانهٔ دیگر (با شناسهٔ فایلِ شناخته‌شده) با کلید این خزانه باز نشود.
        if (store.get(row.itemId, vaultId()) == null) throw IllegalArgumentException("attachment not found")
        return withKey { key ->
            AttachmentCrypto.open(key, row.fileId, AttachmentCrypto.SealedAttachment(row.wrappedDek, row.sealedData))
        }
    }

    /** حذف یک پیوست (فقط همان فایل؛ ورودی دست نمی‌خورد). */
    suspend fun removeAttachment(fileId: String) = lock.withLock {
        val row = store.attachment(fileId)
        if (row != null && store.get(row.itemId, vaultId()) != null) {
            store.deleteAttachment(fileId)
            _localChanges.value++
        }
    }

    /**
     * همهٔ پیوست‌های ورودی‌ها برای بکاپ v2: دادهٔ رمزگشایی‌شدهٔ هر پیوست به‌همراه
     * شمارهٔ ردیف ورودیِ صاحبش. پیوستِ خراب (باز نمی‌شود) رد می‌شود تا کل بکاپ خراب نشود.
     * فراخواننده پس از [app.solina.refuge.core.backup.Backup.encode] باید
     * بایت‌های `data` را پاک کند ([wipe]).
     */
    suspend fun exportAttachmentsForBackup(itemIds: List<String>): List<BackupAttachmentData> = withKey { key ->
        val out = ArrayList<BackupAttachmentData>()
        for ((index, itemId) in itemIds.withIndex()) {
            for (row in store.attachments(itemId)) {
                try {
                    val data = AttachmentCrypto.open(key, row.fileId, AttachmentCrypto.SealedAttachment(row.wrappedDek, row.sealedData))
                    out += BackupAttachmentData(index, row.fileId, row.name, row.mime, data)
                } catch (e: Exception) {
                    // پیوست خراب: در بکاپ نمی‌آید، ولی بقیهٔ بکاپ ساخته می‌شود.
                }
            }
        }
        out
    }

    /** تکثیر ورودی: کپی بار (payload) با شناسه و ویرایش تازه؛ عنوان « (کپی)» می‌گیرد.
     * پیوست‌ها کپی نمی‌شوند (عمداً: هر کپی باید DEK تازه بگیرد)؛ کاربر می‌تواند
     * دوباره به کپی پیوست اضافه کند. */
    suspend fun duplicate(id: String): VaultItem? = lock.withLock {
        val current = _items.value.firstOrNull { it.id == id } ?: return@withLock null
        val now = clock()
        val p = current.payload.copy(
            title = "${current.payload.title} (کپی)",
            createdAt = now,
            editedAt = now,
        )
        val item = VaultItem(newId(), 1, p)
        withKey { key -> store.save(seal(key, item, now), null, MAX_HISTORY) }
        syncTaskDue(item)
        publish(item)
        _localChanges.value++
        item
    }

    suspend fun delete(id: String) = lock.withLock {
        val current = _items.value.firstOrNull { it.id == id } ?: return@withLock
        val now = clock()
        val old = store.get(id, vaultId())
        // نسخهٔ فعلی را در تاریخچه بایگانی می‌کنیم تا از سطل زباله قابل بازگردانی باشد؛
        // blob خود tombstone طبق قرارداد همگام‌سازی خالی می‌ماند.
        val archived = old?.let { StoredHistory(id, it.rev, now, it.wrappedItemKey, it.blob, vaultId()) }
        store.delete(id, now, current.rev + 1, archived, vaultId())
        // پیوست‌ها عمداً می‌مانند: بازگردانی از سطل زباله آن‌ها را هم برمی‌گرداند؛
        // فقط حذف دائمی (purge) آن‌ها را می‌زداید.
        store.deleteTaskDue(id, vaultId())
        _items.value = _items.value.filterNot { it.id == id }
        _localChanges.value++
    }

    /** یک ورودی حذف‌شده (tombstone) به‌همراه آخرین نسخهٔ قابل بازیابی‌اش. */
    data class TrashedItem(val id: String, val rev: Long, val deletedAt: Long, val payload: ItemPayload)

    /** فرادادهٔ یک پیوست (بدون محتوای رمز) برای نمایش در UI و بکاپ. */
    data class AttachmentMeta(
        val fileId: String,
        val itemId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val addedAt: Long,
    )

    /** فهرست سطل زباله: tombstoneهایی که آخرین نسخه‌شان رمزگشایی می‌شود. */
    suspend fun trashedItems(): List<TrashedItem> = withKey { key ->
        val rows = store.all(vaultId()).filter { it.deletedAt != null }
        val list = ArrayList<TrashedItem>(rows.size)
        for (r in rows) {
            val latest = store.history(r.id, vaultId()).maxByOrNull { it.rev } ?: continue
            try {
                val plain = VaultCrypto.openItem(key, r.id, latest.rev, ItemPayload.SCHEMA, SealedItem(latest.wrappedItemKey, latest.blob))
                try {
                    list.add(TrashedItem(r.id, r.rev, r.deletedAt!!, ItemPayload.fromBytes(plain)))
                } finally {
                    plain.wipe()
                }
            } catch (e: Exception) {
                // نسخهٔ خراب: در سطل زباله نشان داده نمی‌شود ولی purge منقضی آن را می‌زداید.
            }
        }
        list.sortedByDescending { it.deletedAt }
    }

    /** بازگردانی از سطل زباله: نسخهٔ آخر به‌عنوان ویرایش جدید زنده می‌شود تا همگام‌سازی آن را منتشر کند. */
    suspend fun restoreTrashed(id: String): VaultItem? = lock.withLock {
        val row = store.get(id, vaultId()) ?: return@withLock null
        if (row.deletedAt == null) return@withLock _items.value.firstOrNull { it.id == id }
        val trashed = trashedItems().firstOrNull { it.id == id } ?: return@withLock null
        val now = clock()
        val item = VaultItem(id, row.rev + 1, trashed.payload.copy(editedAt = now))
        withKey { key -> store.save(seal(key, item, now), null, MAX_HISTORY) }
        syncTaskDue(item)
        publish(item)
        _localChanges.value++
        item
    }

    /** حذف دائمی یک ورودی از سطل زباله (ردیف + تاریخچه + پیوست‌ها). */
    suspend fun purgeTrashed(id: String) = lock.withLock {
        store.purge(id, vaultId())
        store.deleteAttachmentsForItem(id)
        store.deleteTaskDue(id, vaultId())
        _localChanges.value++
    }

    /**
     * پاک‌سازی خودکار tombstoneهای قدیمی‌تر از ۹۰ روز، هم‌راستا با
     * [app.solina.refuge.core.sync.SyncEngine.TOMBSTONE_RETENTION_MILLIS]؛
     * بعد از این مدت حذف به دستگاه‌های آفلاین دیگر منتشر نمی‌شود، پس نگه‌داشتن ردیف بی‌فایده است.
     */
    suspend fun purgeExpiredTrash(now: Long = clock()): Int = lock.withLock {
        val expired = store.all(vaultId()).filter {
            it.deletedAt != null && now - it.deletedAt > app.solina.refuge.core.sync.SyncEngine.TOMBSTONE_RETENTION_MILLIS
        }
        for (row in expired) {
            store.purge(row.id, vaultId())
            store.deleteAttachmentsForItem(row.id)
            store.deleteTaskDue(row.id, vaultId())
        }
        if (expired.isNotEmpty()) _localChanges.value++
        expired.size
    }

    suspend fun history(id: String): List<HistoryEntry> = withKey { key ->
        store.history(id, vaultId()).mapNotNull { h ->
            try {
                val plain = VaultCrypto.openItem(key, id, h.rev, ItemPayload.SCHEMA, SealedItem(h.wrappedItemKey, h.blob))
                try {
                    HistoryEntry(h.rev, h.savedAt, ItemPayload.fromBytes(plain))
                } finally {
                    plain.wipe()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** بازگردانی یک نسخهٔ قدیمی؛ خودش یک ویرایش جدید است (نسخهٔ فعلی هم در تاریخچه می‌ماند). */
    suspend fun restore(id: String, entry: HistoryEntry): VaultItem = update(id, entry.payload)

    /** پاک‌کردن فهرست رمزگشایی‌شده از حافظه (هنگام قفل). */
    fun clear() {
        _items.value = emptyList()
        _unreadable.value = 0
    }

    private fun seal(key: ByteArray, item: VaultItem, now: Long): StoredItem {
        val plain = item.payload.toBytes()
        try {
            val s = VaultCrypto.sealItem(key, item.id, item.rev, ItemPayload.SCHEMA, plain)
            return StoredItem(item.id, item.rev, now, null, s.wrappedItemKey, s.blob, vaultId())
        } finally {
            plain.wipe()
        }
    }

    private fun publish(item: VaultItem) {
        _items.value = (_items.value.filterNot { it.id == item.id } + item).sortedBy { it.payload.title.lowercase() }
    }

    companion object {
        const val MAX_HISTORY = 10
        /** حداکثر طول نام پیوست در فراداده (نام‌های بلندتر بریده می‌شوند). */
        const val MAX_ATTACHMENT_NAME = 255
        /** حداکثر طول نوع MIME پیوست در فراداده. */
        const val MAX_ATTACHMENT_MIME = 128
    }
}
