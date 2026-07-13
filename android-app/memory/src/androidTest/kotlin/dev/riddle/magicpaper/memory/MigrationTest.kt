package dev.riddle.magicpaper.memory

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
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
    }
}
