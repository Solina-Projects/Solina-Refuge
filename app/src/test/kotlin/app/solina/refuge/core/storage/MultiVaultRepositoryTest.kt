package app.solina.refuge.core.storage

import app.solina.refuge.core.crypto.randomBytes
import app.solina.refuge.core.security.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * چندخزانه‌ای در سطح Repository: ورودی‌های هر خزانه فقط با کلید همان خزانه و فقط
 * وقتی همان خزانه فعال است دیده می‌شوند؛ حذف یا ویرایش در یک خزانه به دیگری نمی‌رسد.
 */
class MultiVaultRepositoryTest {
    private var t = 1_000L
    private fun login(title: String) = ItemPayload(title = title, username = "u", password = "Tr0ub4dor&3-horse-battery")

    private fun repo(
        store: FakeStore,
        key: ByteArray,
        vaultId: String,
        prefix: String,
    ): VaultRepository {
        var n = 0
        val session = VaultSession().also { it.unlock(key) }
        return VaultRepository(store, session, clock = { t += 10; t }, newId = { "$prefix${++n}" }, vaultId = { vaultId })
    }

    @Test
    fun itemsAreScopedPerVault() = runBlocking {
        val store = FakeStore()
        val keyA = randomBytes(32)
        val keyB = randomBytes(32)
        val repoA = repo(store, keyA, "vault-a", "a")
        val repoB = repo(store, keyB, "vault-b", "b")

        repoA.add(login("A-only"))
        repoB.load()
        assertTrue("vault B must not see vault A items", repoB.items.value.isEmpty())
        repoA.load()
        assertEquals(listOf("A-only"), repoA.items.value.map { it.payload.title })

        repoB.add(login("B-only"))
        repoA.load()
        assertEquals(listOf("A-only"), repoA.items.value.map { it.payload.title })
        repoB.load()
        assertEquals(listOf("B-only"), repoB.items.value.map { it.payload.title })
    }

    @Test
    fun wrongKeyForSameVaultCountsUnreadable() = runBlocking {
        val store = FakeStore()
        val keyA = randomBytes(32)
        val keyB = randomBytes(32)
        repo(store, keyA, "vault-a", "a").add(login("secret"))
        // همان خزانه، ولی کلید خزانهٔ دیگر: ردیف هست ولی باز نمی‌شود.
        val wrongKey = repo(store, keyB, "vault-a", "x")
        wrongKey.load()
        assertTrue(wrongKey.items.value.isEmpty())
        assertEquals(1, wrongKey.unreadable.value)
    }

    @Test
    fun deleteAndHistoryAreScopedPerVault() = runBlocking {
        val store = FakeStore()
        val repoA = repo(store, randomBytes(32), "vault-a", "a")
        val repoB = repo(store, randomBytes(32), "vault-b", "b")
        val itemA = repoA.add(login("a1"))
        repoB.add(login("b1"))
        repoA.update(itemA.id, login("a1-edited"))
        assertEquals(1, repoA.history(itemA.id).size)
        assertTrue("vault B has no history for a vault A item", repoB.history(itemA.id).isEmpty())

        repoA.delete(itemA.id)
        repoA.load(); repoB.load()
        assertTrue(repoA.items.value.isEmpty())
        assertEquals("deleting in A must not touch B", listOf("b1"), repoB.items.value.map { it.payload.title })
        assertEquals("only vault A has a trashed item", 1, repoA.trashedItems().size)
        assertTrue(repoB.trashedItems().isEmpty())
        // بازگردانی از سطل زبالهٔ A کار می‌کند و به B نمی‌رسد.
        val restoredA = repoA.restoreTrashed(itemA.id)
        assertEquals("a1-edited", restoredA?.payload?.title)
        repoA.load()
        assertEquals(listOf("a1-edited"), repoA.items.value.map { it.payload.title })
        repoB.load()
        assertEquals(listOf("b1"), repoB.items.value.map { it.payload.title })
    }

    @Test
    fun wipeVaultRemovesOnlyThatVault() = runBlocking {
        val store = FakeStore()
        val repoA = repo(store, randomBytes(32), "vault-a", "a")
        val repoB = repo(store, randomBytes(32), "vault-b", "b")
        repoA.add(login("a1"))
        repoB.add(login("b1"))
        store.wipeVault("vault-a")
        repoA.load(); repoB.load()
        assertTrue(repoA.items.value.isEmpty())
        assertEquals(listOf("b1"), repoB.items.value.map { it.payload.title })
    }

    @Test
    fun taskDuesAreScopedPerVault() = runBlocking {
        val store = FakeStore()
        val repoA = repo(store, randomBytes(32), "vault-a", "a")
        val repoB = repo(store, randomBytes(32), "vault-b", "b")
        repoA.add(ItemPayload(title = "task-a", kind = ItemKind.Task, dueAt = 5_000L))
        repoB.add(ItemPayload(title = "task-b", kind = ItemKind.Task, dueAt = 6_000L))
        assertEquals(listOf(5_000L), repoA.taskDues().map { it.dueAt })
        assertEquals(listOf(6_000L), repoB.taskDues().map { it.dueAt })
    }
}
