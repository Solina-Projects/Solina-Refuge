package app.solina.refuge.core.security

import app.solina.refuge.core.crypto.CorruptVaultException
import app.solina.refuge.core.crypto.VaultHeader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class UnlockState(
    val failedAttempts: Int,
    val lastFailureAt: Long,
    val lastFailureElapsed: Long = 0L,
)

/**
 * ذخیرهٔ فایل‌های خزانه در پوشهٔ خصوصی اپ. نوشتن اتمی است: اول فایل موقت، fsync، بعد rename،
 * تا قطع برق وسط نوشتن هدر معتبر قبلی را خراب نکند.
 */
class VaultFiles(private val dir: File) {
    private val header = File(dir, "vault.hdr")
    private val biometric = File(dir, "biometric.bin")
    private val unlockState = File(dir, "unlock.state")

    init {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create vault dir")
    }

    fun hasVault(): Boolean = header.isFile

    fun writeHeader(h: VaultHeader) = atomicWrite(header, h.encode())

    /** @throws CorruptVaultException اگر فایل خراب یا دست‌کاری شده باشد. */
    fun readHeader(): VaultHeader {
        if (!header.isFile) throw CorruptVaultException("no vault")
        return VaultHeader.decode(header.readBytes())
    }

    fun hasBiometric(): Boolean = biometric.isFile
    fun writeBiometric(blob: ByteArray) = atomicWrite(biometric, blob)
    fun readBiometric(): ByteArray? = if (biometric.isFile) biometric.readBytes() else null
    fun clearBiometric() {
        biometric.delete()
    }

    /** شمارندهٔ تلاش ناموفق و زمان آخرین شکست (ساعت دیواری + ساعت یکنواخت برای جلوگیری از دور زدن). */
    fun readUnlockState(): UnlockState = runCatching {
        val parts = unlockState.readText().trim().split(",")
        val attempts = parts[0].toInt().coerceIn(0, 1000)
        val wall = parts[1].toLong().coerceAtLeast(0)
        val elapsed = if (parts.size >= 3) parts[2].toLong().coerceAtLeast(0) else 0L
        UnlockState(attempts, wall, elapsed)
    }.getOrDefault(UnlockState(0, 0, 0L))

    fun writeUnlockState(state: UnlockState) {
        val payload = if (state.lastFailureElapsed > 0L) {
            "${state.failedAttempts},${state.lastFailureAt},${state.lastFailureElapsed}"
        } else {
            "${state.failedAttempts},${state.lastFailureAt}"
        }
        atomicWrite(unlockState, payload.toByteArray())
    }

    /** حذف کامل خزانه (فقط با تأیید صریح کاربر). */
    fun deleteAll() {
        header.delete()
        biometric.delete()
        unlockState.delete()
    }

    /** حذف کامل پوشهٔ این خزانه (هدر، بیومتریک، وضعیت قفل و فایل‌های موقت). */
    fun deleteVaultDir(): Boolean = dir.deleteRecursively()

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot replace ${target.name}")
        }
    }

    companion object {
        /** پیشوند پوشهٔ هر خزانه زیر پوشهٔ ریشهٔ refuge: `vault-<id>`. */
        const val VAULT_DIR_PREFIX = "vault-"

        /** پوشهٔ خزانه با شناسهٔ داده‌شده. شناسه فقط می‌تواند حرف، رقم، `-` یا `_` باشد. */
        fun forVault(root: File, vaultId: String): VaultFiles {
            require(vaultId.isNotEmpty() && vaultId.length <= 64 &&
                vaultId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "invalid vault id"
            }
            return VaultFiles(File(root, VAULT_DIR_PREFIX + vaultId))
        }

        /**
         * مهاجرت نصب تک‌خزانه‌ای: فایل‌های خزانهٔ قدیمی که مستقیم در پوشهٔ ریشه بودند
         * (`vault.hdr` و همراهانش) بدون هیچ رمزگذاریِ دوباره به پوشهٔ `vault-default`
         * منتقل می‌شوند. اگر هدرِ مقصد از قبل هست، فایل‌های قدیمی دست نمی‌خورند.
         * @return true اگر انتقالی انجام شد.
         */
        fun migrateLegacy(root: File): Boolean {
            val legacyHeader = File(root, "vault.hdr")
            if (!legacyHeader.isFile) return false
            val targetDir = File(root, VAULT_DIR_PREFIX + app.solina.refuge.core.crypto.MultiVaultDesign.DEFAULT_VAULT_ID)
            if (File(targetDir, "vault.hdr").isFile) return false
            if (!targetDir.exists() && !targetDir.mkdirs()) return false
            var moved = false
            for (name in listOf("vault.hdr", "biometric.bin", "unlock.state")) {
                val src = File(root, name)
                if (src.isFile) {
                    // renameTo روی یک فایل‌سیستم همیشه جواب می‌دهد؛ اگر نشد، کپی + حذف.
                    if (!src.renameTo(File(targetDir, name))) {
                        runCatching {
                            src.copyTo(File(targetDir, name), overwrite = true)
                            src.delete()
                        }
                    }
                    moved = true
                }
            }
            return moved
        }
    }
}
