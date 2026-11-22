package app.solina.refuge.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import app.solina.refuge.BuildConfig
import java.io.File
import java.security.MessageDigest

/** سیگنال‌های روت؛ فقط نام سیگنال ثبت می‌شود، بدون هیچ دادهٔ حساس. */
enum class RootSignal { TestKeys, SuBinary, RootApp }

/**
 * نتیجهٔ بررسی یکپارچگی امضا — فقط در بیلد release.
 * در بیلد debug بررسی کاملاً رد می‌شود ([Skipped]).
 */
sealed interface IntegrityResult {
    /** بیلد debug: بررسی انجام نمی‌شود. */
    data object Skipped : IntegrityResult
    /** امضای نصب‌شده با اثر انگشت انتشار هم‌خوانی دارد. */
    data object Ok : IntegrityResult
    /** امضا خوانده شد ولی با اثر انگشت انتشار فرق دارد. */
    data class Mismatch(val actualSha256Hex: String?) : IntegrityResult
    /** امضا خوانده نشد (خطای PackageManager یا نبود اثر انگشت مورد انتظار)؛
     * مثل Mismatch برخورد می‌شود (fail-closed). */
    data object Unknown : IntegrityResult
}

/** وضعیت محیطی دستگاه؛ یک بار در شروع برنامه حساب می‌شود. */
data class EnvironmentStatus(
    val rootSignals: List<RootSignal>,
    val debuggerAttached: Boolean,
    val integrity: IntegrityResult,
) {
    val isProbablyRooted: Boolean get() = rootSignals.isNotEmpty()
    /** در release و فقط وقتی امضا تأیید نشد، باز کردن خزانه ممنوع است. */
    val integrityBlocked: Boolean get() = integrity is IntegrityResult.Mismatch || integrity is IntegrityResult.Unknown
}

/**
 * بررسی‌های محیطی — همه فقط هشدارند، به‌جز بررسی یکپارچگی در بیلد release.
 *
 * - روت و دیباگر هرگز باعث قفل یا محدودیت نمی‌شوند: کاربر ممکن است رام سفارشی
 *   یا دستگاه روت‌شده داشته باشد و اپ باید کامل کار کند. فقط یک بنر هشدار در
 *   خانه (مرکز امنیت) نشان داده می‌شود و یک ورودی در DiagLog ثبت می‌شود.
 * - بررسی روت عمداً کوچک و صادقانه است: نبودِ این سیگنال‌ها «تمیز بودن» دستگاه
 *   را ثابت نمی‌کند؛ روتِ پنهان‌شده (مثلاً Magisk Hide) را نمی‌بیند.
 */
object EnvironmentCheck {
    /** مسیرهای رایج فایل su. */
    val defaultSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/cache/su",
        "/dev/su",
    )

    /** اپ‌های مدیریت روت که معمولاً فقط روی دستگاه روت‌شده نصب‌اند. */
    val defaultRootPackages = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "me.phh.superuser",
        "com.kingroot.kinguser",
        "com.kingo.root",
    )

    /**
     * بررسی روت با ورودی‌های قابل‌تزریق (برای تست واحد).
     * روی دستگاه، [checkRootOnDevice] را صدا بزنید.
     */
    fun checkRoot(
        buildTags: String? = Build.TAGS,
        suPaths: List<String> = defaultSuPaths,
        suExists: (String) -> Boolean = { File(it).exists() },
        rootPackages: List<String> = defaultRootPackages,
        packageInstalled: (String) -> Boolean = { false },
    ): List<RootSignal> {
        val signals = mutableListOf<RootSignal>()
        if (buildTags != null && buildTags.contains("test-keys")) signals += RootSignal.TestKeys
        if (suPaths.any(suExists)) signals += RootSignal.SuBinary
        if (rootPackages.any(packageInstalled)) signals += RootSignal.RootApp
        return signals
    }

    /** بررسی روت روی دستگاه؛ برای دیدن پکیج‌ها، نامشان در مانیفست زیر `<queries>` آمده است. */
    fun checkRootOnDevice(context: Context): List<RootSignal> {
        val pm = context.packageManager
        return checkRoot(packageInstalled = { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        })
    }

    fun isDebuggerAttached(
        debuggerConnected: Boolean = Debug.isDebuggerConnected(),
        waitingForDebugger: Boolean = Debug.waitingForDebugger(),
    ): Boolean = debuggerConnected || waitingForDebugger

    /** نرمال‌سازی اثر انگشت: حذف ':' و فاصله، بزرگ‌کردن حروف. */
    fun normalizeFingerprint(hex: String): String =
        hex.filter { it.isLetterOrDigit() }.uppercase()

    fun fingerprintsMatch(expectedHex: String, actualHex: String?): Boolean =
        actualHex != null && normalizeFingerprint(expectedHex) == normalizeFingerprint(actualHex)

    /** SHA-256 گواهی امضاکنندهٔ APK نصب‌شده، هگز بدون ':'؛ null اگر خوانده نشد. */
    fun installedSigningSha256Hex(context: Context): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return@runCatching null
            signing.apkContentsSigners ?: signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val cert = signatures?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** بررسی یکپارچگی؛ در debug همیشه [IntegrityResult.Skipped]. */
    fun checkIntegrity(context: Context): IntegrityResult {
        if (BuildConfig.DEBUG) return IntegrityResult.Skipped
        val expected = BuildConfig.RELEASE_SIGNING_SHA256
        if (expected.isBlank()) return IntegrityResult.Unknown
        val actual = installedSigningSha256Hex(context) ?: return IntegrityResult.Unknown
        return if (fingerprintsMatch(expected, actual)) IntegrityResult.Ok
        else IntegrityResult.Mismatch(actual)
    }

    /** همهٔ بررسی‌های محیطی در یک فراخوانی (در شروع برنامه). */
    fun checkAll(context: Context): EnvironmentStatus = EnvironmentStatus(
        rootSignals = checkRootOnDevice(context),
        debuggerAttached = isDebuggerAttached(),
        integrity = checkIntegrity(context),
    )
}
