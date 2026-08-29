package com.tzt.btcmonitor.market

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.MarketTick
import com.tzt.btcmonitor.model.WebSocketStatus
import com.tzt.btcmonitor.notification.NotificationHelper
import com.tzt.btcmonitor.strategy.StrategyEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task003Android16Test {
    @Test
    fun liveOkxConnectionDispatchesBtcAndEthIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstTicks = ConcurrentHashMap<String, MarketTick>()
        val expectedSymbols = setOf("BTC-USDT", "ETH-USDT")
        val latch = CountDownLatch(expectedSymbols.size)
        val latestStatus = AtomicReference(WebSocketStatus.DISCONNECTED)
        val manager = MarketDataManager(
            scope = scope,
            logs = LogManager(context),
            onStatus = latestStatus::set,
            onTick = { tick ->
                if (tick.symbol in expectedSymbols && firstTicks.putIfAbsent(tick.symbol, tick) == null) {
                    latch.countDown()
                }
            }
        )

        try {
            manager.start(initialNetworkAvailable = true)
            manager.updateSymbols(expectedSymbols)

            assertTrue(
                "Timed out waiting for both symbols; status=${latestStatus.get()} received=${firstTicks.keys}",
                latch.await(90, TimeUnit.SECONDS)
            )
            assertEquals(expectedSymbols, firstTicks.keys)
            assertTrue(firstTicks.values.all { it.price > 0.0 })
        } finally {
            manager.stop()
            scope.cancel()
        }
    }

    @Test
    fun differentSymbolAlertsCreateSeparateAndroidNotifications() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "POST_NOTIFICATIONS must be granted once by the user before running notification instrumentation",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val previousAlertIds = notificationManager.activeNotifications
            .filter { it.notification.channelId == NotificationHelper.ALERT_CHANNEL_ID }
            .mapTo(mutableSetOf()) { it.id }
        val helper = NotificationHelper(context, LogManager(context)).also { it.createChannels() }
        val engine = StrategyEngine(
            listOf(
                AlertConfig(
                    id = "task003-btc",
                    name = "TASK-003 BTC",
                    symbol = "BTC-USDT",
                    assetId = "okx:BTC-USDT",
                    threshold = 100.0
                ),
                AlertConfig(
                    id = "task003-eth",
                    name = "TASK-003 ETH",
                    symbol = "ETH-USDT",
                    assetId = "okx:ETH-USDT",
                    threshold = 10.0
                )
            )
        )

        try {
            assertTrue(engine.evaluate(tick("BTC-USDT", 99.0)).none { it.triggered })
            assertTrue(engine.evaluate(tick("ETH-USDT", 9.0)).none { it.triggered })

            listOf(tick("BTC-USDT", 101.0), tick("ETH-USDT", 11.0)).forEach { tick ->
                engine.evaluate(tick)
                    .filter { it.triggered && it.message != null }
                    .forEach { helper.sendTradingAlert(requireNotNull(it.message), tick.price) }
            }

            val newAlerts = waitForNewAlertNotifications(notificationManager, previousAlertIds)
            assertEquals(2, newAlerts.size)
            val messages = newAlerts.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) }
                .map(CharSequence::toString)
            assertTrue(messages.any { "BTC-USDT" in it })
            assertTrue(messages.any { "ETH-USDT" in it })
        } finally {
            notificationManager.activeNotifications
                .filter {
                    it.notification.channelId == NotificationHelper.ALERT_CHANNEL_ID &&
                        it.id !in previousAlertIds
                }
                .forEach { notificationManager.cancel(it.id) }
        }
    }

    private fun waitForNewAlertNotifications(
        manager: NotificationManager,
        previousIds: Set<Int>
    ) = run {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var notifications = manager.activeNotifications.filter {
            it.notification.channelId == NotificationHelper.ALERT_CHANNEL_ID && it.id !in previousIds
        }
        while (notifications.size < 2 && System.nanoTime() < deadline) {
            Thread.sleep(100)
            notifications = manager.activeNotifications.filter {
                it.notification.channelId == NotificationHelper.ALERT_CHANNEL_ID && it.id !in previousIds
            }
        }
        notifications
    }

    private fun tick(symbol: String, price: Double) = MarketTick(
        symbol = symbol,
        price = price,
        exchangeTimeMillis = 1L
    )
}
