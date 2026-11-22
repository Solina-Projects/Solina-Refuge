package app.solina.refuge.core.backup

import app.solina.refuge.core.storage.ItemPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * بکاپ چندخزانه‌ای: بکاپ خزانهٔ فعال برچسب خزانهٔ مبدأ را دارد؛
 * بکاپ‌های قدیمیِ بدون برچسب هنوز بازیابی می‌شوند و قالب RFGB v1 عوض نشده است.
 */
class MultiVaultBackupTest {
    private val pw = "backup-password".toCharArray()
    private fun login(title: String) = ItemPayload(title = title, username = "u", password = "pw-12345678")

    @Test
    fun activeVaultBackupCarriesVaultTag() {
        val bytes = Backup.encode(listOf(login("a")), pw, 1_700_000_000_000L, vaultId = "vault-work", vaultName = "کار")
        val decoded = Backup.decodeFull(bytes, pw)
        assertEquals("vault-work", decoded.vaultId)
        assertEquals("کار", decoded.vaultName)
        assertEquals(listOf("a"), decoded.items.map { it.title })
        // همان قالب v1: سرآیند RFGB و نسخهٔ ۱.
        assertTrue(Backup.isRefugeBackup(bytes))
        assertEquals(1, Backup.inspect(bytes).version)
    }

    @Test
    fun legacyBackupWithoutVaultTagStillRestores() {
        // بکاپ قدیمی: encode بدون vaultId/vaultName (مثل نسخهٔ ۱٫۰٫۰).
        val bytes = Backup.encode(listOf(login("old")), pw, 1_700_000_000_000L)
        val decoded = Backup.decodeFull(bytes, pw)
        assertNull(decoded.vaultId)
        assertNull(decoded.vaultName)
        assertEquals(listOf("old"), decoded.items.map { it.title })
        assertEquals(listOf("old"), Backup.decode(bytes, pw).map { it.title })
    }

    @Test
    fun authenticatorBackupCarriesVaultTag() {
        val otp = ItemPayload(title = "otp", totpSecret = "JBSWY3DPEHPK3PXP")
        val bytes = Backup.encodeAuthenticatorOnly(listOf(otp), pw, 1_700_000_000_000L, vaultId = "vault-b")
        assertEquals("vault-b", Backup.decodeFull(bytes, pw).vaultId)
    }
}
