package app.solina.refuge.core.backup

import app.solina.refuge.core.crypto.Aead
import app.solina.refuge.core.crypto.Argon2id
import app.solina.refuge.core.crypto.Hkdf
import app.solina.refuge.core.crypto.KdfParams
import app.solina.refuge.core.crypto.VaultCrypto
import app.solina.refuge.core.crypto.randomBytes
import app.solina.refuge.core.crypto.wipe
import app.solina.refuge.core.storage.ItemPayload
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

class BackupFormatException(message: String) : Exception(message)

/** متادیتای اعتبارسنجی‌شدهٔ هدر فایل بکاپ پیش از دریافت رمز عبور. */
data class BackupHeaderInfo(
    val version: Int,
    val kdf: KdfParams,
    val totalBytes: Int,
)

/** نتیجهٔ کامل رمزگشایی بکاپ شامل ورودی‌ها و در صورت وجود، هدر اصلی خزانه (رمز اصلی و ۲۴ کلمهٔ قبلی). */
data class DecodedBackup(
    val items: List<ItemPayload>,
    val vaultHeader: ByteArray?,
    /** پیوست‌ها (فقط در بکاپ‌های نسخهٔ ۲)؛ دادهٔ هر پیوست متنِ اصلیِ فایل است،
     * چون خودِ بکاپ با رمز بکاپ رمز شده و پیوست داخل همان پوشش امن می‌نشیند —
     * دقیقاً مثل JSON ورودی‌ها. هنگام بازیابی دوباره با کلید خزانهٔ فعلی بسته می‌شوند. */
    val attachments: List<BackupAttachmentData> = emptyList(),
)

/**
 * یک پیوستِ داخل بکاپ نسخهٔ ۲. [itemIndex] شمارهٔ ورودیِ صاحبِ پیوست در
 * آرایهٔ `items` همان بکاپ است (چون هنگام بازیابی شناسه‌های تازه ساخته می‌شوند،
 * پیوند با شمارهٔ ردیف برقرار می‌ماند، نه با شناسهٔ قدیمی).
 */
data class BackupAttachmentData(
    val itemIndex: Int,
    val fileId: String,
    val name: String,
    val mime: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupAttachmentData) return false
        return itemIndex == other.itemIndex && fileId == other.fileId && name == other.name &&
            mime == other.mime && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = itemIndex
        result = 31 * result + fileId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * بکاپ رمزشده (RFGB v1/v2). با «رمز بکاپ» جداگانه محافظت می‌شود (Argon2id ← HKDF ← AES-256-GCM)،
 * نه با کلید خزانه، تا بتوان آن را روی دستگاه دیگر هم باز کرد. هدر در AAD است و دست‌کاری آن شناسایی می‌شود.
 *
 * چیدمان: "RFGB"(4) | نسخه(1) | حافظه KiB(4) | تکرار(4) | موازی(4) | salt(16) | AES-GCM(JSON)
 *
 * نسخهٔ ۱: همان قالب قبلی — فقط ورودی‌ها. نسخهٔ ۲: ورودی‌ها + آرایهٔ `attachments`
 * در JSON (هر پیوست: شمارهٔ ردیف ورودی، شناسهٔ فایل، نام، نوع، اندازه و دادهٔ Base64).
 * بکاپ v1 دقیقاً مثل قبل خوانده می‌شود و [encode] وقتی پیوستی ندارد، بایت‌به‌بایت
 * همان خروجی v1 را می‌سازد (سازگاری عقب‌رو کامل).
 */
object Backup {
    private val MAGIC = "RFGB".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val VERSION_2: Byte = 2
    private const val SALT_SIZE = 16
    private const val HEADER_SIZE = 4 + 1 + 12 + SALT_SIZE
    private val INFO = "refuge/backup/v1".toByteArray(Charsets.US_ASCII)

    private fun key(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val pw = VaultCrypto.passwordBytes(password)
        try {
            val master = Argon2id.derive(pw, salt, params)
            try {
                return Hkdf.derive(master, null, INFO)
            } finally {
                master.wipe()
            }
        } finally {
            pw.wipe()
        }
    }

    /** آیا بایت‌ها با سرآیند رسمی بکاپ Refuge («RFGB») شروع می‌شوند؟ */
    fun isRefugeBackup(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** نمایش خوانای اندازهٔ فایل بکاپ. */
    fun formatByteSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "$bytes B"
    }

    /** اعتبارسنجی هدر و پارامترهای KDF پیش از نمایش صفحهٔ ورود رمز بازیابی. */
    fun inspect(bytes: ByteArray): BackupHeaderInfo {
        if (bytes.size < HEADER_SIZE + 28 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw BackupFormatException("این فایل یک بکاپ Refuge نیست.")
        }
        if (bytes[4] != VERSION && bytes[4] != VERSION_2) throw BackupFormatException("نسخهٔ این بکاپ پشتیبانی نمی‌شود.")
        val bb = ByteBuffer.wrap(bytes, 5, 12)
        val params = try {
            KdfParams(bb.int, bb.int, bb.int)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("هدر بکاپ نامعتبر است.")
        }
        return BackupHeaderInfo(version = bytes[4].toInt() and 0xff, kdf = params, totalBytes = bytes.size)
    }

    /**
     * رمزگذاری بکاپ. بدون پیوست دقیقاً بکاپ v1 ساخته می‌شود (بایت‌به‌بایت مثل قبل)؛
     * با حداقل یک پیوست، بکاپ v2 ساخته می‌شود (بایت نسخه ۲ + آرایهٔ `attachments` در JSON).
     * [attachments] دادهٔ رمزگشایی‌شدهٔ پیوست‌هاست؛ فراخواننده (خزانهٔ باز) آن‌ها را
     * با کلید خزانه باز کرده است و اینجا فقط داخل پوششِ رمزِ بکاپ می‌نشینند.
     */
    fun encode(
        items: List<ItemPayload>,
        password: CharArray,
        now: Long,
        params: KdfParams = KdfParams.DEFAULT,
        vaultHeader: ByteArray? = null,
        attachments: List<BackupAttachmentData> = emptyList(),
    ): ByteArray {
        val isV2 = attachments.isNotEmpty()
        val versionByte = if (isV2) VERSION_2 else VERSION
        val json = JSONObject().apply {
            put("format", "refuge-backup")
            put("version", if (isV2) 2 else 1)
            put("exportedAt", now)
            if (vaultHeader != null && vaultHeader.isNotEmpty()) {
                put("vaultHeader", Base64.getEncoder().encodeToString(vaultHeader))
            }
            put("items", JSONArray().also { arr -> items.forEach { arr.put(JSONObject(String(it.toBytes(), Charsets.UTF_8))) } })
            if (isV2) {
                put(
                    "attachments",
                    JSONArray().also { arr ->
                        attachments.forEach { a ->
                            arr.put(
                                JSONObject().apply {
                                    put("i", a.itemIndex)
                                    put("id", a.fileId)
                                    put("n", a.name)
                                    put("m", a.mime)
                                    put("s", a.data.size)
                                    put("d", Base64.getEncoder().encodeToString(a.data))
                                },
                            )
                        }
                    },
                )
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val salt = randomBytes(SALT_SIZE)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(versionByte).putInt(params.memoryKiB).putInt(params.iterations).putInt(params.parallelism).put(salt)
            .array()
        val k = key(password, salt, params)
        try {
            return header + Aead.seal(k, json, header)
        } finally {
            k.wipe()
            json.wipe()
        }
    }

    /** بکاپ مستقل و رمزشده صرفاً برای حساب‌های Authenticator (TOTP و HOTP). */
    fun encodeAuthenticatorOnly(
        items: List<ItemPayload>,
        password: CharArray,
        now: Long,
        params: KdfParams = KdfParams.DEFAULT,
        vaultHeader: ByteArray? = null,
    ): ByteArray {
        val otpItems = items.filter { it.totpSecret.isNotBlank() }
        if (otpItems.isEmpty()) {
            throw BackupFormatException("هیچ حساب Authenticator (کد TOTP/HOTP) برای بکاپ وجود ندارد.")
        }
        return encode(otpItems, password, now, params, vaultHeader)
    }

    /** رمزگشایی کامل بکاپ (ورودی‌ها + هدر اصلی خزانه در صورت وجود). */
    fun decodeFull(bytes: ByteArray, password: CharArray): DecodedBackup {
        val info = inspect(bytes)
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        val salt = bytes.copyOfRange(HEADER_SIZE - SALT_SIZE, HEADER_SIZE)
        val k = key(password, salt, info.kdf)
        val plain = try {
            Aead.open(k, bytes.copyOfRange(HEADER_SIZE, bytes.size), header)
        } finally {
            k.wipe()
        }
        try {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            if (root.optString("format") != "refuge-backup") throw BackupFormatException("محتوای بکاپ ناشناخته است.")
            val arr = root.getJSONArray("items")
            val items = (0 until arr.length()).map {
                ItemPayload.fromBytes(arr.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            }
            val vhB64 = root.optString("vaultHeader", "")
            val vh = if (vhB64.isNotEmpty()) runCatching { Base64.getDecoder().decode(vhB64) }.getOrNull() else null
            val attachments = if (info.version == 2) parseAttachments(root, items.size) else emptyList()
            return DecodedBackup(items = items, vaultHeader = vh, attachments = attachments)
        } catch (e: JSONException) {
            throw BackupFormatException("محتوای بکاپ خراب است.")
        } finally {
            plain.wipe()
        }
    }

    /** خواندن و اعتبارسنجی آرایهٔ `attachments` بکاپ v2؛ هر رکورد خراب، کل بکاپ را رد می‌کند. */
    private fun parseAttachments(root: JSONObject, itemCount: Int): List<BackupAttachmentData> {
        val arr = root.optJSONArray("attachments") ?: return emptyList()
        val out = ArrayList<BackupAttachmentData>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw BackupFormatException("پیوست بکاپ خراب است.")
            val itemIndex = o.optInt("i", -1)
            if (itemIndex < 0 || itemIndex >= itemCount) throw BackupFormatException("پیوست بکاپ به ورودی ناموجود اشاره می‌کند.")
            val fileId = o.optString("id", "")
            if (fileId.isBlank()) throw BackupFormatException("پیوست بکاپ شناسه ندارد.")
            val data = try {
                Base64.getDecoder().decode(o.optString("d", ""))
            } catch (e: IllegalArgumentException) {
                throw BackupFormatException("دادهٔ پیوست بکاپ خراب است.")
            }
            if (data.size != o.optInt("s", -1)) throw BackupFormatException("اندازهٔ پیوست بکاپ با داده‌اش نمی‌خواند.")
            if (data.size > app.solina.refuge.core.crypto.AttachmentCrypto.MAX_ATTACHMENT_BYTES) {
                throw BackupFormatException("پیوست بکاپ بیش از حد بزرگ است.")
            }
            out += BackupAttachmentData(
                itemIndex = itemIndex,
                fileId = fileId,
                name = o.optString("n", ""),
                mime = o.optString("m", ""),
                data = data,
            )
        }
        return out
    }

    /** @throws BackupFormatException فایل بکاپ سولینا نیست یا ناقص است؛ @throws AuthenticationFailedException رمز اشتباه یا فایل دست‌کاری‌شده. */
    fun decode(bytes: ByteArray, password: CharArray): List<ItemPayload> = decodeFull(bytes, password).items
}
