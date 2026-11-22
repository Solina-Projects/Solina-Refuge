package app.solina.refuge.core.crypto

/**
 * پیوست رمزگذاری‌شده (فاز ۴، آیتم ۶) — لایهٔ رمزنگاری خالص؛ به خزانه (Room)،
 * بکاپ v2 و UI وصل شده است (بخش «وضعیت اتصال» پایین را بخوانید).
 *
 * ══════════════════════════ طراحی (ثابت‌شده با تست) ══════════════════════════
 *
 * ۱) سلسله‌مراتب کلید (هیچ تغییری در KDF اصلی خزانه نیست):
 *    - کلید خزانه (vaultKey، همان ۳۲ بایت AES که VaultCrypto می‌سازد) عوض نمی‌شود.
 *    - هر پیوست یک DEK تصادفی ۳۲ بایتیِ خودش دارد (per-file DEK).
 *    - DEK با کلید خزانه و AES-256-GCM بسته می‌شود (wrappedDek)؛ AAD آن
 *      "refuge/attachment/dek/v1" + شناسهٔ فایل است، پس DEK یک فایل با فایل دیگر عوض نمی‌شود.
 *    - محتوای فایل با DEK و AES-256-GCM بسته می‌شود (sealedData)؛ AAD آن
 *      "refuge/attachment/data/v1" + شناسهٔ فایل است.
 *    - نتیجه: اگر یک DEK لو برود فقط همان یک فایل لو می‌رود؛ اگر کلید خزانه عوض شود
 *      (تغییر رمز اصلی) فقط wrappedDekها باید دوباره بسته شوند، نه کل داده‌ها.
 *
 * ۲) شناسهٔ فایل (fileId): رشته‌ای یکتا که فراخواننده می‌سازد (مثلاً UUID)؛
 *    در AAD هر دو لایه بسته شده است، پس جابه‌جایی فایل‌ها شناسایی می‌شود.
 *
 * ۳) محدودیت اندازه: حداکثر ۱۰ مگابایت برای هر فایل (MAX_ATTACHMENT_BYTES).
 *    بیشتر از آن رد می‌شود تا حافظهٔ گوشی پر نشود و بکاپ غول‌پیکر نسازد.
 *
 * ۴) بکاپ: پیوست‌ها در بکاپ RFGB v2 هستند ([app.solina.refuge.core.backup.Backup] نسخهٔ ۲ را
 *    وقتی حداقل یک پیوست هست می‌سازد و بکاپ‌های v1 را دقیقاً مثل قبل می‌خواند).
 *    در بکاپ، دادهٔ پیوست داخل همان پوششِ رمزِ بکاپ می‌نشیند (مثل JSON ورودی‌ها) و
 *    هنگام بازیابی دوباره با کلید خزانهٔ فعلی بسته می‌شود؛ پس بازیابی روی خزانهٔ
 *    دیگر هم پیوست‌ها را برمی‌گرداند و نیازی به کلید خزانهٔ مبدأ نیست.
 *
 * ══════════════════════════ وضعیت اتصال ══════════════════════════
 *
 * - ذخیره‌سازی: جدول `attachments` در Room (مهاجرت ۲ ← ۳) + لایهٔ [app.solina.refuge.core.storage.ItemStore].
 * - خزانه: [app.solina.refuge.core.storage.VaultRepository] پیوست را اضافه/باز/حذف می‌کند و
 *   در بکاپ/بازیابی ([importBackup] / [exportAttachmentsForBackup]) شرکت می‌دهد.
 * - UI: بخش «پیوست‌ها» در صفحهٔ جزئیات ورودی (افزودن / باز کردن / حذف).
 * - تکثیر ورودی پیوست‌ها را کپی نمی‌کند (عمداً: هر کپی باید DEK تازه بگیرد و
 *   این کار هنوز پیاده نشده است)؛ حذف ورودی پیوست‌ها را نگه می‌دارد تا بازگردانی
 *   از سطل زباله آن‌ها را هم برگرداند، و فقط حذف دائمی (purge) آن‌ها را می‌زداید.
 */
object AttachmentCrypto {
    /** حداکثر اندازهٔ هر پیوست: ۱۰ مگابایت. */
    const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

    const val FORMAT_VERSION = 1

    private val AAD_DEK_PREFIX = "refuge/attachment/dek/v1/".toByteArray(Charsets.US_ASCII)
    private val AAD_DATA_PREFIX = "refuge/attachment/data/v1/".toByteArray(Charsets.US_ASCII)

    /** پیوست بسته‌شده: DEKِ بسته‌شده با کلید خزانه + دادهٔ بسته‌شده با DEK. */
    data class SealedAttachment(
        val wrappedDek: ByteArray,
        val sealedData: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SealedAttachment) return false
            return wrappedDek.contentEquals(other.wrappedDek) && sealedData.contentEquals(other.sealedData)
        }

        override fun hashCode(): Int {
            var result = wrappedDek.contentHashCode()
            result = 31 * result + sealedData.contentHashCode()
            return result
        }
    }

    private fun aad(prefix: ByteArray, fileId: String): ByteArray =
        prefix + fileId.toByteArray(Charsets.UTF_8)

    /**
     * رمزگذاری یک فایل.
     * @param vaultKey کلید ۳۲ بایتی خزانه (هرگز ذخیره یا لاگ نشود).
     * @param fileId شناسهٔ یکتای فایل (مثلاً UUID)؛ خالی نباشد.
     * @param data محتوای فایل؛ حداکثر [MAX_ATTACHMENT_BYTES].
     * @throws IllegalArgumentException اگر کلید ۳۲ بایت نباشد، fileId خالی باشد یا فایل بیش از حد بزرگ باشد.
     */
    fun seal(vaultKey: ByteArray, fileId: String, data: ByteArray): SealedAttachment {
        require(vaultKey.size == Aead.KEY_SIZE) { "vaultKey must be 32 bytes" }
        require(fileId.isNotBlank()) { "fileId must not be blank" }
        require(data.size <= MAX_ATTACHMENT_BYTES) { "attachment exceeds 10 MB limit" }
        val dek = randomBytes(Aead.KEY_SIZE)
        try {
            val wrappedDek = Aead.seal(vaultKey, dek, aad(AAD_DEK_PREFIX, fileId))
            val sealedData = Aead.seal(dek, data, aad(AAD_DATA_PREFIX, fileId))
            return SealedAttachment(wrappedDek, sealedData)
        } finally {
            dek.wipe()
        }
    }

    /**
     * رمزگشایی یک فایل.
     * @throws AuthenticationFailedException اگر کلید، شناسهٔ فایل یا هر بایت از
     * دادهٔ بسته‌شده عوض شده باشد (دست‌کاری شناسایی می‌شود).
     */
    fun open(vaultKey: ByteArray, fileId: String, sealed: SealedAttachment): ByteArray {
        require(vaultKey.size == Aead.KEY_SIZE) { "vaultKey must be 32 bytes" }
        require(fileId.isNotBlank()) { "fileId must not be blank" }
        val dek = Aead.open(vaultKey, sealed.wrappedDek, aad(AAD_DEK_PREFIX, fileId))
        try {
            return Aead.open(dek, sealed.sealedData, aad(AAD_DATA_PREFIX, fileId))
        } finally {
            dek.wipe()
        }
    }
}
