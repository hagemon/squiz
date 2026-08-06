package com.hagemon.squiz.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val pageUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSize: Long,
    val sha256: String?,
)

data class DownloadedUpdate(
    val info: AppUpdateInfo,
    val file: File,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoPublishedRelease : UpdateCheckResult
    data object Skipped : UpdateCheckResult
    data class Failure(val message: String) : UpdateCheckResult
}

sealed interface UpdateDownloadStatus {
    data object None : UpdateDownloadStatus
    data object Downloading : UpdateDownloadStatus
    data class Ready(val update: DownloadedUpdate) : UpdateDownloadStatus
    data class Failure(val message: String) : UpdateDownloadStatus
}

class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    val currentVersionName: String
        get() = installedPackageInfo().versionName.orEmpty()

    suspend fun checkForUpdate(force: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - lastCheck < AUTOMATIC_CHECK_INTERVAL_MS) {
            return@withContext UpdateCheckResult.Skipped
        }
        preferences.edit().putLong(KEY_LAST_CHECK, now).apply()

        runCatching { fetchLatestRelease() }
            .fold(
                onSuccess = { latest ->
                    when {
                        latest == null -> UpdateCheckResult.NoPublishedRelease
                        compareReleaseVersions(latest.versionName, currentVersionName) > 0 -> {
                            UpdateCheckResult.Available(latest)
                        }
                        else -> UpdateCheckResult.UpToDate
                    }
                },
                onFailure = { error ->
                    UpdateCheckResult.Failure(error.message ?: "无法连接 GitHub")
                },
            )
    }

    fun enqueueDownload(info: AppUpdateInfo): Long {
        val updateDirectory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            UPDATE_DIRECTORY,
        ).apply { mkdirs() }
        val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetFile = File(updateDirectory, safeName)
        if (targetFile.exists()) targetFile.delete()

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Squiz ${info.versionName}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(targetFile))

        val downloadId = downloadManager.enqueue(request)
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_FILE_PATH, targetFile.absolutePath)
            .putString(KEY_TAG, info.tagName)
            .putString(KEY_VERSION, info.versionName)
            .putString(KEY_RELEASE_NAME, info.releaseName)
            .putString(KEY_NOTES, info.notes)
            .putString(KEY_PAGE_URL, info.pageUrl)
            .putString(KEY_ASSET_NAME, info.assetName)
            .putString(KEY_DOWNLOAD_URL, info.downloadUrl)
            .putLong(KEY_ASSET_SIZE, info.assetSize)
            .putString(KEY_SHA256, info.sha256)
            .apply()
        return downloadId
    }

    fun isPendingDownload(downloadId: Long): Boolean =
        downloadId >= 0L && downloadId == preferences.getLong(KEY_DOWNLOAD_ID, -1L)

    suspend fun inspectPendingDownload(): UpdateDownloadStatus = withContext(Dispatchers.IO) {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0L) return@withContext UpdateDownloadStatus.None

        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) {
                clearPendingDownload()
                return@withContext UpdateDownloadStatus.Failure("找不到已下载的更新")
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING,
                -> return@withContext UpdateDownloadStatus.Downloading

                DownloadManager.STATUS_FAILED -> {
                    clearPendingDownload()
                    return@withContext UpdateDownloadStatus.Failure("更新下载失败，请稍后重试")
                }
            }
        }

        val filePath = preferences.getString(KEY_FILE_PATH, null)
        val file = filePath?.let(::File)
        if (file == null || !file.isFile) {
            clearPendingDownload()
            return@withContext UpdateDownloadStatus.Failure("下载的安装包不存在")
        }

        val info = pendingUpdateInfo()
        if (info == null) {
            clearPendingDownload()
            return@withContext UpdateDownloadStatus.Failure("更新信息已丢失，请重新下载")
        }

        val expectedDigest = info.sha256
        if (!expectedDigest.isNullOrBlank()) {
            val actualDigest = sha256(file)
            if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                clearPendingDownload(deleteFile = true)
                return@withContext UpdateDownloadStatus.Failure("安装包校验失败，请重新下载")
            }
        }

        val validationError = validateApk(file)
        if (validationError != null) {
            clearPendingDownload(deleteFile = true)
            return@withContext UpdateDownloadStatus.Failure(validationError)
        }
        UpdateDownloadStatus.Ready(DownloadedUpdate(info, file))
    }

    fun canRequestPackageInstalls(): Boolean =
        appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}"),
    )

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fetchLatestRelease(): AppUpdateInfo? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Squiz-Android/$currentVersionName")
        }
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IllegalStateException("GitHub 返回 ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: String): AppUpdateInfo {
        val release = JSONObject(json)
        val tagName = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        val apkAsset = (0 until assets.length())
            .asSequence()
            .map(assets::getJSONObject)
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IllegalStateException("最新版 Release 中没有 APK")
        val assetDigest = apkAsset.optString("digest")
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
        val checksumAsset = (0 until assets.length())
            .asSequence()
            .map(assets::getJSONObject)
            .firstOrNull { it.optString("name") == "${apkAsset.getString("name")}.sha256" }
        val digest = assetDigest ?: checksumAsset
            ?.optString("browser_download_url")
            ?.takeIf(String::isNotBlank)
            ?.let(::fetchChecksum)
            ?: throw IllegalStateException("最新版缺少 SHA-256 校验信息")

        return AppUpdateInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            releaseName = release.optString("name").ifBlank { tagName },
            notes = release.optString("body"),
            pageUrl = release.optString("html_url"),
            assetName = apkAsset.getString("name"),
            downloadUrl = apkAsset.getString("browser_download_url"),
            assetSize = apkAsset.optLong("size"),
            sha256 = digest,
        )
    }

    private fun fetchChecksum(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "Squiz-Android/$currentVersionName")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("无法读取更新校验信息")
            }
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            Regex("[A-Fa-f0-9]{64}").find(content)?.value
                ?: throw IllegalStateException("SHA-256 校验信息格式不正确")
        } finally {
            connection.disconnect()
        }
    }

    private fun validateApk(file: File): String? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: return "无法读取安装包信息"

        if (archiveInfo.packageName != appContext.packageName) return "安装包与 Squiz 不匹配"
        if (PackageInfoCompat.getLongVersionCode(archiveInfo) <= PackageInfoCompat.getLongVersionCode(installedPackageInfo())) {
            return "下载的版本不高于当前版本"
        }

        val installedSigners = signerDigests(installedPackageInfo())
        val archiveSigners = signerDigests(archiveInfo)
        if (installedSigners.isNotEmpty() && archiveSigners.isNotEmpty() && installedSigners.intersect(archiveSigners).isEmpty()) {
            return "安装包签名与当前应用不一致"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        }
    }

    private fun pendingUpdateInfo(): AppUpdateInfo? {
        val tag = preferences.getString(KEY_TAG, null) ?: return null
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        val assetName = preferences.getString(KEY_ASSET_NAME, null) ?: return null
        val downloadUrl = preferences.getString(KEY_DOWNLOAD_URL, null) ?: return null
        return AppUpdateInfo(
            tagName = tag,
            versionName = version,
            releaseName = preferences.getString(KEY_RELEASE_NAME, tag).orEmpty(),
            notes = preferences.getString(KEY_NOTES, "").orEmpty(),
            pageUrl = preferences.getString(KEY_PAGE_URL, "").orEmpty(),
            assetName = assetName,
            downloadUrl = downloadUrl,
            assetSize = preferences.getLong(KEY_ASSET_SIZE, 0L),
            sha256 = preferences.getString(KEY_SHA256, null),
        )
    }

    private fun clearPendingDownload(deleteFile: Boolean = false) {
        if (deleteFile) preferences.getString(KEY_FILE_PATH, null)?.let(::File)?.delete()
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_FILE_PATH)
            .remove(KEY_TAG)
            .remove(KEY_VERSION)
            .remove(KEY_RELEASE_NAME)
            .remove(KEY_NOTES)
            .remove(KEY_PAGE_URL)
            .remove(KEY_ASSET_NAME)
            .remove(KEY_DOWNLOAD_URL)
            .remove(KEY_ASSET_SIZE)
            .remove(KEY_SHA256)
            .apply()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/hagemon/squiz/releases/latest"
        const val PREFERENCES_NAME = "app_updates"
        const val AUTOMATIC_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L
        const val UPDATE_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        const val KEY_LAST_CHECK = "last_check"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_TAG = "tag"
        const val KEY_VERSION = "version"
        const val KEY_RELEASE_NAME = "release_name"
        const val KEY_NOTES = "notes"
        const val KEY_PAGE_URL = "page_url"
        const val KEY_ASSET_NAME = "asset_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_ASSET_SIZE = "asset_size"
        const val KEY_SHA256 = "sha256"
    }
}

internal fun compareReleaseVersions(left: String, right: String): Int {
    fun components(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { component -> component.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val leftParts = components(left)
    val rightParts = components(right)
    val count = maxOf(leftParts.size, rightParts.size)
    repeat(count) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}
