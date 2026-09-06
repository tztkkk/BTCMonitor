package com.tzt.btcmonitor.settings

import androidx.datastore.preferences.core.*
import com.tzt.btcmonitor.model.AlertConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TargetAlertMigrationTest {
    private val old = stringPreferencesKey("alerts_json_v2")
    private val target = stringPreferencesKey("target_alerts_json_v1")
    private val cooldown = intPreferencesKey("alert_cooldown_minutes_v1")

    @Test fun mixedRecordsPreserveFieldsAndDeduplicateWithoutLosingLaterRecords() = runBlocking {
        val legacy = """[{"id":"a","name":"原名称","assetId":"okx:ETH-USDT","symbol":"ETH-USDT","enabled":false,"direction":"BELOW_OR_EQUAL","threshold":123.45},null,{"id":"bad","threshold":-1},{"id":"a","threshold":99},{"id":"b","threshold":200}]"""
        val before = preferencesOf(old to legacy)
        val after = TargetAlertMigration.migrate(before)
        val alerts = requireNotNull(AlertConfigJson.decode(requireNotNull(after[target])))
        assertEquals(2, alerts.size)
        assertEquals(AlertConfig("a", "原名称", "okx:ETH-USDT", "ETH-USDT", false, 123.45), alerts.first())
        assertEquals("b", alerts.last().id)
        assertFalse(requireNotNull(after[target]).contains("direction"))
        assertEquals(legacy, after[old])
        assertEquals(5, after[cooldown])
        assertFalse(TargetAlertMigration.shouldMigrate(after))
        assertEquals(after, TargetAlertMigration.migrate(after))
    }

    @Test fun newEmptySchemaNeverReimportsDeletedLegacyAlerts() = runBlocking {
        val after = TargetAlertMigration.migrate(preferencesOf(
            old to """[{"id":"a","threshold":100}]""", target to "[]", cooldown to 15
        ))
        assertEquals("[]", after[target])
        assertEquals(15, after[cooldown])
    }

    @Test fun missingAndInvalidCooldownDefaultButAllLegalValuesSurvive() = runBlocking {
        for (minutes in listOf(-1, 0, 2, 1, 5, 15, 30, 60)) {
            val after = TargetAlertMigration.migrate(preferencesOf(target to "[]", cooldown to minutes))
            assertEquals(if (minutes in listOf(1, 5, 15, 30, 60)) minutes else 5, after[cooldown])
        }
    }

    @Test fun singleAlertKeysMigrateAndCorruptRootDoesNotBecomeDefault() = runBlocking {
        val after = TargetAlertMigration.migrate(preferencesOf(
            booleanPreferencesKey("alert_enabled") to false,
            doublePreferencesKey("alert_threshold") to 777.0
        ))
        assertEquals(listOf(AlertConfig(enabled = false, threshold = 777.0)),
            AlertConfigJson.decode(requireNotNull(after[target])))
        assertTrue(runCatching { TargetAlertMigration.migrate(preferencesOf(old to "{broken")) }.isFailure)
    }
}
