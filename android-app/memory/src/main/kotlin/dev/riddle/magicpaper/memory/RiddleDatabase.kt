package dev.riddle.magicpaper.memory

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryPageEntity::class,
        MemoryPointEntity::class,
        AppPreferenceEntity::class,
        DraftPageEntity::class,
        DraftPointEntity::class,
        ActiveReplyRunEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RiddleDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `active_reply_runs` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`draftId` INTEGER NOT NULL, " +
                        "`draftRevision` INTEGER NOT NULL, " +
                        "`partialReplySourceText` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `draft_pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_active_reply_runs_draftId` " +
                        "ON `active_reply_runs` (`draftId`)",
                )
            }
        }
    }
}
