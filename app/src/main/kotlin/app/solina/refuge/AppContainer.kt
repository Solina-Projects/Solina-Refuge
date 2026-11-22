package app.solina.refuge

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.solina.refuge.auth.ExternalAccountManager
import app.solina.refuge.core.passkey.PasskeyManager
import app.solina.refuge.core.security.BiometricKeystore
import app.solina.refuge.core.security.EnvironmentCheck
import app.solina.refuge.core.security.EnvironmentStatus
import app.solina.refuge.core.security.IntegrityResult
import app.solina.refuge.core.security.PinUnlockManager
import app.solina.refuge.core.security.SecurityPrefs
import app.solina.refuge.core.security.VaultFiles
import app.solina.refuge.core.security.VaultRegistry
import app.solina.refuge.core.security.VaultSession
import app.solina.refuge.core.storage.RoomItemStore
import app.solina.refuge.core.storage.RefugeDatabase
import app.solina.refuge.core.storage.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** ظرف وابستگی‌های سراسری Refuge با جداسازی لایه‌های امنیتی، داده و همگام‌سازی. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    /** پوشهٔ ریشهٔ همهٔ خزانه‌ها (`vault-<id>/` برای هر خزانه + فایل `vaults.txt`). */
    val vaultRoot = File(appContext.filesDir, "refuge")
    /**
     * فهرست خزانه‌ها. در ساخت، نصب تک‌خزانه‌ایِ قدیمی مهاجرت می‌کند: فایل‌های خزانهٔ
     * موجود به پوشهٔ `vault-default` می‌روند و همان خزانه «خزانهٔ اصلی» می‌شود —
     * بدون رمزگذاریِ دوباره و بدون از دست رفتن داده.
     */
    val vaultRegistry = VaultRegistry(vaultRoot).also {
        VaultFiles.migrateLegacy(vaultRoot)
        it.ensure()
    }
    /** فایل‌های خزانهٔ فعال (با تعویض خزانه، به پوشهٔ خزانهٔ جدید اشاره می‌کند). */
    val files: VaultFiles get() = VaultFiles.forVault(vaultRoot, vaultRegistry.activeId())
    val securityPrefs = SecurityPrefs(appContext)
    val pinUnlock = PinUnlockManager(appContext)
    val passkeys = PasskeyManager(appContext)
    val externalAccounts = ExternalAccountManager(appContext)
    val session = VaultSession(clock = SystemClock::elapsedRealtime)
    /**
     * وضعیت محیطی دستگاه؛ یک بار در شروع برنامه حساب می‌شود.
     * روت و دیباگر فقط هشدارند؛ فقط عدم‌تأیید امضا در بیلد release باز کردن خزانه را ممنوع می‌کند.
     * (DiagLog.init پیش از ساخت container در RefugeApplication.onCreate صدا زده شده است.)
     */
    val environment: EnvironmentStatus = EnvironmentCheck.checkAll(appContext).also { env ->
        if (env.isProbablyRooted) DiagLog.log("ENV", "root signals: ${env.rootSignals.joinToString(",")}")
        if (env.debuggerAttached) DiagLog.log("ENV", "debugger attached")
        when (env.integrity) {
            IntegrityResult.Skipped -> DiagLog.log("ENV", "integrity check skipped (debug)")
            IntegrityResult.Ok -> DiagLog.log("ENV", "release signature ok")
            is IntegrityResult.Mismatch -> DiagLog.log("ENV", "release signature MISMATCH")
            IntegrityResult.Unknown -> DiagLog.log("ENV", "release signature unknown")
        }
    }

    /** در release و فقط وقتی امضا تأیید نشد، باز کردن خزانه ممنوع است. */
    val integrityBlocked: Boolean get() = environment.integrityBlocked
    val database by lazy { RefugeDatabase.open(appContext) }
    val itemStore by lazy { RoomItemStore(database) }
    val repository by lazy { VaultRepository(itemStore, session, vaultId = { vaultRegistry.activeId() }) }
    val sync = app.solina.refuge.sync.SyncManager(appContext, { files }, session, { itemStore }, { repository }, { vaultRegistry.activeId() })

    private val handler = Handler(Looper.getMainLooper())
    private val lockCheck = Runnable { session.enforceTimeout(securityPrefs.autoLockMillis()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { session.unlocked.collect { if (!it) repository.clear() } }
    }

    fun scheduleAutoLock() {
        handler.removeCallbacks(lockCheck)
        val timeout = securityPrefs.autoLockMillis()
        if (timeout <= 0L) {
            session.lock()
            return
        }
        session.onBackgrounded()
        handler.postDelayed(lockCheck, timeout + 500L)
    }

    fun cancelAutoLock() = handler.removeCallbacks(lockCheck)

    /**
     * پاک‌کردن فایل‌های رمزگشایی‌شدهٔ پیوست از کش خصوصی برنامه. این فایل‌ها متنِ اصلیِ
     * پیوست‌اند و باز کردن پیوست با برنامهٔ دیگر آن‌ها را می‌سازد؛ پس پاک‌سازی امن
     * خزانه و حذف خزانه بدون پاک‌کردن آن‌ها ناقص می‌ماند.
     */
    fun clearAttachmentCache() {
        runCatching { File(appContext.cacheDir, "attachments").deleteRecursively() }
    }

    /**
     * خروج کامل و پاک‌سازی امن تمام اطلاعات محلی (دیتابیس با secure_delete + VACUUM،
     * فایل‌های هدر خزانه، کلید بیومتریک Keystore، اتصال همگام‌سازی و حساب‌های خارجی).
     */
    suspend fun wipeLocalVaultSecurely() {
        cancelAutoLock()
        session.lock()
        repository.clear()
        clearAttachmentCache()
        runCatching { itemStore.wipeAll() }
        runCatching { BiometricKeystore.deleteKey() }
        runCatching { pinUnlock.disablePin() }
        // همهٔ خزانه‌ها (پوشهٔ هر خزانه + فایل فهرست)، نه فقط خزانهٔ فعال.
        runCatching { vaultRegistry.wipeAll() }
        sync.disconnect()
        externalAccounts.clearAll()
        securityPrefs.clear()
        DiagLog.log("WIPE", "local vault securely wiped")
    }
}

class RefugeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        ErrorNotifier.createChannel(this)
        app.solina.refuge.core.notes.TaskNotifier.createChannel(this)
        app.solina.refuge.core.notes.TaskReminderScheduler.scheduleSweep(this)
        CrashReporter.install(this)
        DiagLog.startWatchdog(this)
        DiagLog.log("APP", "start")
        container = AppContainer(this)
    }
}
