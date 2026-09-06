package com.tzt.btcmonitor.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.domain.alert.AlertCooldown
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.tzt.btcmonitor.model.MarketSource
import com.tzt.btcmonitor.model.SupportedAssets
import com.tzt.btcmonitor.model.WatchAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "monitor_settings", produceMigrations = { listOf(TargetAlertMigration) })

data class AppSettings(
    val assets: List<WatchAsset> = emptyList(),
    val alerts: List<AlertConfig> = emptyList(),
    val monitoringPaused: Boolean = false,
    val alertCooldown: AlertCooldown = AlertCooldown.DEFAULT,
    val githubOwner: String = BuildConfig.GITHUB_OWNER,
    val githubRepo: String = BuildConfig.GITHUB_REPO
)

class SettingsRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)
    private object Keys {
        val enabled = booleanPreferencesKey("alert_enabled")
        val threshold = doublePreferencesKey("alert_threshold")
        val alertsJson = stringPreferencesKey("target_alerts_json_v1")
        val cooldown = intPreferencesKey("alert_cooldown_minutes_v1")
        val assetsJson = stringPreferencesKey("watch_assets_json_v3")
        val monitoringPaused = booleanPreferencesKey("monitoring_paused_v3")
        val githubOwner = stringPreferencesKey("github_owner")
        val githubRepo = stringPreferencesKey("github_repo")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            assets = preferences[Keys.assetsJson]
                ?.let(WatchAssetJson::decode)
                ?: listOf(SupportedAssets.default),
            alerts = preferences[Keys.alertsJson]
                ?.let { requireNotNull(AlertConfigJson.decode(it)) { "到价提醒配置损坏" } }
                ?: listOf(legacyAlert(preferences)),
            monitoringPaused = preferences[Keys.monitoringPaused] ?: false,
            alertCooldown = AlertCooldown.fromMinutes(preferences[Keys.cooldown] ?: 5) ?: AlertCooldown.DEFAULT,
            githubOwner = preferences[Keys.githubOwner] ?: BuildConfig.GITHUB_OWNER,
            githubRepo = preferences[Keys.githubRepo] ?: BuildConfig.GITHUB_REPO
        )
    }

    suspend fun addAlert(asset: WatchAsset, name: String, enabled: Boolean, threshold: Double) {
        validate(name, threshold)
        updateAlerts { current ->
            current + AlertConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                assetId = asset.id,
                symbol = asset.symbol,
                enabled = enabled,
                threshold = threshold
            )
        }
    }

    suspend fun updateAlert(id: String, name: String, enabled: Boolean, threshold: Double) {
        validate(name, threshold)
        updateAlerts { current ->
            require(current.any { it.id == id }) { "提醒不存在" }
            current.map {
                if (it.id == id) it.copy(
                    name = name.trim(),
                    enabled = enabled,
                    threshold = threshold
                ) else it
            }
        }
    }

    suspend fun setAlertEnabled(id: String, enabled: Boolean) {
        updateAlerts { current ->
            require(current.any { it.id == id }) { "提醒不存在" }
            current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    suspend fun deleteAlert(id: String) {
        updateAlerts { current -> current.filterNot { it.id == id } }
    }

    suspend fun addAsset(asset: WatchAsset) {
        dataStore.edit { preferences ->
            val current = currentAssets(preferences)
            preferences[Keys.assetsJson] = WatchAssetJson.encode((current + asset).distinctBy(WatchAsset::id))
        }
    }

    suspend fun removeAsset(assetId: String) {
        dataStore.edit { preferences ->
            preferences[Keys.assetsJson] = WatchAssetJson.encode(
                currentAssets(preferences).filterNot { it.id == assetId }
            )
            preferences[Keys.alertsJson] = AlertConfigJson.encode(
                currentAlerts(preferences).filterNot { it.assetId == assetId }
            )
        }
    }

    suspend fun setAlertCooldown(cooldown: AlertCooldown) {
        dataStore.edit { it[Keys.cooldown] = cooldown.minutes }
    }

    suspend fun setMonitoringPaused(paused: Boolean) {
        dataStore.edit { it[Keys.monitoringPaused] = paused }
    }

    suspend fun saveGitHubRepository(owner: String, repo: String) {
        dataStore.edit {
            it[Keys.githubOwner] = owner.trim()
            it[Keys.githubRepo] = repo.trim()
        }
    }

    private suspend fun updateAlerts(transform: (List<AlertConfig>) -> List<AlertConfig>) {
        dataStore.edit { preferences ->
            val current = currentAlerts(preferences)
            preferences[Keys.alertsJson] = AlertConfigJson.encode(transform(current))
        }
    }

    private fun currentAlerts(preferences: androidx.datastore.preferences.core.Preferences): List<AlertConfig> =
        preferences[Keys.alertsJson]?.let { requireNotNull(AlertConfigJson.decode(it)) { "到价提醒配置损坏" } } ?: listOf(legacyAlert(preferences))

    private fun currentAssets(preferences: androidx.datastore.preferences.core.Preferences): List<WatchAsset> =
        preferences[Keys.assetsJson]?.let(WatchAssetJson::decode) ?: listOf(SupportedAssets.default)

    private fun legacyAlert(preferences: androidx.datastore.preferences.core.Preferences): AlertConfig = AlertConfig(
        enabled = preferences[Keys.enabled] ?: true,
        threshold = preferences[Keys.threshold] ?: 120_000.0
    )

    private fun validate(name: String, threshold: Double) {
        require(name.trim().isNotEmpty()) { "提醒名称不能为空" }
        require(name.trim().length <= 40) { "提醒名称不能超过 40 个字符" }
        require(threshold.isFinite() && threshold > 0.0) { "提醒价格必须大于 0" }
    }
}

internal object AlertConfigJson {
    fun encode(alerts: List<AlertConfig>): String = JSONArray().apply {
        alerts.forEach { alert ->
            put(JSONObject().apply {
                put("id", alert.id)
                put("name", alert.name)
                put("assetId", alert.assetId)
                put("symbol", alert.symbol)
                put("enabled", alert.enabled)
                put("targetPrice", alert.threshold)
            })
        }
    }.toString()

    fun decode(value: String): List<AlertConfig>? = decodeRecords(value, legacy = false)

    fun decodeLegacy(value: String): List<AlertConfig>? = decodeRecords(value, legacy = true)

    private fun decodeRecords(value: String, legacy: Boolean): List<AlertConfig>? = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val record = runCatching {
                    val item = array.getJSONObject(index)
                    val threshold = item.getDouble(if (legacy) "threshold" else "targetPrice")
                    require(threshold.isFinite() && threshold > 0.0)
                    val id = item.getString("id").also { require(it.isNotBlank()) }
                    val symbol = item.optString("symbol").ifBlank { "BTC-USDT" }
                    AlertConfig(
                        id = id,
                        name = item.optString("name").ifBlank { "BTC 价格提醒" },
                        assetId = item.optString("assetId").ifBlank { "okx:$symbol" },
                        symbol = symbol,
                        enabled = item.optBoolean("enabled", true),
                        threshold = threshold
                    )
                }.getOrNull()
                if (record != null) add(record)
            }
        }.distinctBy(AlertConfig::id)
    }.getOrNull()
}

internal object WatchAssetJson {
    fun encode(assets: List<WatchAsset>): String = JSONArray().apply {
        assets.forEach { asset ->
            put(JSONObject().apply {
                put("id", asset.id)
                put("symbol", asset.symbol)
                put("displayName", asset.displayName)
                put("source", asset.source.name)
            })
        }
    }.toString()

    fun decode(value: String): List<WatchAsset>? = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    WatchAsset(
                        id = item.getString("id"),
                        symbol = item.getString("symbol"),
                        displayName = item.optString("displayName").ifBlank { item.getString("symbol") },
                        source = runCatching { MarketSource.valueOf(item.optString("source")) }
                            .getOrDefault(MarketSource.OKX)
                    )
                )
            }
        }.distinctBy(WatchAsset::id)
    }.getOrNull()
}

/** Atomic, idempotent migration before any settings consumer or editor sees the data. */
internal object TargetAlertMigration : DataMigration<Preferences> {
    private val target = stringPreferencesKey("target_alerts_json_v1")
    private val oldList = stringPreferencesKey("alerts_json_v2")
    private val cooldown = intPreferencesKey("alert_cooldown_minutes_v1")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[target] == null || AlertCooldown.fromMinutes(currentData[cooldown] ?: 0) == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[target] == null) {
                val alerts = this[oldList]?.let {
                    requireNotNull(AlertConfigJson.decodeLegacy(it)) { "旧提醒配置损坏，迁移已停止" }
                } ?: listOf(AlertConfig(
                    enabled = this[booleanPreferencesKey("alert_enabled")] ?: true,
                    threshold = this[doublePreferencesKey("alert_threshold")]
                        ?.takeIf { it.isFinite() && it > 0.0 } ?: 120_000.0
                ))
                this[target] = AlertConfigJson.encode(alerts)
            }
            this[cooldown] = (AlertCooldown.fromMinutes(this[cooldown] ?: 0) ?: AlertCooldown.DEFAULT).minutes
        }

    // Retain legacy keys for recovery; new readers and writers use only the new schema.
    override suspend fun cleanUp() = Unit
}
