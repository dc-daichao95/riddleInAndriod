package dev.riddle.magicpaper.memory

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryPageEntity::class,
        MemoryPointEntity::class,
        AppPreferenceEntity::class,
        DraftPageEntity::class,
        DraftPointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RiddleDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
