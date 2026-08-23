package com.tzt.btcmonitor.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.logging.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdatePhase {
    IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING,
    PERMISSION_REQUIRED, READY_TO_INSTALL, INSTALLING, ERROR
}

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val webUrl: String
)

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestVersion: String? = null,
    val releaseNotes: String = "",
    val progressPercent: Int = 0,
    val message: String = ""
)

class UpdateManager(
    private val context: Context,
    private val logs: LogManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var latestRelease: ReleaseInfo? = null
    private var verifiedApk: File? = null

    suspend fun checkForUpdates(owner: String, repo: String, silent: Boolean = false) {
        if (!validRepoPart(owner) || !validRepoPart(repo)) {
            if (!silent) fail("请先在设置中填写有效的 GitHub owner 和 release repository")
            return
        }
        if (!silent) mutableState.value = UpdateUiState(phase = UpdatePhase.CHECKING, message = "正在检查更新")
        runCatching {
            withContext(Dispatchers.IO) { fetchLatestRelease(owner, repo) }
        }.onSuccess { release ->
            latestRelease = release
            val available = compareSemVer(release.versionName, BuildConfig.VERSION_NAME) > 0
            mutableState.value = UpdateUiState(
                phase = if (available) UpdatePhase.AVAILABLE else UpdatePhase.UP_TO_DATE,
                latestVersion = release.versionName,
                releaseNotes = release.releaseNotes,
                message = if (available) "发现新版本 ${release.tagName}" else "当前已是最新版本"
            )
            logs.log("UpdateCheck", "latest=${release.tagName} available=$available")
        }.onFailure {
            logs.log("Exception", "Update check: ${it.message}", LogLevel.ERROR)
            if (!silent) fail("检查更新失败：${it.message}")
        }
    }

    suspend fun downloadAndPrepareInstall() {
        val release = latestRelease ?: return fail("请先检查更新")
        runCatching {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updateDir.listFiles()?.forEach { if (it.isFile) it.delete() }
            val apk = File(updateDir, release.apkName)
            mutableState.value = mutableState.value.copy(
                phase = UpdatePhase.DOWNLOADING,
                progressPercent = 0,
                message = "正在下载 ${release.apkName}"
            )
            withContext(Dispatchers.IO) { downloadFile(release.apkUrl, apk) }
            mutableState.value = mutableState.value.copy(phase = UpdatePhase.VERIFYING, message = "正在校验 APK")
            val checksums = withContext(Dispatchers.IO) { fetchText(release.checksumUrl) }
            val expected = checksumFor(checksums, release.apkName)
                ?: error("SHA256SUMS.txt 中没有 ${release.apkName}")
            val actual = withContext(Dispatchers.IO) { sha256(apk) }
            check(actual.equals(expected, ignoreCase = true)) { "SHA-256 校验失败" }
            verifyApkIdentity(apk)
            verifiedApk = apk
            logs.log("UpdateVerified", "${release.apkName} sha256=$actual")

            if (!context.packageManager.canRequestPackageInstalls()) {
                mutableState.value = mutableState.value.copy(
                    phase = UpdatePhase.PERMISSION_REQUIRED,
                    progressPercent = 100,
                    message = "请允许此 App 安装未知应用，然后返回继续安装"
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = UpdatePhase.READY_TO_INSTALL,
                    progressPercent = 100,
                    message = "校验通过，可以安装"
                )
                launchInstaller()
            }
        }.onFailure {
            logs.log("Exception", "Update download: ${it.message}", LogLevel.ERROR)
            fail("下载或校验失败：${it.message}")
        }
    }

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri()
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun resumeInstallAfterPermission() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            fail("尚未允许安装未知应用")
            return
        }
        launchInstaller()
    }

    private fun launchInstaller() {
        val apk = verifiedApk?.takeIf { it.exists() } ?: return fail("已校验的 APK 不存在，请重新下载")
        runCatching {
            verifyApkIdentity(apk)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            mutableState.value = mutableState.value.copy(phase = UpdatePhase.INSTALLING, message = "已打开系统安装界面")
            context.startActivity(intent)
        }.onFailure { fail("无法打开系统安装界面：${it.message}") }
    }

    private fun fetchLatestRelease(owner: String, repo: String): ReleaseInfo {
        val json = JSONObject(fetchText("https://api.github.com/repos/$owner/$repo/releases/latest"))
        check(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "最新 Release 不是正式版本" }
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apkName: String? = null
        var apkUrl: String? = null
        var checksumUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            when {
                name.endsWith(".apk", ignoreCase = true) -> {
                    apkName = name
                    apkUrl = asset.getString("browser_download_url")
                }
                name.equals("SHA256SUMS.txt", ignoreCase = true) -> {
                    checksumUrl = asset.getString("browser_download_url")
                }
            }
        }
        return ReleaseInfo(
            tagName = tag,
            versionName = tag.removePrefix("v"),
            releaseNotes = json.optString("body"),
            apkName = requireNotNull(apkName) { "Release 中没有 APK" },
            apkUrl = requireNotNull(apkUrl),
            checksumUrl = requireNotNull(checksumUrl) { "Release 中没有 SHA256SUMS.txt" },
            webUrl = json.optString("html_url")
        )
    }

    private fun fetchText(url: String): String {
        val request = githubRequest(url)
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
            return response.body.string()
        }
    }

    private fun downloadFile(url: String, destination: File) {
        client.newCall(githubRequest(url)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            mutableState.value = mutableState.value.copy(progressPercent = percent)
                        }
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun githubRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "BTCMonitor-Android/${BuildConfig.VERSION_NAME}")
        .build()

    private fun verifyApkIdentity(apk: File) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)) {
            "无法读取 APK 包信息"
        }
        check(archive.packageName == context.packageName) {
            "applicationId 不一致：${archive.packageName} != ${context.packageName}"
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(archive.longVersionCode > installed.longVersionCode) {
            "versionCode 必须高于当前版本 (${installed.longVersionCode})"
        }
        val archiveCerts = archive.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val installedCerts = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        check(archiveCerts.isNotEmpty() && archiveCerts == installedCerts) { "APK 签名证书与当前 App 不一致" }
    }

    private fun checksumFor(text: String, fileName: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            val listedName = line.substringAfter("  ", line.substringAfter(" *", ""))
            listedName == fileName
        }
        ?.substringBefore(' ')
        ?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun compareSemVer(left: String, right: String): Int {
        fun parts(value: String) = value.removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val compared = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        return 0
    }

    private fun validRepoPart(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.-]+"))

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(phase = UpdatePhase.ERROR, message = message)
    }
}
