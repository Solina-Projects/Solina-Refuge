package app.solina.refuge.core.storage

import app.solina.refuge.core.crypto.MultiVaultDesign

/** یک ردیف ذخیره‌شده. فقط id، شمارهٔ ویرایش و زمان‌ها آشکارند؛ هر چیز دیگر داخل [blob] رمزشده است. */
class StoredItem(
    val id: String,
    val rev: Long,
    val updatedAt: Long,
    /** غیر null یعنی حذف‌شده (tombstone برای همگام‌سازی آینده)؛ [blob] در آن حالت خالی است. */
    val deletedAt: Long?,
    val wrappedItemKey: ByteArray,
    val blob: ByteArray,
    /** خزانه‌ای که این ردیف به آن تعلق دارد؛ همهٔ خواندن‌ها/نوشتن‌ها به خزانهٔ فعال محدودند. */
    val vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID,
)

/**
 * فرادادهٔ متن‌باز موعد یک کار (فاز ۳): فقط شناسه و زمان موعد، بدون هیچ عنوان یا متنی.
 * یادآور WorkManager با همین جدول و درحالی‌که خزانه قفل است کار می‌کند؛ چون هیچ محتوایی
 * اینجا نیست، نوتیفیکیشن هم فقط پیام کلی نشان می‌دهد.
 */
class TaskDueMeta(val id: String, val dueAt: Long, val notifiedAt: Long)

/** نسخهٔ قدیمی یک ورودی که رمزشده نگه داشته می‌شود. */
class StoredHistory(
    val itemId: String,
    val rev: Long,
    val savedAt: Long,
    val wrappedItemKey: ByteArray,
    val blob: ByteArray,
    val vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID,
)

/**
 * یک پیوست رمزگذاری‌شدهٔ ذخیره‌شده (فاز ۴، اتصال کامل).
 * فقط fileId، itemId، نام، نوع، اندازه و زمان افزودن آشکارند؛ محتوای فایل
 * ([sealedData]) و کلید آن ([wrappedDek]) با [app.solina.refuge.core.crypto.AttachmentCrypto]
 * و کلید خزانه بسته شده‌اند و بدون آن باز نمی‌شوند.
 */
class StoredAttachment(
    val fileId: String,
    val itemId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    val wrappedDek: ByteArray,
    val sealedData: ByteArray,
)

/** لایهٔ ذخیره؛ هیچ چیز دربارهٔ رمزنگاری نمی‌داند. */
interface ItemStore {
    /** همهٔ ردیف‌های خزانهٔ داده‌شده (پیش‌فرض: خزانهٔ پیش‌فرض، برای سازگاری با ذخیره‌های قدیمی). */
    suspend fun all(vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID): List<StoredItem>
    suspend fun get(id: String, vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID): StoredItem?

    /** ذخیرهٔ اتمیک ورودی جدید و (اختیاری) نسخهٔ قبلی در تاریخچه؛ تاریخچه به [keepHistory] نسخه محدود می‌شود. */
    suspend fun save(item: StoredItem, archivedPrevious: StoredHistory?, keepHistory: Int)

    /** حذف نرم: blob خالی، tombstone می‌ماند تا همگام‌سازی حذف را منتشر کند.
     * تاریخچه حفظ می‌شود تا بازگردانی از سطل زباله ممکن باشد؛ اگر [archivedPrevious] داده شود
     * (نسخهٔ فعلیِ در حال حذف) هم به تاریخچه اضافه می‌شود. */
    suspend fun delete(
        id: String,
        deletedAt: Long,
        newRev: Long,
        archivedPrevious: StoredHistory? = null,
        vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID,
    )

    /** حذف دائمی ردیف و تاریخچه (خالی کردن سطل زباله / پاک‌سازی tombstone منقضی). */
    suspend fun purge(id: String, vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID)

    suspend fun history(itemId: String, vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID): List<StoredHistory>
    suspend fun wipeAll()

    // ── پیوست‌های رمزگذاری‌شده (فاز ۴) ──
    // پیاده‌سازی پیش‌فرض خالی است تا ذخیره‌های آزمایشی قدیمی نشکنند؛ RoomItemStore آن را بازنویسی می‌کند.
    /** همهٔ پیوست‌های یک ورودی، به ترتیب زمان افزودن. */
    suspend fun attachments(itemId: String): List<StoredAttachment> = emptyList()
    /** یک پیوست با شناسهٔ فایل، یا null اگر نباشد. */
    suspend fun attachment(fileId: String): StoredAttachment? = null
    /** همهٔ پیوست‌های همهٔ ورودی‌ها (برای بکاپ نسخهٔ ۲). */
    suspend fun allAttachments(): List<StoredAttachment> = emptyList()
    /** درج/به‌روزرسانی یک پیوست (fileId کلید اصلی است). */
    suspend fun saveAttachment(attachment: StoredAttachment) {}
    /** حذف یک پیوست با شناسهٔ فایل. */
    suspend fun deleteAttachment(fileId: String) {}
    /** حذف همهٔ پیوست‌های یک ورودی (هنگام حذف دائمی ورودی). */
    suspend fun deleteAttachmentsForItem(itemId: String) {}
    /** حذف همهٔ پیوست‌ها (هنگام پاک‌سازی کامل خزانه). */
    suspend fun wipeAttachments() {}

    /** حذف کامل همهٔ ردیف‌ها، تاریخچه، موعدها و پیوست‌های یک خزانه (هنگام حذف خزانه). */
    suspend fun wipeVault(vaultId: String) {
        for (row in all(vaultId)) {
            purge(row.id, vaultId)
            deleteAttachmentsForItem(row.id)
        }
    }

    // ── فرادادهٔ موعد کارها (فاز ۳) ──
    // پیاده‌سازی پیش‌فرض خالی است تا ذخیره‌های آزمایشی قدیمی نشکنند؛ RoomItemStore آن را بازنویسی می‌کند.
    /** ثبت/به‌روزرسانی موعد متن‌باز یک کار؛ فقط برای کارهای ناتمامِ دارای موعد صدا زده می‌شود. */
    suspend fun saveTaskDue(id: String, dueAt: Long, notifiedAt: Long = 0L, vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID) {}
    /** حذف ردیف موعد (کار انجام شد، موعد برداشته شد، یا ورودی حذف شد). */
    suspend fun deleteTaskDue(id: String, vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID) {}
    /** همهٔ موعدهای متن‌باز (برای یادآور پس‌زمینه در حالت قفل). */
    suspend fun allTaskDues(vaultId: String = MultiVaultDesign.DEFAULT_VAULT_ID): List<TaskDueMeta> = emptyList()
}
