package app.solina.refuge.core.security

import app.solina.refuge.core.crypto.MultiVaultDesign
import app.solina.refuge.core.crypto.VaultCrypto
import app.solina.refuge.core.crypto.KdfParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * تست‌های فهرست خزانه‌ها و مهاجرت نصب تک‌خزانه‌ای.
 * VaultRegistry و VaultFiles هیچ وابستگی اندرویدی ندارند.
 */
class VaultRegistryTest {
    private var n = 0
    private fun freshRoot(): File = Files.createTempDirectory("vault-reg-test").toFile()
    private fun registry(root: File) = VaultRegistry(root, clock = { 1_700_000_000_000L }, newId = { "v${++n}" })

    @Test
    fun freshRegistryHasDefaultVaultActive() {
        val reg = registry(freshRoot())
        reg.ensure()
        assertEquals(listOf("default"), reg.vaults().map { it.id })
        assertEquals("default", reg.activeId())
        assertEquals(VaultRegistry.DEFAULT_VAULT_NAME_FA, reg.active()!!.name)
    }

    @Test
    fun createSetActiveAndList() {
        val reg = registry(freshRoot())
        val info = reg.reserve("کار")
        assertEquals("v1", info.id)
        reg.register(info)
        assertEquals(listOf("default", "v1"), reg.vaults().map { it.id })
        reg.setActive("v1")
        assertEquals("v1", reg.activeId())
        assertEquals("کار", reg.active()!!.name)
    }

    @Test
    fun reserveValidatesNameAndMaxVaults() {
        val reg = registry(freshRoot())
        try { reg.reserve("   "); fail("blank name must be rejected") } catch (e: IllegalArgumentException) { }
        for (i in 1 until MultiVaultDesign.MAX_VAULTS) {
            reg.register(reg.reserve("خزانهٔ $i"))
        }
        assertEquals(MultiVaultDesign.MAX_VAULTS, reg.vaults().size)
        try { reg.reserve("یکی دیگر"); fail("over the max must be rejected") } catch (e: IllegalStateException) { }
    }

    @Test
    fun deleteRefusesLastVault() {
        val reg = registry(freshRoot())
        try { reg.delete("default"); fail("deleting the last vault must be refused") } catch (e: IllegalStateException) { }
        val second = reg.reserve("دوم")
        reg.register(second)
        val newActive = reg.delete("default")
        assertEquals("v1", newActive)
        assertEquals(listOf("v1"), reg.vaults().map { it.id })
        assertEquals("v1", reg.activeId())
    }

    @Test
    fun deleteActiveVaultSwitchesToFirstRemaining() {
        val reg = registry(freshRoot())
        val a = reg.reserve("الف"); reg.register(a)
        val b = reg.reserve("ب"); reg.register(b)
        reg.setActive("v2")
        val newActive = reg.delete("v2")
        assertEquals("default", newActive)
        assertEquals(listOf("default", "v1"), reg.vaults().map { it.id })
    }

    @Test
    fun registryPersistsAcrossInstances() {
        val root = freshRoot()
        val reg = registry(root)
        val info = reg.reserve("پایدار"); reg.register(info)
        reg.setActive("v1")
        val reg2 = VaultRegistry(root)
        assertEquals(listOf("default", "v1"), reg2.vaults().map { it.id })
        assertEquals("v1", reg2.activeId())
        assertEquals("پایدار", reg2.active()!!.name)
    }

    @Test
    fun unknownVaultCannotBeActivated() {
        val reg = registry(freshRoot())
        try { reg.setActive("nope"); fail() } catch (e: IllegalStateException) { }
    }

    @Test
    fun legacyMigrationMovesHeaderIntoDefaultVaultDir() {
        val root = freshRoot()
        // خزانهٔ تک‌خزانه‌ایِ واقعی: هدر در ریشهٔ پوشه.
        val fast = KdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
        val created = VaultCrypto.create("legacy-password".toCharArray(), fast)
        File(root, "vault.hdr").writeBytes(created.header.encode())
        File(root, "biometric.bin").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(VaultFiles.migrateLegacy(root))
        assertFalse(File(root, "vault.hdr").exists())
        val moved = File(root, "vault-default/vault.hdr")
        assertTrue(moved.isFile)
        assertTrue(VaultFiles.forVault(root, "default").hasVault())
        // هدرِ منتقل‌شده هنوز با همان رمز باز می‌شود؛ هیچ رمزگذاریِ دوباره‌ای نشده است.
        val header = VaultFiles.forVault(root, "default").readHeader()
        assertTrue(VaultCrypto.unlockWithPassword(header, "legacy-password".toCharArray()).contentEquals(created.vaultKey))
        assertTrue(File(root, "vault-default/biometric.bin").isFile)
        // اجرای دوبارهٔ مهاجرت بی‌اثر است.
        assertFalse(VaultFiles.migrateLegacy(root))
    }

    @Test
    fun migrateLegacyIsNoopWithoutLegacyHeader() {
        val root = freshRoot()
        assertFalse(VaultFiles.migrateLegacy(root))
    }

    @Test
    fun corruptRegistryRebuildsFromDisk() {
        val root = freshRoot()
        val reg = registry(root)
        val info = reg.reserve("دوم"); reg.register(info)
        val fast = KdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
        VaultFiles.forVault(root, "default").writeHeader(VaultCrypto.create("pw".toCharArray(), fast).header)
        VaultFiles.forVault(root, "v1").writeHeader(VaultCrypto.create("pw".toCharArray(), fast).header)
        File(root, VaultRegistry.FILE_NAME).writeText("garbage\nnot-a-registry", Charsets.UTF_8)
        val reg2 = VaultRegistry(root)
        assertEquals(listOf("default", "v1"), reg2.vaults().map { it.id })
        assertEquals("default", reg2.activeId())
    }

    @Test
    fun invalidVaultIdIsRejected() {
        val root = freshRoot()
        try { VaultFiles.forVault(root, "../escape"); fail() } catch (e: IllegalArgumentException) { }
        try { VaultFiles.forVault(root, "a/b"); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun wipeAllRemovesEveryVaultDirAndRegistry() {
        val root = freshRoot()
        val reg = registry(root)
        val info = reg.reserve("دوم"); reg.register(info)
        VaultFiles.forVault(root, "default").writeHeader(
            VaultCrypto.create("pw".toCharArray(), KdfParams(64, 1, 1)).header,
        )
        reg.wipeAll()
        assertFalse(File(root, "vault-default").exists())
        assertFalse(File(root, VaultRegistry.FILE_NAME).exists())
        // بعد از wipe، ensure دوباره از صفر شروع می‌کند.
        reg.ensure()
        assertEquals(listOf("default"), reg.vaults().map { it.id })
    }
}
