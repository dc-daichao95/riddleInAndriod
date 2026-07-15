package dev.riddle.magicpaper.memory

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            val forbiddenColumns = findForbiddenUserColumns(database)
            assertFalse("Persisted schema must not expose secrets or temporary image bytes: $forbiddenColumns", forbiddenColumns.isNotEmpty())
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 1, true).close()
    }

    @Test
    fun securityScannerDiscoversForbiddenColumnsInAnyUserTable() {
        helper.createDatabase(PROBE_DATABASE, 1).use { database ->
            database.execSQL("CREATE TABLE review_probe (id INTEGER PRIMARY KEY, api_token TEXT, payload BLOB)")

            val findings = findForbiddenUserColumns(database)

            assertTrue(findings.contains("review_probe.api_token"))
            assertTrue(findings.contains("review_probe.payload"))
        }
    }

    @Test
    fun populatedVersionOneMigratesToVersionTwoWithoutChangingExistingRowsAndStartsWithNoActiveRun() {
        helper.createDatabase(POPULATED_DATABASE, 1).use { database ->
            database.execSQL(
                "INSERT INTO memory_pages VALUES " +
                    "('page-1', 42, 'question', 'answer', 'COMPLETED', 'provider', 'model', 1)",
            )
            database.execSQL(
                "INSERT INTO memory_points VALUES " +
                    "('page-1', 0, 0, 'stroke', 'PEN', 0.1, 0.2, 0.01)",
            )
            database.execSQL("INSERT INTO app_preferences VALUES (0, 1, 8, 9)")
            database.execSQL("INSERT INTO draft_pages VALUES (0, 77)")
            database.execSQL(
                "INSERT INTO draft_points VALUES " +
                    "(0, 0, 0, 'draft-stroke', 'PEN', 0.3, 0.4, 0.01)",
            )
            database.execSQL(
                "INSERT INTO draft_points VALUES " +
                    "(0, 0, 1, 'draft-stroke', 'ERASER', 0.5, 0.6, 0.02)",
            )
            database.execSQL(
                "INSERT INTO draft_points VALUES " +
                    "(0, 1, 0, 'second-stroke', 'PEN', 0.7, 0.8, 0.03)",
            )
        }

        helper.runMigrationsAndValidate(
            POPULATED_DATABASE,
            2,
            true,
            RiddleDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT transcription, reply FROM memory_pages WHERE pageId = 'page-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("question", cursor.getString(0))
                assertEquals("answer", cursor.getString(1))
            }
            database.query("SELECT x, y FROM memory_points WHERE pageId = 'page-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0.1, cursor.getDouble(0), 0.0)
                assertEquals(0.2, cursor.getDouble(1), 0.0)
            }
            database.query("SELECT recentContextCount, memoryRevision FROM app_preferences WHERE id = 0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(8, cursor.getInt(0))
                assertEquals(9L, cursor.getLong(1))
            }
            database.query("SELECT revision FROM draft_pages WHERE id = 0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(77L, cursor.getLong(0))
            }
            database.query(
                "SELECT draftId, strokeOrder, pointOrder, strokeId, tool, x, y, radius " +
                    "FROM draft_points ORDER BY strokeOrder ASC, pointOrder ASC",
            ).use { cursor ->
                assertEquals(3, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("draft-stroke", cursor.getString(3))
                assertEquals("PEN", cursor.getString(4))
                assertEquals(0.3, cursor.getDouble(5), 0.0)
                assertEquals(0.4, cursor.getDouble(6), 0.0)
                assertEquals(0.01, cursor.getDouble(7), 0.0)

                assertTrue(cursor.moveToNext())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals("draft-stroke", cursor.getString(3))
                assertEquals("ERASER", cursor.getString(4))
                assertEquals(0.5, cursor.getDouble(5), 0.0)
                assertEquals(0.6, cursor.getDouble(6), 0.0)
                assertEquals(0.02, cursor.getDouble(7), 0.0)

                assertTrue(cursor.moveToNext())
                assertEquals(0, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("second-stroke", cursor.getString(3))
                assertEquals("PEN", cursor.getString(4))
                assertEquals(0.7, cursor.getDouble(5), 0.0)
                assertEquals(0.8, cursor.getDouble(6), 0.0)
                assertEquals(0.03, cursor.getDouble(7), 0.0)
                assertFalse(cursor.moveToNext())
            }
            database.query("SELECT COUNT(*) FROM active_reply_runs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT runId FROM active_reply_runs LIMIT 1").use { cursor ->
                assertNull(if (cursor.moveToFirst()) cursor.getString(0) else null)
            }
        }
    }

    private fun findForbiddenUserColumns(database: SupportSQLiteDatabase): List<String> {
        val userTables = buildList {
            database.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table') " +
                    "ORDER BY name",
            ).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        return buildList {
            userTables.forEach { table ->
                val escapedTable = table.replace("`", "``")
                database.query("PRAGMA table_info(`$escapedTable`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(nameIndex)
                        val type = cursor.getString(typeIndex)
                        if (isForbiddenPersistedField(column, type)) add("$table.$column")
                    }
                }
            }
        }
    }

    private fun isForbiddenPersistedField(column: String, type: String): Boolean {
        val normalized = column.lowercase().filter(Char::isLetterOrDigit)
        val forbiddenNameFragments = listOf(
            "key",
            "secret",
            "token",
            "credential",
            "authorization",
            "bearer",
            "tempimage",
            "temporaryimage",
            "imagebytes",
            "imageblob",
            "blob",
        )
        return type.equals("BLOB", ignoreCase = true) || forbiddenNameFragments.any(normalized::contains)
    }

    companion object {
        private const val TEST_DATABASE = "migration-test"
        private const val PROBE_DATABASE = "migration-security-probe"
        private const val POPULATED_DATABASE = "migration-populated-v1"
    }
}
