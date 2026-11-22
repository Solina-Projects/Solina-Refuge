package app.solina.refuge.core.security

import app.solina.refuge.core.crypto.MultiVaultDesign
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * یک خزانه: شناسهٔ پایدار، نام نمایشی و زمان ساخت.
 * شناسه فقط برای نام پوشه و ستون دیتابیس استفاده می‌شود؛ هیچ رمزی در آن نیست.
 */
data class VaultInfo(val id: String, val name: String, val createdAt: Long)

/**
 * فهرست خزانه‌ها و خزانهٔ فعال، ذخیره‌شده در `vaults.txt` کنار پوشه‌های خزانه.
 *
 * قالب فایل (متن ساده، یک رکورد در هر خط):
 *   خط اول: `active:<id>`
 *   خط‌های بعد: `<id>\t<base64url(نام)>\t<createdAt>`
 *
 * فایل اتمی نوشته می‌شود (فایل موقت + rename) تا قطع برق وسط نوشتن، فهرست را خراب نکند.
 * این کلاس هیچ وابستگی اندرویدی ندارد تا در تست‌های JVM مستقیم تست شود.
 */
class VaultRegistry(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val registryFile = File(rootDir, FILE_NAME)

    @Volatile
    private var cached: RegistryState? = null

    private data class RegistryState(val activeId: String, val vaults: List<VaultInfo>)

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    /** شناسهٔ تازه برای خزانهٔ جدید (بدون ثبت؛ ثبت با [register] انجام می‌شود). */
    fun freshId(): String = newId()

    /**
     * اگر فایل فهرست نیست، با یک خزانهٔ پیش‌فرض می‌سازدش.
     * برای نصب‌های قدیمیِ تک‌خزانه‌ای هم همین اتفاق می‌افتد: خزانهٔ موجود بدون هیچ
     * رمزگذاریِ دوباره، «خزانهٔ پیش‌فرض» می‌شود (مهاجرت فایل‌ها با [VaultFiles.migrateLegacy]).
     */
    @Synchronized
    fun ensure() {
        if (cached == null) cached = read()
        if (cached == null) {
            val default = VaultInfo(
                MultiVaultDesign.DEFAULT_VAULT_ID,
                DEFAULT_VAULT_NAME_FA,
                clock(),
            )
            val state = RegistryState(default.id, listOf(default))
            write(state)
            cached = state
        }
    }

    /** فهرست خزانه‌ها به ترتیب ساخت. */
    @Synchronized
    fun vaults(): List<VaultInfo> {
        ensure()
        return cached!!.vaults
    }

    /** شناسهٔ خزانهٔ فعال. */
    @Synchronized
    fun activeId(): String {
        ensure()
        return cached!!.activeId
    }

    /** خزانهٔ فعال، یا null اگر فهرست خالی باشد (نباید اتفاق بیفتد؛ [ensure] همیشه یکی می‌سازد). */
    @Synchronized
    fun active(): VaultInfo? {
        ensure()
        val state = cached!!
        return state.vaults.firstOrNull { it.id == state.activeId } ?: state.vaults.firstOrNull()
    }

    /** آیا خزانه‌ای با این شناسه وجود دارد؟ */
    @Synchronized
    fun exists(id: String): Boolean {
        ensure()
        return cached!!.vaults.any { it.id == id }
    }

    /**
     * اعتبارسنجی نام و سقف تعداد، و ساخت [VaultInfo] بدون ثبت.
     * ثبت واقعی (نوشتن هدر خزانه) با [register] انجام می‌شود تا ساختِ نیمه‌کاره
     * (کاربر عبارت بازیابی را تأیید نکرد) خزانهٔ خالی در فهرست نسازد.
     */
    @Synchronized
    fun reserve(name: String): VaultInfo {
        ensure()
        val clean = name.trim()
        require(clean.isNotEmpty()) { "vault name is empty" }
        require(clean.length <= MAX_NAME_LENGTH) { "vault name too long" }
        check(cached!!.vaults.size < MultiVaultDesign.MAX_VAULTS) { "too many vaults" }
        return VaultInfo(freshId(), clean, clock())
    }

    /** ثبت خزانهٔ تازه‌ساخته‌شده در فهرست (پس از ساخته‌شدن هدرش). */
    @Synchronized
    fun register(info: VaultInfo) {
        ensure()
        validateId(info.id)
        require(info.name.isNotBlank()) { "vault name is empty" }
        check(cached!!.vaults.none { it.id == info.id }) { "vault id already exists" }
        check(cached!!.vaults.size < MultiVaultDesign.MAX_VAULTS) { "too many vaults" }
        val state = cached!!.copy(vaults = cached!!.vaults + info)
        write(state)
        cached = state
    }

    /** حذف ورودی خزانه از فهرست، بدون هیچ بررسی (برای بازگردانیِ ثبتِ ناموفق). */
    @Synchronized
    fun remove(id: String) {
        ensure()
        val state = cached!!
        if (state.vaults.none { it.id == id }) return
        val remaining = state.vaults.filterNot { it.id == id }
        val newActive = if (state.activeId == id) remaining.firstOrNull()?.id ?: state.activeId else state.activeId
        val next = RegistryState(newActive, remaining)
        write(next)
        cached = next
    }

    /** فعال‌کردن خزانه؛ شناسه باید در فهرست باشد. */
    @Synchronized
    fun setActive(id: String) {
        ensure()
        check(cached!!.vaults.any { it.id == id }) { "unknown vault $id" }
        if (cached!!.activeId == id) return
        val next = cached!!.copy(activeId = id)
        write(next)
        cached = next
    }

    /**
     * حذف خزانه از فهرست. حذف آخرین خزانه ممنوع است (حداقل یک خزانه همیشه می‌ماند).
     * @return شناسهٔ خزانهٔ فعالِ جدید (اگر خزانهٔ فعال حذف شده باشد، اولین خزانهٔ باقی‌مانده).
     * @throws IllegalStateException اگر فقط یک خزانه مانده باشد.
     */
    @Synchronized
    fun delete(id: String): String {
        ensure()
        val state = cached!!
        check(state.vaults.any { it.id == id }) { "unknown vault $id" }
        check(state.vaults.size > 1) { "cannot delete the last vault" }
        val remaining = state.vaults.filterNot { it.id == id }
        val newActive = if (state.activeId == id) remaining.first().id else state.activeId
        val next = RegistryState(newActive, remaining)
        write(next)
        cached = next
        return newActive
    }

    /**
     * پاک‌سازی کامل همهٔ داده‌های چندخزانه‌ای: پوشهٔ هر خزانه + فایل فهرست.
     * فقط برای «پاک‌سازی کامل خزانه» (wipe) استفاده می‌شود.
     */
    @Synchronized
    fun wipeAll() {
        val state = cached ?: read()
        for (v in state?.vaults.orEmpty()) {
            VaultFiles.forVault(rootDir, v.id).deleteVaultDir()
        }
        // پوشه‌هایی که در فهرست نیستند (مثلاً بعد از خرابی فایل فهرست) هم پاک شوند.
        rootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(VaultFiles.VAULT_DIR_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        registryFile.delete()
        cached = null
    }

    private fun validateId(id: String) {
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "invalid vault id"
        }
    }

    private fun read(): RegistryState? {
        if (!registryFile.isFile) return null
        // توجه: returnهای داخل runCatching باید محلی باشند (return@runCatching)؛
        // return ساده از خود read() برمی‌گردد و fallback بازسازی از دیسک را دور می‌زند.
        val parsed = runCatching {
            val lines = registryFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            if (lines.isEmpty()) return@runCatching null
            val active = lines.first().removePrefix("active:").trim()
            val vaults = lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val id = parts[0]
                val name = runCatching {
                    String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                }.getOrNull() ?: return@mapNotNull null
                val createdAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                VaultInfo(id, name, createdAt)
            }
            if (vaults.isEmpty() || vaults.none { it.id == active }) return@runCatching null
            RegistryState(active, vaults)
        }.getOrNull()
        return parsed ?: rebuildFromDisk()
    }

    /**
     * بازسازی فهرست از روی پوشه‌های خزانهٔ موجود روی دیسک؛ فقط وقتی فایل فهرست خراب
     * است. نام خزانه‌های غیرپیش‌فرض در این حالت به شناسه برمی‌گردد (بهتر از گم‌شدن
     * خزانه است) و خزانهٔ پیش‌فرض — اگر هست — فعال می‌شود.
     */
    private fun rebuildFromDisk(): RegistryState? {
        val dirs = rootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(VaultFiles.VAULT_DIR_PREFIX) && File(it, "vault.hdr").isFile }
            ?.sortedBy { it.name }
            ?: return null
        if (dirs.isEmpty()) return null
        val vaults = dirs.map { dir ->
            val id = dir.name.removePrefix(VaultFiles.VAULT_DIR_PREFIX)
            val name = if (id == MultiVaultDesign.DEFAULT_VAULT_ID) DEFAULT_VAULT_NAME_FA else id
            VaultInfo(id, name, clock())
        }
        val active = if (vaults.any { it.id == MultiVaultDesign.DEFAULT_VAULT_ID }) {
            MultiVaultDesign.DEFAULT_VAULT_ID
        } else {
            vaults.first().id
        }
        return RegistryState(active, vaults)
    }

    private fun write(state: RegistryState) {
        val text = buildString {
            append("active:").append(state.activeId).append('\n')
            for (v in state.vaults) {
                append(v.id).append('\t')
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(v.name.toByteArray(Charsets.UTF_8)))
                    .append('\t').append(v.createdAt).append('\n')
            }
        }
        val tmp = File(rootDir, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(registryFile)) {
            tmp.delete()
            throw java.io.IOException("cannot replace $FILE_NAME")
        }
    }

    companion object {
        const val FILE_NAME = "vaults.txt"

        /** نام پیش‌فرض خزانهٔ مهاجرت‌شده از نصب تک‌خزانه‌ای. */
        const val DEFAULT_VAULT_NAME_FA = "خزانهٔ اصلی"
        const val DEFAULT_VAULT_NAME_EN = "Main vault"
        const val MAX_NAME_LENGTH = 64
        const val MAX_ID_LENGTH = 64
    }
}
