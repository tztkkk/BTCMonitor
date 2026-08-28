package com.tzt.btcmonitor.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.AlertDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "monitor_settings")

data class AppSettings(
    val alerts: List<AlertConfig> = listOf(AlertConfig()),
    val githubOwner: String = BuildConfig.GITHUB_OWNER,
    val githubRepo: String = BuildConfig.GITHUB_REPO
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("alert_enabled")
        val direction = stringPreferencesKey("alert_direction")
        val threshold = doublePreferencesKey("alert_threshold")
        val alertsJson = stringPreferencesKey("alerts_json_v2")
        val githubOwner = stringPreferencesKey("github_owner")
        val githubRepo = stringPreferencesKey("github_repo")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            alerts = preferences[Keys.alertsJson]
                ?.let(AlertConfigJson::decode)
                ?: listOf(legacyAlert(preferences)),
            githubOwner = preferences[Keys.githubOwner] ?: BuildConfig.GITHUB_OWNER,
            githubRepo = preferences[Keys.githubRepo] ?: BuildConfig.GITHUB_REPO
        )
    }

    suspend fun addAlert(name: String, enabled: Boolean, direction: AlertDirection, threshold: Double) {
        validate(name, threshold)
        updateAlerts { current ->
            current + AlertConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                enabled = enabled,
                direction = direction,
                threshold = threshold
            )
        }
    }

    suspend fun updateAlert(id: String, name: String, enabled: Boolean, direction: AlertDirection, threshold: Double) {
        validate(name, threshold)
        updateAlerts { current ->
            require(current.any { it.id == id }) { "提醒不存在" }
            current.map {
                if (it.id == id) it.copy(
                    name = name.trim(),
                    enabled = enabled,
                    direction = direction,
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

    suspend fun saveGitHubRepository(owner: String, repo: String) {
        context.dataStore.edit {
            it[Keys.githubOwner] = owner.trim()
            it[Keys.githubRepo] = repo.trim()
        }
    }

    private suspend fun updateAlerts(transform: (List<AlertConfig>) -> List<AlertConfig>) {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.alertsJson]
                ?.let(AlertConfigJson::decode)
                ?: listOf(legacyAlert(preferences))
            preferences[Keys.alertsJson] = AlertConfigJson.encode(transform(current))
        }
    }

    private fun legacyAlert(preferences: androidx.datastore.preferences.core.Preferences): AlertConfig = AlertConfig(
        enabled = preferences[Keys.enabled] ?: true,
        direction = preferences[Keys.direction]
            ?.let { runCatching { AlertDirection.valueOf(it) }.getOrNull() }
            ?: AlertDirection.ABOVE_OR_EQUAL,
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
                put("symbol", alert.symbol)
                put("enabled", alert.enabled)
                put("direction", alert.direction.name)
                put("threshold", alert.threshold)
            })
        }
    }.toString()

    fun decode(value: String): List<AlertConfig>? = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val threshold = item.getDouble("threshold")
                if (!threshold.isFinite() || threshold <= 0.0) continue
                add(
                    AlertConfig(
                        id = item.getString("id"),
                        name = item.optString("name").ifBlank { "BTC 价格提醒" },
                        symbol = item.optString("symbol").ifBlank { "BTC-USDT" },
                        enabled = item.optBoolean("enabled", true),
                        direction = runCatching { AlertDirection.valueOf(item.getString("direction")) }
                            .getOrDefault(AlertDirection.ABOVE_OR_EQUAL),
                        threshold = threshold
                    )
                )
            }
        }.distinctBy(AlertConfig::id)
    }.getOrNull()
}
