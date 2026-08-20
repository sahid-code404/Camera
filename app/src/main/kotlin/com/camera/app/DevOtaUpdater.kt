package com.camera.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class DevUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

internal sealed interface DevUpdateCheck {
    data object UpToDate : DevUpdateCheck
    data class Available(val info: DevUpdateInfo) : DevUpdateCheck
    data class Failed(val message: String) : DevUpdateCheck
}

internal sealed interface DevInstallResult {
    data object InstallerOpened : DevInstallResult
    data object SourcePermissionRequired : DevInstallResult
    data class Failed(val message: String) : DevInstallResult
}

/**
 * Tiny updater for development builds.
 *
 * This is intentionally not a silent installer. Android still owns the final install confirmation.
 * The updater only removes the repetitive browser/download/uninstall/reinstall workflow.
 */
internal class DevOtaUpdater(private val activity: Activity) {

    fun check(callback: (DevUpdateCheck) -> Unit) {
        Thread {
            val result = runCatching {
                val manifestText = getText(BuildConfig.OTA_MANIFEST_URL)
                val json = JSONObject(manifestText)
                val info = DevUpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", "dev"),
                    apkUrl = json.getString("apkUrl"),
                    sha256 = json.getString("sha256").lowercase(),
                )
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    DevUpdateCheck.Available(info)
                } else {
                    DevUpdateCheck.UpToDate
                }
            }.getOrElse { error ->
                DevUpdateCheck.Failed(error.message ?: error.javaClass.simpleName)
            }
            activity.runOnUiThread { callback(result) }
        }.apply {
            name = "CameraDevOtaCheck"
            isDaemon = true
            start()
        }
    }

    fun downloadAndInstall(
        info: DevUpdateInfo,
        callback: (DevInstallResult) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
            activity.startActivity(intent)
            callback(DevInstallResult.SourcePermissionRequired)
            return
        }

        Thread {
            val result = runCatching {
                val otaDir = File(activity.cacheDir, "ota").apply { mkdirs() }
                otaDir.listFiles()?.forEach { old ->
                    if (old.isFile && old.name.endsWith(".apk")) old.delete()
                }
                val apk = File(otaDir, "Camera-${info.versionCode}.apk")
                download(info.apkUrl, apk)
                val actualSha = sha256(apk)
                check(actualSha.equals(info.sha256, ignoreCase = true)) {
                    "OTA checksum mismatch: expected ${info.sha256}, got $actualSha"
                }

                activity.runOnUiThread { openInstaller(apk) }
                DevInstallResult.InstallerOpened
            }.getOrElse { error ->
                DevInstallResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            activity.runOnUiThread { callback(result) }
        }.apply {
            name = "CameraDevOtaDownload"
            isDaemon = true
            start()
        }
    }

    private fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    private fun getText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
            .also { connection.disconnect() }
    }

    private fun download(url: String, destination: File) {
        val connection = openConnection(url)
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, bufferSize = 128 * 1024)
            }
        }
        connection.disconnect()
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 45_000
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "Camera-Dev-OTA/${BuildConfig.VERSION_NAME}")
        connection.connect()
        check(connection.responseCode in 200..299) {
            "HTTP ${connection.responseCode} from $url"
        }
        return connection
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
