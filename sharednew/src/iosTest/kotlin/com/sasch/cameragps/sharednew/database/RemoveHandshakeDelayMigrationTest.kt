package com.sasch.cameragps.sharednew.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoveHandshakeDelayMigrationTest {
    @Test
    fun removesSavedDelayAndPreservesCameraSettings() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            // Version 6 schema, including an existing nonzero delay.
            connection.execSQL(
                """
                CREATE TABLE camera_devices (
                    mac TEXT NOT NULL PRIMARY KEY,
                    deviceEnabled INTEGER NOT NULL DEFAULT 1,
                    alwaysOnEnabled INTEGER NOT NULL,
                    deviceName TEXT NOT NULL,
                    deviceNameIsCustom INTEGER NOT NULL DEFAULT 0,
                    remoteControlEnabled INTEGER NOT NULL DEFAULT 0,
                    handshakeDelayMs INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            connection.execSQL("INSERT INTO camera_devices VALUES ('A', 1, 1, 'My camera', 1, 1, 10000)")
            connection.execSQL("INSERT INTO camera_devices VALUES ('B', 0, 0, 'Other camera', 0, 0, 0)")

            LogDatabase_AutoMigration_6_7_Impl().migrate(connection)

            connection.prepare("SELECT * FROM camera_devices ORDER BY mac").use { rows ->
                assertEquals(
                    listOf(
                        "mac",
                        "deviceEnabled",
                        "alwaysOnEnabled",
                        "deviceName",
                        "deviceNameIsCustom",
                        "remoteControlEnabled"
                    ),
                    (0 until rows.getColumnCount()).map { rows.getColumnName(it) },
                )
                for ((id, enabled, name) in listOf(
                    Triple("A", 1L, "My camera"),
                    Triple("B", 0L, "Other camera")
                )) {
                    assertTrue(rows.step())
                    assertEquals(id, rows.getText(0))
                    assertEquals(enabled, rows.getLong(1))
                    assertEquals(enabled, rows.getLong(2))
                    assertEquals(name, rows.getText(3))
                    assertEquals(enabled, rows.getLong(4))
                    assertEquals(enabled, rows.getLong(5))
                }
                assertFalse(rows.step())
            }
        }
    }
}
