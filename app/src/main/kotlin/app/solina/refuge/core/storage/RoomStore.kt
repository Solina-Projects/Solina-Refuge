package app.solina.refuge.core.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "items")
class ItemRow(
    @PrimaryKey val id: String,
    val rev: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val wrappedItemKey: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray,
    /** خزانهٔ مالک ردیف؛ مهاجرت ۲←۳ همهٔ ردیف‌های قدیمی را به خزانهٔ پیش‌فرض می‌دهد. */
    val vaultId: String = app.solina.refuge.core.crypto.MultiVaultDesign.DEFAULT_VAULT_ID,
)

@Entity(tableName = "history", indices = [Index("itemId")])
class HistoryRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val itemId: String,
    val rev: Long,
    val savedAt: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val wrappedItemKey: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray,
    val vaultId: String = app.solina.refuge.core.crypto.MultiVaultDesign.DEFAULT_VAULT_ID,
)

/**
 * فرادادهٔ متن‌باز موعد کار (فاز ۳). فقط شناسهٔ کار، زمان موعد و زمان آخرین یادآوری؛
 * هیچ عنوان، متن یا محتوای دیگری اینجا ذخیره نمی‌شود — محتوا فقط داخل blob رمز خزانه است.
 */
@Entity(tableName = "task_due")
class TaskDueRow(
    @PrimaryKey val id: String,
    val dueAt: Long,
    val notifiedAt: Long,
    val vaultId: String = app.solina.refuge.core.crypto.MultiVaultDesign.DEFAULT_VAULT_ID,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE vaultId = :vaultId")
    suspend fun all(vaultId: String): List<ItemRow>

    @Query("SELECT * FROM items WHERE id = :id AND vaultId = :vaultId")
    suspend fun get(id: String, vaultId: String): ItemRow?

    @Upsert
    suspend fun upsert(row: ItemRow)

    @Query("INSERT INTO history (itemId, rev, savedAt, wrappedItemKey, blob, vaultId) VALUES (:itemId, :rev, :savedAt, :key, :blob, :vaultId)")
    suspend fun addHistory(itemId: String, rev: Long, savedAt: Long, key: ByteArray, blob: ByteArray, vaultId: String)

    @Query(
        "DELETE FROM history WHERE itemId = :itemId AND vaultId = :vaultId AND rowId NOT IN " +
            "(SELECT rowId FROM history WHERE itemId = :itemId AND vaultId = :vaultId ORDER BY rev DESC, rowId DESC LIMIT :keep)",
    )
    suspend fun trimHistory(itemId: String, keep: Int, vaultId: String)

    @Query("DELETE FROM items WHERE id = :id AND vaultId = :vaultId")
    suspend fun deleteItem(id: String, vaultId: String)

    @Query("DELETE FROM history WHERE itemId = :itemId AND vaultId = :vaultId")
    suspend fun clearHistory(itemId: String, vaultId: String)

    @Query("SELECT * FROM history WHERE itemId = :itemId AND vaultId = :vaultId ORDER BY rev DESC")
    suspend fun history(itemId: String, vaultId: String): List<HistoryRow>

    @Query("DELETE FROM items")
    suspend fun wipeItems()

    @Query("DELETE FROM history")
    suspend fun wipeHistory()

    @Query("DELETE FROM items WHERE vaultId = :vaultId")
    suspend fun wipeVaultItems(vaultId: String)

    @Query("DELETE FROM history WHERE vaultId = :vaultId")
    suspend fun wipeVaultHistory(vaultId: String)

    @Query("DELETE FROM task_due WHERE vaultId = :vaultId")
    suspend fun wipeVaultTaskDues(vaultId: String)

    @Upsert
    suspend fun upsertTaskDue(row: TaskDueRow)

    @Query("DELETE FROM task_due WHERE id = :id AND vaultId = :vaultId")
    suspend fun deleteTaskDue(id: String, vaultId: String)

    @Query("SELECT * FROM task_due WHERE vaultId = :vaultId")
    suspend fun allTaskDues(vaultId: String): List<TaskDueRow>

    @Query("DELETE FROM task_due")
    suspend fun wipeTaskDues()
}

@Database(entities = [ItemRow::class, HistoryRow::class, TaskDueRow::class], version = 3, exportSchema = false)
abstract class RefugeDatabase : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        /** مهاجرت ۱ ← ۲ (فاز ۳): فقط جدول متن‌باز موعد کارها اضافه می‌شود؛ جدول items دست نمی‌خورد. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_due` (`id` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `notifiedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /**
         * مهاجرت ۲ ← ۳ (چندخزانه‌ای): ستون `vaultId` به هر سه جدول اضافه می‌شود؛
         * همهٔ ردیف‌های قدیمی به خزانهٔ پیش‌فرض (`default`) تعلق می‌گیرند — هیچ داده‌ای
         * جابه‌جا یا دوباره رمز نمی‌شود.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `vaultId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `history` ADD COLUMN `vaultId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `task_due` ADD COLUMN `vaultId` TEXT NOT NULL DEFAULT 'default'")
            }
        }

        fun open(context: Context): RefugeDatabase =
            Room.databaseBuilder(context.applicationContext, RefugeDatabase::class.java, "refuge.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // بازنویسی صفحات حذف‌شده با صفر برای کاهش باقی‌ماندهٔ متادیتا/بلاب در دیسک
                        // نکته: PRAGMA حتی موقع مقداردهی یک ردیف برمی‌گرداند، پس execSQL روی اندروید واقعی SQLiteException می‌اندازد؛ با query ردیف مصرف می‌شود.
                        db.query("PRAGMA secure_delete = ON").close()
                    }
                })
                .build()
    }
}

class RoomItemStore(private val db: RefugeDatabase) : ItemStore {
    private val dao = db.dao()

    override suspend fun all(vaultId: String) = dao.all(vaultId).map { it.toStored() }
    override suspend fun get(id: String, vaultId: String) = dao.get(id, vaultId)?.toStored()

    override suspend fun save(item: StoredItem, archivedPrevious: StoredHistory?, keepHistory: Int) {
        db.withTransaction {
            if (archivedPrevious != null) {
                dao.addHistory(
                    archivedPrevious.itemId, archivedPrevious.rev, archivedPrevious.savedAt,
                    archivedPrevious.wrappedItemKey, archivedPrevious.blob, archivedPrevious.vaultId,
                )
                dao.trimHistory(item.id, keepHistory, item.vaultId)
            }
            dao.upsert(ItemRow(item.id, item.rev, item.updatedAt, item.deletedAt, item.wrappedItemKey, item.blob, item.vaultId))
        }
    }

    override suspend fun delete(id: String, deletedAt: Long, newRev: Long, archivedPrevious: StoredHistory?, vaultId: String) {
        db.withTransaction {
            if (archivedPrevious != null) {
                dao.addHistory(
                    archivedPrevious.itemId, archivedPrevious.rev, archivedPrevious.savedAt,
                    archivedPrevious.wrappedItemKey, archivedPrevious.blob, archivedPrevious.vaultId,
                )
                dao.trimHistory(id, VaultRepository.MAX_HISTORY, vaultId)
            }
            dao.upsert(ItemRow(id, newRev, deletedAt, deletedAt, ByteArray(0), ByteArray(0), vaultId))
        }
    }

    override suspend fun purge(id: String, vaultId: String) {
        db.withTransaction {
            dao.clearHistory(id, vaultId)
            dao.deleteItem(id, vaultId)
        }
    }

    override suspend fun history(itemId: String, vaultId: String) =
        dao.history(itemId, vaultId).map { StoredHistory(it.itemId, it.rev, it.savedAt, it.wrappedItemKey, it.blob, it.vaultId) }

    override suspend fun wipeAll() {
        db.withTransaction { dao.wipeItems(); dao.wipeHistory(); dao.wipeTaskDues() }
        // پاکسازی کامل WAL و صفحات آزاد فایل دیتابیس
        runCatching {
            db.openHelper.writableDatabase.apply {
                query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                execSQL("VACUUM")
            }
        }
    }

    override suspend fun wipeVault(vaultId: String) {
        db.withTransaction { dao.wipeVaultItems(vaultId); dao.wipeVaultHistory(vaultId); dao.wipeVaultTaskDues(vaultId) }
    }

    override suspend fun saveTaskDue(id: String, dueAt: Long, notifiedAt: Long, vaultId: String) {
        dao.upsertTaskDue(TaskDueRow(id, dueAt, notifiedAt, vaultId))
    }

    override suspend fun deleteTaskDue(id: String, vaultId: String) {
        dao.deleteTaskDue(id, vaultId)
    }

    override suspend fun allTaskDues(vaultId: String): List<TaskDueMeta> =
        dao.allTaskDues(vaultId).map { TaskDueMeta(it.id, it.dueAt, it.notifiedAt) }

    private fun ItemRow.toStored() = StoredItem(id, rev, updatedAt, deletedAt, wrappedItemKey, blob, vaultId)
}
