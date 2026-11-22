package app.solina.refuge.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ایزولاسیون کلید چندخزانه‌ای: هر خزانه کلید (Vault Key) مخصوص خودش را دارد؛
 * کلید خزانهٔ A هرگز نمی‌تواند ورودیِ خزانهٔ B را باز کند — حتی اگر رمز هر دو یکی باشد.
 */
class MultiVaultKeyIsolationTest {
    /** پارامتر سبک فقط برای سرعت تست؛ مقدار واقعی KdfParams.DEFAULT است. */
    private val fast = KdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val pwA = "correct horse battery staple A".toCharArray()
    private val pwB = "correct horse battery staple B".toCharArray()

    @Test
    fun samePasswordStillGivesDifferentVaultKeys() {
        val a = VaultCrypto.create(pwA, fast)
        val b = VaultCrypto.create(pwA, fast)
        assertFalse("two vaults with the same password must not share a key",
            a.vaultKey.contentEquals(b.vaultKey))
        assertFalse("two vaults must not share a salt",
            a.header.salt.contentEquals(b.header.salt))
    }

    @Test
    fun vaultAKeyCannotOpenVaultBItems() {
        val a = VaultCrypto.create(pwA, fast)
        val b = VaultCrypto.create(pwB, fast)
        val sealed = VaultCrypto.sealItem(a.vaultKey, "item-1", 1, 1, "top-secret".toByteArray(Charsets.UTF_8))
        try {
            VaultCrypto.openItem(b.vaultKey, "item-1", 1, 1, sealed)
            fail("vault B key must not open a vault A item")
        } catch (e: AuthenticationFailedException) {
            // مورد انتظار: احراز هویت AEAD شکست می‌خورد.
        }
        val plain = VaultCrypto.openItem(a.vaultKey, "item-1", 1, 1, sealed)
        assertEquals("top-secret", String(plain, Charsets.UTF_8))
    }

    @Test
    fun vaultAPasswordCannotUnlockVaultBHeader() {
        val a = VaultCrypto.create(pwA, fast)
        val b = VaultCrypto.create(pwB, fast)
        try {
            VaultCrypto.unlockWithPassword(b.header, pwA)
            fail("vault A password must not unlock vault B header")
        } catch (e: AuthenticationFailedException) {
        }
        assertTrue(VaultCrypto.unlockWithPassword(a.header, pwA).contentEquals(a.vaultKey))
    }

    @Test
    fun recoveryWordsArePerVault() {
        val a = VaultCrypto.create(pwA, fast)
        val b = VaultCrypto.create(pwB, fast)
        val wordsA = a.recoveryWords.joinToString(" ")
        assertTrue(VaultCrypto.unlockWithRecovery(a.header, wordsA).contentEquals(a.vaultKey))
        try {
            VaultCrypto.unlockWithRecovery(b.header, wordsA)
            fail("vault A recovery words must not unlock vault B")
        } catch (e: Exception) {
            // AuthenticationFailedException یا InvalidRecoveryPhraseException — هر دو یعنی «باز نشد».
        }
    }
}
