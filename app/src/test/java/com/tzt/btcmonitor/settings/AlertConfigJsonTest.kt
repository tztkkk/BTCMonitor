package com.tzt.btcmonitor.settings

import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.SupportedAssets
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertConfigJsonTest {
    @Test
    fun roundTripPreservesAlertList() {
        val alerts = listOf(
            AlertConfig(id = "one", name = "高位", threshold = 120_000.0),
            AlertConfig(
                id = "two",
                name = "低位",
                enabled = false,
                direction = AlertDirection.BELOW_OR_EQUAL,
                threshold = 70_000.0
            )
        )

        assertEquals(alerts, AlertConfigJson.decode(AlertConfigJson.encode(alerts)))
    }

    @Test
    fun roundTripPreservesWatchAssets() {
        val assets = SupportedAssets.all.take(3)
        assertEquals(assets, WatchAssetJson.decode(WatchAssetJson.encode(assets)))
    }
}
