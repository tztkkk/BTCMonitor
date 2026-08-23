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

private val Context.dataStore by preferencesDataStore(name = "monitor_settings")

data class AppSettings(
    val alert: AlertConfig = AlertConfig(),
    val githubOwner: String = BuildConfig.GITHUB_OWNER,
    val githubRepo: String = BuildConfig.GITHUB_REPO
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("alert_enabled")
        val direction = stringPreferencesKey("alert_direction")
        val threshold = doublePreferencesKey("alert_threshold")
        val githubOwner = stringPreferencesKey("github_owner")
        val githubRepo = stringPreferencesKey("github_repo")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            alert = AlertConfig(
                enabled = preferences[Keys.enabled] ?: true,
                direction = preferences[Keys.direction]
                    ?.let { runCatching { AlertDirection.valueOf(it) }.getOrNull() }
                    ?: AlertDirection.ABOVE_OR_EQUAL,
                threshold = preferences[Keys.threshold] ?: 120_000.0
            ),
            githubOwner = preferences[Keys.githubOwner] ?: BuildConfig.GITHUB_OWNER,
            githubRepo = preferences[Keys.githubRepo] ?: BuildConfig.GITHUB_REPO
        )
    }

    suspend fun saveAlert(enabled: Boolean, direction: AlertDirection, threshold: Double) {
        require(threshold.isFinite() && threshold > 0.0) { "提醒价格必须大于 0" }
        context.dataStore.edit {
            it[Keys.enabled] = enabled
            it[Keys.direction] = direction.name
            it[Keys.threshold] = threshold
        }
    }

    suspend fun saveGitHubRepository(owner: String, repo: String) {
        context.dataStore.edit {
            it[Keys.githubOwner] = owner.trim()
            it[Keys.githubRepo] = repo.trim()
        }
    }
}
