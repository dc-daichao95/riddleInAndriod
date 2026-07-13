package dev.riddle.magicpaper.memory

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RiddleDatabase::class.java,
    )

    @Test
    fun versionOneExportCreatesAndValidatesTheBaselineSchema() {
        helper.createDatabase(TEST_DATABASE, 1).use { database ->
            val forbiddenColumns = buildList {
                listOf("memory_pages", "draft_pages", "draft_points").forEach { table ->
                    database.query("PRAGMA table_info(`$table`)").use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex).lowercase()
                            if (name.contains("key") || name.contains("secret") || name.contains("image") || name.contains("byte")) {
                                add("$table.$name")
                            }
                        }
                    }
                }
            }
            assertFalse("Persisted schema must not expose secrets or temporary image bytes: $forbiddenColumns", forbiddenColumns.isNotEmpty())
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 1, true).close()
    }

    companion object { private const val TEST_DATABASE = "migration-test" }
}
