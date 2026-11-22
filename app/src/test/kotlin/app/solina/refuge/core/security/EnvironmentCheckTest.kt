package app.solina.refuge.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** تست‌های منطق خالص بررسی‌های محیطی؛ بدون نیاز به دستگاه یا فریم‌ورک اندروید. */
class EnvironmentCheckTest {

    private fun rootCheck(
        tags: String? = "release-keys",
        su: (String) -> Boolean = { false },
        installed: (String) -> Boolean = { false },
    ): List<RootSignal> = EnvironmentCheck.checkRoot(
        buildTags = tags,
        suPaths = listOf("/system/xbin/su"),
        suExists = su,
        rootPackages = listOf("com.topjohnwu.magisk"),
        packageInstalled = installed,
    )

    @Test fun testKeysFlagDetected() {
        assertEquals(listOf(RootSignal.TestKeys), rootCheck(tags = "test-keys"))
    }

    @Test fun releaseKeysNotFlagged() {
        assertEquals(emptyList<RootSignal>(), rootCheck(tags = "release-keys"))
    }

    @Test fun nullBuildTagsNotFlagged() {
        assertEquals(emptyList<RootSignal>(), rootCheck(tags = null))
    }

    @Test fun suBinaryDetected() {
        assertEquals(listOf(RootSignal.SuBinary), rootCheck(su = { it == "/system/xbin/su" }))
    }

    @Test fun rootAppDetected() {
        assertEquals(listOf(RootSignal.RootApp), rootCheck(installed = { it == "com.topjohnwu.magisk" }))
    }

    @Test fun multipleSignalsAllReported() {
        val signals = rootCheck(
            tags = "test-keys",
            su = { true },
            installed = { true },
        )
        assertEquals(listOf(RootSignal.TestKeys, RootSignal.SuBinary, RootSignal.RootApp), signals)
    }

    @Test fun cleanDeviceNoSignals() {
        assertEquals(emptyList<RootSignal>(), rootCheck())
        assertFalse(EnvironmentStatus(emptyList(), false, IntegrityResult.Ok).isProbablyRooted)
        assertTrue(EnvironmentStatus(listOf(RootSignal.SuBinary), false, IntegrityResult.Ok).isProbablyRooted)
    }

    @Test fun fingerprintNormalizationIgnoresColonsAndCase() {
        assertTrue(EnvironmentCheck.fingerprintsMatch("03:B6:00:65", "03b60065"))
        assertTrue(EnvironmentCheck.fingerprintsMatch("03B60065", "03:B6:00:65"))
        assertFalse(EnvironmentCheck.fingerprintsMatch("03B60065", "03B60066"))
        assertFalse(EnvironmentCheck.fingerprintsMatch("03B60065", null))
        assertFalse(EnvironmentCheck.fingerprintsMatch("03B60065", ""))
    }

    @Test fun integrityBlockedOnlyOnMismatchOrUnknown() {
        assertTrue(EnvironmentStatus(emptyList(), false, IntegrityResult.Mismatch("AABB")).integrityBlocked)
        assertTrue(EnvironmentStatus(emptyList(), false, IntegrityResult.Mismatch(null)).integrityBlocked)
        assertTrue(EnvironmentStatus(emptyList(), false, IntegrityResult.Unknown).integrityBlocked)
        assertFalse(EnvironmentStatus(emptyList(), false, IntegrityResult.Ok).integrityBlocked)
        assertFalse(EnvironmentStatus(emptyList(), false, IntegrityResult.Skipped).integrityBlocked)
    }

    @Test fun debuggerFlagPassthrough() {
        assertTrue(EnvironmentCheck.isDebuggerAttached(debuggerConnected = true, waitingForDebugger = false))
        assertTrue(EnvironmentCheck.isDebuggerAttached(debuggerConnected = false, waitingForDebugger = true))
        assertTrue(EnvironmentCheck.isDebuggerAttached(debuggerConnected = true, waitingForDebugger = true))
        assertFalse(EnvironmentCheck.isDebuggerAttached(debuggerConnected = false, waitingForDebugger = false))
    }
}
