package com.camera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.camera.feature.camera.CameraBootstrapScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraApp()
        }
    }
}

@Composable
private fun CameraApp() {
    val activity = LocalActivity.current ?: return
    val updater = remember(activity) { DevOtaUpdater(activity) }
    var update by remember { mutableStateOf<DevUpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var installHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        updater.check { result ->
            if (result is DevUpdateCheck.Available) update = result.info
        }
    }

    CameraBootstrapScreen()

    val available = update
    if (available != null) {
        AlertDialog(
            onDismissRequest = {
                if (!downloading) {
                    update = null
                    installHint = null
                }
            },
            title = { Text("Camera update available") },
            text = {
                Text(
                    installHint ?: if (downloading) {
                        "Downloading and verifying ${available.versionName}…"
                    } else {
                        "Install ${available.versionName} over this build. Your Camera settings stay in place."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        installHint = null
                        updater.downloadAndInstall(available) { result ->
                            downloading = false
                            when (result) {
                                DevInstallResult.InstallerOpened -> {
                                    installHint = "Android installer opened. Confirm Update to finish."
                                }
                                DevInstallResult.SourcePermissionRequired -> {
                                    installHint = "Allow installs from Camera, return here, then tap Update again. This is required only once."
                                }
                                is DevInstallResult.Failed -> {
                                    installHint = "Update failed: ${result.message}"
                                }
                            }
                        }
                    },
                ) {
                    Text(if (downloading) "Working…" else "Update")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        update = null
                        installHint = null
                    },
                ) {
                    Text("Later")
                }
            },
        )
    }
}
