package com.tzt.btcmonitor.settings

import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tzt.btcmonitor.domain.alert.AlertCooldown
import com.tzt.btcmonitor.model.SupportedAssets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Task011MigrationAndroid16Test {
    @Test fun legacyStoreMigratesBeforeReadAndCrudSurvivesReopen() = runBlocking {
        assertEquals(36, Build.VERSION.SDK_INT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "task011-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        try {
            val oldStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }
            )
            oldStore.edit {
                it[stringPreferencesKey("alerts_json_v2")] = """[{"id":"upgrade-a","name":"旧提醒","assetId":"okx:BTC-USDT","symbol":"BTC-USDT","enabled":false,"direction":"BELOW_OR_EQUAL","threshold":99.5}]"""
            }
            job.cancelAndJoin()
            job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                migrations = listOf(TargetAlertMigration),
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }
            )
            val repository = SettingsRepository(store)
            val migrated = repository.settings.first()
            assertEquals("upgrade-a", migrated.alerts.single().id)
            assertEquals("旧提醒", migrated.alerts.single().name)
            assertEquals(99.5, migrated.alerts.single().threshold, 0.0)
            assertFalse(migrated.alerts.single().enabled)
            assertEquals(AlertCooldown.DEFAULT, migrated.alertCooldown)
            repository.updateAlert("upgrade-a", "重命名", true, 101.5)
            repository.setAlertEnabled("upgrade-a", false)
            repository.addAlert(SupportedAssets.default, "新增提醒", false, 102.0)
            repository.setAlertCooldown(AlertCooldown.FIFTEEN_MINUTES)
            val saved = repository.settings.first()
            assertEquals(2, saved.alerts.size)
            assertEquals("重命名", saved.alerts.first().name)
            assertEquals(101.5, saved.alerts.first().threshold, 0.0)
            assertFalse(saved.alerts.first().enabled)
            repository.deleteAlert("upgrade-a")
            val expected = repository.settings.first()
            job.cancelAndJoin()
            job = SupervisorJob()
            val reopened = SettingsRepository(PreferenceDataStoreFactory.create(
                migrations = listOf(TargetAlertMigration),
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }
            ))
            assertEquals(expected, reopened.settings.first())
            assertEquals(AlertCooldown.FIFTEEN_MINUTES, reopened.settings.first().alertCooldown)
            assertEquals(1, reopened.settings.first().alerts.size)
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
