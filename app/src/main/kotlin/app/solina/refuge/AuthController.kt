package app.solina.refuge

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.solina.refuge.core.crypto.AuthenticationFailedException
import app.solina.refuge.core.crypto.CorruptVaultException
import app.solina.refuge.core.crypto.CreatedVault
import app.solina.refuge.core.crypto.InvalidRecoveryPhraseException
import app.solina.refuge.core.crypto.VaultCrypto
import app.solina.refuge.core.crypto.wipe
import app.solina.refuge.core.security.BiometricKeystore
import app.solina.refuge.core.security.KdfCalibrator
import app.solina.refuge.core.security.PinResult
import app.solina.refuge.core.security.UnlockBackoff
import app.solina.refuge.core.security.UnlockState
import app.solina.refuge.core.security.VaultFiles
import app.solina.refuge.core.security.VaultInfo
import app.solina.refuge.core.security.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassword : UnlockResult
    data object InvalidPhrase : UnlockResult
    data object Corrupt : UnlockResult
    data class Wait(val millis: Long) : UnlockResult
    /** امضای release تأیید نشد؛ خزانه باز نمی‌شود (فقط در بیلد release). */
    data object IntegrityFailed : UnlockResult
}

/** نتیجهٔ حذف خزانه. */
sealed interface VaultDeleteResult {
    /** حذف شد؛ [newActiveId] خزانهٔ فعالِ جدید است (اگر خزانهٔ فعال حذف شده باشد). */
    data class Ok(val newActiveId: String) : VaultDeleteResult
    /** فقط یک خزانه مانده است؛ حذف آخرین خزانه ممنوع است. */
    data object LastVault : VaultDeleteResult
    /** خزانه‌ای با این شناسه نیست. */
    data object NotFound : VaultDeleteResult
}

/**
 * خزانهٔ تازه‌ساخته‌شده که هنوز ثبت نشده است. هدر فقط بعد از اینکه کاربر عبارت
 * بازیابی ۲۴ کلمه‌ای را تأیید کرد، با [AuthController.commitVault] نوشته و ثبت می‌شود؛
 * تا آن لحظه هیچ فایل یا ورودی‌ای در فهرست خزانه‌ها ساخته نمی‌شود.
 */
class PendingVault(val info: VaultInfo, val created: CreatedVault)

/** منطق ساخت، باز کردن و بازیابی خزانه؛ رابط فقط این کلاس را صدا می‌زند. */
class AuthController(
    private val container: AppContainer,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val files get() = container.files
    private val session get() = container.session
    private val registry: VaultRegistry get() = container.vaultRegistry
    private fun filesFor(vaultId: String): VaultFiles = VaultFiles.forVault(container.vaultRoot, vaultId)

    fun hasVault() = files.hasVault()
    fun hasBiometric() = files.hasBiometric() && BiometricKeystore.hasKey()

    // ───── چندخزانه‌ای ─────

    /** فهرست همهٔ خزانه‌ها به ترتیب ساخت. */
    fun vaults(): List<VaultInfo> = registry.vaults()

    /** شناسهٔ خزانهٔ فعال. */
    fun activeVaultId(): String = registry.activeId()

    /** خزانهٔ فعال. */
    fun activeVault(): VaultInfo? = registry.active()

    /**
     * ساخت خزانهٔ جدید در حافظه (با رمز و ۲۴ کلمهٔ مخصوص خودش؛ کلیدش از هیچ خزانهٔ
     * دیگری مشتق نمی‌شود). روی دیسک نوشته نمی‌شود تا کاربر عبارت بازیابی را تأیید
     * کند ([commitVault]).
     */
    suspend fun createVault(name: String, password: CharArray): PendingVault = withContext(Dispatchers.Default) {
        val info = registry.reserve(name)
        PendingVault(info, VaultCrypto.create(password, KdfCalibrator.pickOnDevice()))
    }

    /**
     * ثبت خزانهٔ تازه و فعال‌کردنش. اگر نوشتن هدر ناموفق باشد، ثبت بازگردانده می‌شود
     * تا خزانهٔ خالی در فهرست نماند. نشست با کلید خزانهٔ جدید باز می‌شود.
     */
    fun commitVault(pending: PendingVault) {
        registry.register(pending.info)
        try {
            filesFor(pending.info.id).writeHeader(pending.created.header)
        } catch (e: Exception) {
            registry.remove(pending.info.id)
            throw e
        }
        registry.setActive(pending.info.id)
        filesFor(pending.info.id).writeUnlockState(UnlockState(0, 0, 0L))
        // هدرِ خزانهٔ قبلی نباید به‌اشتباه به ریموتِ همگام‌سازی برود.
        container.sync.clearHeaderDirty()
        session.unlock(pending.created.vaultKey)
    }

    /**
     * تعویض خزانهٔ فعال با رمز همان خزانه. رمز اول روی هدرِ خزانهٔ مقصد آزموده می‌شود؛
     * اگر درست نباشد، خزانهٔ فعال عوض نمی‌شود و نشستِ خزانهٔ فعلی دست نمی‌خورد.
     * در موفقیت، کلید خزانهٔ قبلی از حافظه پاک و خزانهٔ جدید باز می‌شود.
     */
    suspend fun switchVault(id: String, password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        if (container.integrityBlocked) return@withContext UnlockResult.IntegrityFailed
        if (!registry.exists(id)) return@withContext UnlockResult.Corrupt
        val targetFiles = filesFor(id)
        val wait = remainingWaitMillis(targetFiles)
        if (wait > 0) return@withContext UnlockResult.Wait(wait)
        val header = try {
            targetFiles.readHeader()
        } catch (e: CorruptVaultException) {
            return@withContext UnlockResult.Corrupt
        }
        try {
            val vk = VaultCrypto.unlockWithPassword(header, password)
            session.lock()
            registry.setActive(id)
            targetFiles.writeUnlockState(UnlockState(0, 0, 0L))
            container.sync.clearHeaderDirty()
            session.unlock(vk)
            UnlockResult.Success
        } catch (e: AuthenticationFailedException) {
            val s = targetFiles.readUnlockState()
            targetFiles.writeUnlockState(UnlockState(s.failedAttempts + 1, wallClock(), elapsedClock()))
            UnlockResult.WrongPassword
        }
    }

    /** تعویض خزانهٔ فعال با عبارت بازیابی ۲۴ کلمه‌ای مخصوص همان خزانه. */
    suspend fun switchVaultWithRecovery(id: String, phrase: String): UnlockResult = withContext(Dispatchers.Default) {
        if (container.integrityBlocked) return@withContext UnlockResult.IntegrityFailed
        if (!registry.exists(id)) return@withContext UnlockResult.Corrupt
        val targetFiles = filesFor(id)
        val wait = remainingWaitMillis(targetFiles)
        if (wait > 0) return@withContext UnlockResult.Wait(wait)
        val header = try {
            targetFiles.readHeader()
        } catch (e: CorruptVaultException) {
            return@withContext UnlockResult.Corrupt
        }
        try {
            val vk = VaultCrypto.unlockWithRecovery(header, phrase)
            session.lock()
            registry.setActive(id)
            targetFiles.writeUnlockState(UnlockState(0, 0, 0L))
            container.sync.clearHeaderDirty()
            session.unlock(vk)
            UnlockResult.Success
        } catch (e: InvalidRecoveryPhraseException) {
            UnlockResult.InvalidPhrase
        } catch (e: AuthenticationFailedException) {
            val s = targetFiles.readUnlockState()
            targetFiles.writeUnlockState(UnlockState(s.failedAttempts + 1, wallClock(), elapsedClock()))
            UnlockResult.WrongPassword
        }
    }

    /**
     * حذف خزانه با تأیید صریح کاربر (تأیید در UI گرفته می‌شود). هدر، بیومتریک و همهٔ
     * ورودی‌های آن خزانه برای همیشه پاک می‌شوند؛ حذف آخرین خزانه ممنوع است.
     * اگر خزانهٔ فعال حذف شود، نشست قفل و اولین خزانهٔ باقی‌مانده فعال می‌شود.
     */
    suspend fun deleteVault(id: String): VaultDeleteResult = withContext(Dispatchers.Default) {
        val all = registry.vaults()
        if (all.none { it.id == id }) return@withContext VaultDeleteResult.NotFound
        if (all.size <= 1) return@withContext VaultDeleteResult.LastVault
        val wasActive = registry.activeId() == id
        if (wasActive) {
            session.lock()
            registry.setActive(all.first { it.id != id }.id)
        }
        runCatching { container.itemStore.wipeVault(id) }
        filesFor(id).deleteVaultDir()
        // فایل رمزگشایی‌شدهٔ پیوستِ این خزانه ممکن است در کش مانده باشد.
        container.clearAttachmentCache()
        registry.remove(id)
        // پین فقط به یک خزانه تعلق دارد؛ اگر پینِ خزانهٔ حذف‌شده بود، غیرفعال می‌شود.
        if (container.pinUnlock.pinVaultId() == id) container.pinUnlock.disablePin()
        container.sync.clearHeaderDirty()
        VaultDeleteResult.Ok(registry.activeId())
    }

    fun remainingWaitMillis(
        now: Long = wallClock(),
        elapsedNow: Long = elapsedClock(),
    ): Long = remainingWaitMillis(files, now, elapsedNow)

    private fun remainingWaitMillis(
        targetFiles: VaultFiles,
        now: Long = wallClock(),
        elapsedNow: Long = elapsedClock(),
    ): Long {
        val state = targetFiles.readUnlockState()
        val delay = UnlockBackoff.delayMillis(state.failedAttempts)
        if (delay <= 0L) return 0L

        // اولویت با ساعت یکنواخت (elapsedRealtime) تا جلو بردن ساعت دیواری Backoff را دور نزند.
        if (state.lastFailureElapsed > 0L) {
            val monoPassed = elapsedNow - state.lastFailureElapsed
            if (monoPassed >= 0L) {
                return (delay - monoPassed).coerceAtLeast(0L)
            }
            // دستگاه ریستارت شده: fallback محافظه‌کارانه به ساعت دیواری (با سقف delay)
        }
        val wallPassed = now - state.lastFailureAt
        if (wallPassed < 0L) return delay
        return (delay - wallPassed).coerceAtLeast(0L)
    }

    private fun recordFailure() {
        val s = files.readUnlockState()
        files.writeUnlockState(UnlockState(s.failedAttempts + 1, wallClock(), elapsedClock()))
    }

    private fun recordSuccess() = files.writeUnlockState(UnlockState(0, 0, 0L))

    /** ساخت خزانه در حافظه. روی دیسک نوشته نمی‌شود تا کاربر عبارت بازیابی را تأیید کند ([commit]). */
    suspend fun create(password: CharArray): CreatedVault = withContext(Dispatchers.Default) {
        VaultCrypto.create(password, KdfCalibrator.pickOnDevice())
    }

    /** نوشتن هدر روی دیسک و باز کردن نشست. کلید ورودی تحویل نشست می‌شود و پاک می‌شود. */
    fun commit(created: CreatedVault) {
        files.writeHeader(created.header)
        recordSuccess()
        session.unlock(created.vaultKey)
    }

    /** بازیابی مستقیم هدر و کلید خزانهٔ قبلی (با حفظ دقیق رمز اصلی و ۲۴ کلمهٔ قبلی کاربر). */
    fun restoreExistingVault(header: app.solina.refuge.core.crypto.VaultHeader, vaultKey: ByteArray) {
        files.writeHeader(header)
        recordSuccess()
        session.unlock(vaultKey)
    }

    /** بازیابی بکاپ قدیمی با حفظ عبارت بازیابی ۲۴ کلمه‌ای قبلی و رمز اصلی کاربر. */
    suspend fun createWithExistingRecovery(password: CharArray, phrase: String): UnlockResult =
        withContext(Dispatchers.Default) {
            try {
                val created = VaultCrypto.createWithExistingRecovery(password, phrase, KdfCalibrator.pickOnDevice())
                files.writeHeader(created.header)
                recordSuccess()
                session.unlock(created.vaultKey)
                UnlockResult.Success
            } catch (e: InvalidRecoveryPhraseException) {
                UnlockResult.InvalidPhrase
            }
        }

    suspend fun unlockWithPassword(password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        if (container.integrityBlocked) return@withContext UnlockResult.IntegrityFailed
        val wait = remainingWaitMillis()
        if (wait > 0) return@withContext UnlockResult.Wait(wait)
        val header = try {
            files.readHeader()
        } catch (e: CorruptVaultException) {
            return@withContext UnlockResult.Corrupt
        }
        try {
            session.unlock(VaultCrypto.unlockWithPassword(header, password))
            recordSuccess()
            UnlockResult.Success
        } catch (e: AuthenticationFailedException) {
            recordFailure()
            UnlockResult.WrongPassword
        }
    }

    suspend fun unlockWithRecovery(phrase: String): UnlockResult = withContext(Dispatchers.Default) {
        if (container.integrityBlocked) return@withContext UnlockResult.IntegrityFailed
        val wait = remainingWaitMillis()
        if (wait > 0) return@withContext UnlockResult.Wait(wait)
        val header = try {
            files.readHeader()
        } catch (e: CorruptVaultException) {
            return@withContext UnlockResult.Corrupt
        }
        try {
            session.unlock(VaultCrypto.unlockWithRecovery(header, phrase))
            recordSuccess()
            UnlockResult.Success
        } catch (e: InvalidRecoveryPhraseException) {
            UnlockResult.InvalidPhrase
        } catch (e: AuthenticationFailedException) {
            recordFailure()
            UnlockResult.WrongPassword
        }
    }

    /** تعیین رمز اصلی جدید (مثلاً بعد از بازیابی). فقط Vault Key دوباره پیچیده می‌شود. */
    suspend fun setNewPassword(newPassword: CharArray): Boolean = withContext(Dispatchers.Default) {
        val vk = session.keyCopy() ?: return@withContext false
        try {
            val updated = VaultCrypto.changePassword(files.readHeader(), vk, newPassword, KdfCalibrator.pickOnDevice())
            files.writeHeader(updated)
            true
        } finally {
            vk.wipe()
        }
    }

    /** تغییر رمز اصلی پس از تأیید رمز فعلی (بدون تغییر هویت خزانه یا نیاز به رمزگذاری مجدد آیتم‌ها). */
    suspend fun verifyAndChangePassword(currentPassword: CharArray, newPassword: CharArray): Boolean =
        withContext(Dispatchers.Default) {
            val header = try {
                files.readHeader()
            } catch (e: CorruptVaultException) {
                return@withContext false
            }
            val vk = try {
                VaultCrypto.unlockWithPassword(header, currentPassword)
            } catch (e: AuthenticationFailedException) {
                return@withContext false
            }
            try {
                val updated = VaultCrypto.changePassword(header, vk, newPassword, KdfCalibrator.pickOnDevice())
                files.writeHeader(updated)
                true
            } finally {
                vk.wipe()
            }
        }

    // ───── بیومتریک ─────

    // ───── پین (میان‌بر؛ جایگزین رمز اصلی نیست، نگاه کنید به PinUnlock.kt) ─────

    /**
     * آیا برای خزانهٔ فعال پین تنظیم شده است؟ پین فقط به یک خزانه تعلق دارد؛
     * با تعویض خزانه، پینِ خزانهٔ دیگر کار نمی‌کند.
     */
    fun hasPin() = container.pinUnlock.hasPinFor(registry.activeId())

    /** تنظیم یا تغییر پین؛ فقط وقتی خزانه باز است (Vault Key در نشست است). */
    fun setupPin(pin: CharArray): Boolean {
        val vk = session.keyCopy() ?: return false
        try {
            return container.pinUnlock.setupPin(pin, vk, registry.activeId())
        } finally {
            vk.wipe()
        }
    }

    /** بررسی پین فعلی بدون تغییر نشست؛ برای تأیید هنگام تغییر پین. */
    fun verifyPin(pin: CharArray): Boolean = container.pinUnlock.verifyPin(pin, registry.activeId())

    fun disablePin() = container.pinUnlock.disablePin()

    /** باز کردن با پین؛ در صورت موفقیت نشست باز می‌شود (مثل رمز اصلی). */
    suspend fun unlockWithPin(pin: CharArray): PinResult = withContext(Dispatchers.Default) {
        if (container.integrityBlocked) return@withContext PinResult.IntegrityFailed
        when (val r = container.pinUnlock.unlockWithPin(pin, registry.activeId())) {
            is PinResult.Success -> {
                session.unlock(r.vaultKey)
                r
            }
            else -> r
        }
    }

    fun biometricAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun enableBiometric(activity: FragmentActivity, onDone: (Boolean) -> Unit) {
        val vk = session.keyCopy() ?: return onDone(false)
        val cipher = try {
            BiometricKeystore.newEnrollCipher(registry.activeId())
        } catch (e: Exception) {
            vk.wipe()
            return onDone(false)
        }
        prompt(activity, "فعال‌سازی اثر انگشت", cipher, onFail = { vk.wipe(); onDone(false) }) { c ->
            try {
                files.writeBiometric(BiometricKeystore.finishEnroll(c, vk))
                onDone(true)
            } catch (e: Exception) {
                BiometricKeystore.deleteKey()
                onDone(false)
            } finally {
                vk.wipe()
            }
        }
    }

    fun disableBiometric() {
        files.clearBiometric()
        BiometricKeystore.deleteKey()
    }

    /** @param onResult true اگر باز شد؛ false در لغو یا خطا (رمز اصلی همیشه در دسترس است).
     *  @param onIntegrityFailed وقتی امضای release تأیید نشده باشد، به‌جای تلاش بیومتریک صدا زده می‌شود. */
    fun unlockWithBiometric(
        activity: FragmentActivity,
        onResult: (Boolean) -> Unit,
        onIntegrityFailed: (() -> Unit)? = null,
    ) {
        if (container.integrityBlocked) {
            onIntegrityFailed?.invoke()
            return
        }
        val blob = files.readBiometric() ?: return onResult(false)
        val cipher = try {
            BiometricKeystore.newUnlockCipher(blob, registry.activeId())
        } catch (e: Exception) {
            // اثر انگشت جدید اضافه شده یا کلید از بین رفته: میان‌بر غیرفعال می‌شود، رمز اصلی می‌ماند.
            disableBiometric()
            return onResult(false)
        }
        prompt(activity, "باز کردن Refuge", cipher, onFail = { onResult(false) }) { c ->
            try {
                session.unlock(BiometricKeystore.finishUnlock(c, blob))
                onResult(true)
            } catch (e: Exception) {
                // blob قدیمیِ بدون AAD خزانه یا خراب: برای همیشه باز نمی‌شود؛ میان‌بر
                // غیرفعال می‌شود تا کاربر با رمز اصلی بیومتریک را دوباره فعال کند.
                disableBiometric()
                onResult(false)
            }
        }
    }

    private fun prompt(
        activity: FragmentActivity,
        title: String,
        cipher: javax.crypto.Cipher,
        onFail: () -> Unit,
        onSuccess: (javax.crypto.Cipher) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) onFail() else onSuccess(c)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("استفاده از رمز اصلی")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
