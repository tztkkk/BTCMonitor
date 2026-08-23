package com.tzt.btcmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tzt.btcmonitor.ui.MonitorApp
import com.tzt.btcmonitor.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) pendingNotificationAction?.invoke()
                pendingNotificationAction = null
            }
            val unknownSourcesLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { viewModel.resumeUpdateInstall() }

            MonitorApp(
                viewModel = viewModel,
                runWithNotificationPermission = { action ->
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        action()
                    } else {
                        pendingNotificationAction = action
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                openUnknownSourcesSettings = {
                    unknownSourcesLauncher.launch(viewModel.unknownSourcesIntent())
                },
                launchExternalIntent = { intent ->
                    runCatching { startActivity(intent) }
                        .onFailure { AppContainer.logs.log("Exception", "Open external intent: ${it.message}") }
                }
            )
        }
    }
}
