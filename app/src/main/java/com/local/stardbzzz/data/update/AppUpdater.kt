package com.local.stardbzzz.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.local.stardbzzz.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val buildNumber: Int,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateState
}

/**
 * Checks GitHub Releases for a newer CI build and downloads its APK.
 * Builds are compared by the trailing number of the tag (v1.1.<build>) against BuildConfig.BUILD_NUMBER.
 */
class AppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Separate client: the stardb client injects session cookies and auth headers.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** Silent check, at most once per day; failures are ignored. */
    fun checkAutomatically() {
        val lastCheck = prefs.getLong(KeyLastCheck, 0L)
        val now = System.currentTimeMillis()
        if (now >= lastCheck && now - lastCheck < AutoCheckIntervalMillis) return
        if (_state.value != UpdateState.Idle) return

        scope.launch {
            val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@launch
            prefs.edit().putLong(KeyLastCheck, now).apply()
            if (release.buildNumber > BuildConfig.BUILD_NUMBER &&
                release.buildNumber != prefs.getInt(KeyIgnoredBuild, -1)
            ) {
                _state.compareAndSet(UpdateState.Idle, UpdateState.Available(release))
            }
        }
    }

    /** User-initiated check: always reports a result, and ignores the "skip this version" choice. */
    fun checkManually() {
        if (!_state.compareAndSet(UpdateState.Idle, UpdateState.Checking)) return

        scope.launch {
            try {
                val release = fetchLatestRelease()
                prefs.edit().putLong(KeyLastCheck, System.currentTimeMillis()).apply()
                if (release.buildNumber > BuildConfig.BUILD_NUMBER) {
                    _state.value = UpdateState.Available(release)
                } else {
                    _state.value = UpdateState.Idle
                    _messages.tryEmit("已是最新版本（$currentVersionName）")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                _state.value = UpdateState.Idle
                _messages.tryEmit("检查更新失败：${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    fun postpone() {
        _state.value = UpdateState.Idle
    }

    fun ignore(release: ReleaseInfo) {
        prefs.edit().putInt(KeyIgnoredBuild, release.buildNumber).apply()
        _state.value = UpdateState.Idle
    }

    fun download(release: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        _state.value = UpdateState.Downloading(release, 0L, release.apkSize)
        downloadJob = scope.launch {
            try {
                val apk = downloadApk(release)
                _state.value = UpdateState.ReadyToInstall(release, apk)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                _state.value = UpdateState.Idle
                _messages.tryEmit("下载失败：${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Idle
    }

    fun dismissInstall() {
        _state.value = UpdateState.Idle
    }

    fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LatestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "zzz-achievement/$currentVersionName")
            .build()
        val body = client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw IOException("还没有发布版本")
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("响应为空")
        }
        parseRelease(JSONObject(body))
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val tagName = json.getString("tag_name")
        val buildNumber = TagPattern.matchEntire(tagName)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("无法识别版本号 $tagName")
        val assets = json.optJSONArray("assets")
        val apkAsset = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("$tagName 没有 APK 文件")
        val digest = apkAsset.stringOrEmpty("digest")
        return ReleaseInfo(
            tagName = tagName,
            buildNumber = buildNumber,
            notes = json.stringOrEmpty("body").trim(),
            apkUrl = apkAsset.getString("browser_download_url"),
            apkSize = apkAsset.optLong("size", -1L),
            apkSha256 = digest.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
        )
    }

    private suspend fun downloadApk(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "zzz-achievement-${release.tagName}.apk")
        val partial = File(dir, "${target.name}.part")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L

        try {
            val request = Request.Builder().url(release.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应为空")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                var lastReportedPercent = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sha256.update(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            if (percent != lastReportedPercent || total <= 0) {
                                lastReportedPercent = percent
                                // Don't resurrect the progress dialog after cancelDownload() reset the state.
                                _state.update { current ->
                                    if (current is UpdateState.Downloading && current.release == release) {
                                        UpdateState.Downloading(release, downloaded, total)
                                    } else {
                                        current
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (release.apkSize > 0 && downloaded != release.apkSize) {
                throw IOException("文件不完整（$downloaded / ${release.apkSize} 字节）")
            }
            val actualSha256 = sha256.digest().joinToString("") { "%02x".format(it) }
            if (release.apkSha256 != null && release.apkSha256 != actualSha256) {
                throw IOException("文件校验失败")
            }
            val packageName = appContext.packageManager.getPackageArchiveInfo(partial.path, 0)?.packageName
            if (packageName != appContext.packageName) {
                throw IOException("安装包无效")
            }
            if (!partial.renameTo(target)) throw IOException("无法保存安装包")
            target
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    private fun JSONObject.stringOrEmpty(name: String): String = if (isNull(name)) "" else optString(name)

    private companion object {
        const val LatestReleaseUrl = "https://api.github.com/repos/tiger-git2022/zzz-achievement/releases/latest"
        const val AutoCheckIntervalMillis = 24 * 60 * 60 * 1000L
        const val KeyLastCheck = "last_check_epoch_millis"
        const val KeyIgnoredBuild = "ignored_build_number"
        val TagPattern = Regex("""v?\d+\.\d+\.(\d+)""")
    }
}
