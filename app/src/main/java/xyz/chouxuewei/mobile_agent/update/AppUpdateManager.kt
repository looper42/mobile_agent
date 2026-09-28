package xyz.chouxuewei.mobile_agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.chouxuewei.mobile_agent.core.localizedText

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String?,
)

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apk: ReleaseAsset,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val latestVersion: String) : AppUpdateState
    data class Available(val release: ReleaseInfo) : AppUpdateState
    data class Downloading(
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : AppUpdateState
    data class Downloaded(val release: ReleaseInfo, val apkFile: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class AppUpdateManager(
    context: Context,
    private val scope: CoroutineScope,
    private val currentVersionName: String,
) {
    private val appContext = context.applicationContext
    private val updateRoot = File(appContext.cacheDir, UPDATE_DIRECTORY)
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private var operation: Job? = null

    fun checkForUpdates(force: Boolean = false) {
        if (operation?.isActive == true) return
        if (!force && mutableState.value is AppUpdateState.Available) return
        if (!force && mutableState.value is AppUpdateState.Downloaded) return
        operation = scope.launch {
            mutableState.value = AppUpdateState.Checking
            mutableState.value = try {
                withContext(Dispatchers.IO) { queryLatestRelease(currentVersionName) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                AppUpdateState.Error(updateErrorMessage(failure))
            }
        }
    }

    fun download(release: ReleaseInfo) {
        if (operation?.isActive == true) return
        operation = scope.launch {
            mutableState.value = AppUpdateState.Downloading(release, 0L, release.apk.sizeBytes.takeIf { it > 0L })
            mutableState.value = try {
                val file = withContext(Dispatchers.IO) {
                    downloadApk(release) { downloaded, total ->
                        mutableState.value = AppUpdateState.Downloading(release, downloaded, total)
                    }
                }
                AppUpdateState.Downloaded(release, file)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                AppUpdateState.Error(updateErrorMessage(failure))
            }
        }
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launchInstaller(apkFile: File) {
        val updateRootPath = updateRoot.canonicalFile.path + File.separator
        require(apkFile.isFile && apkFile.canonicalFile.path.startsWith(updateRootPath)) {
            localizedText("更新安装包不存在或位置不安全", "The update package is missing or stored in an unsafe location")
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            apkFile,
        )
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun queryLatestRelease(installedVersion: String): AppUpdateState {
        val connection = openConnection(LATEST_RELEASE_API)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw githubApiFailure(status, connection)
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = parseGitHubRelease(payload)
            if (!isNewerReleaseVersion(parsed.tagName, installedVersion)) {
                AppUpdateState.UpToDate(parsed.tagName)
            } else {
                val apk = selectApkAsset(parsed.assets, Build.SUPPORTED_ABIS.toList())
                    ?: throw IOException(localizedText(
                        "最新 Release 没有可安装的正式 APK",
                        "The latest release does not contain an installable release APK",
                    ))
                AppUpdateState.Available(
                    ReleaseInfo(
                        tagName = parsed.tagName,
                        title = parsed.title.ifBlank { parsed.tagName },
                        notes = parsed.notes,
                        pageUrl = parsed.pageUrl,
                        apk = apk,
                    ),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadApk(
        release: ReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        updateRoot.mkdirs()
        require(updateRoot.isDirectory) { localizedText("无法创建更新缓存目录", "Could not create the update cache directory") }
        val safeTag = release.tagName.replace(Regex("[^0-9A-Za-z._-]"), "_").take(80)
        val destination = File(updateRoot, "mobile-agent-$safeTag.apk")
        val temporary = File(updateRoot, ".${destination.name}.download")
        temporary.delete()

        val connection = openConnection(release.apk.downloadUrl)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException(localizedText(
                "下载更新失败（HTTP $status）",
                "Could not download the update (HTTP $status)",
            ))
            val declaredLength = connection.contentLengthLong.takeIf { it > 0L }
                ?: release.apk.sizeBytes.takeIf { it > 0L }
            if (declaredLength != null && declaredLength > MAX_APK_BYTES) {
                throw IOException(localizedText("更新安装包超过 300 MB", "The update package exceeds 300 MB"))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES) {
                            throw IOException(localizedText("更新安装包超过 300 MB", "The update package exceeds 300 MB"))
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (
                            downloaded - lastReported >= PROGRESS_REPORT_BYTES ||
                            downloaded == declaredLength
                        ) {
                            lastReported = downloaded
                            onProgress(downloaded, declaredLength)
                        }
                    }
                }
            }
            if (declaredLength != null && downloaded != declaredLength) {
                throw IOException(localizedText("更新安装包下载不完整", "The update package download is incomplete"))
            }
            verifyDigest(release.apk.digest, digest.digest())
            if (destination.exists() && !destination.delete()) {
                throw IOException(localizedText("无法替换旧的更新安装包", "Could not replace the old update package"))
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            updateRoot.listFiles()?.filter { it != destination }?.forEach(File::delete)
            return destination
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val endpoint = URL(url)
        require(endpoint.protocol.equals("https", ignoreCase = true)) {
            localizedText("更新地址必须使用 HTTPS", "The update URL must use HTTPS")
        }
        return (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "mobile-agent-android/$currentVersionName")
        }
    }

    private fun githubApiFailure(status: Int, connection: HttpURLConnection): IOException {
        val message = when (status) {
            HttpURLConnection.HTTP_NOT_FOUND -> localizedText(
                "GitHub 上还没有正式 Release",
                "No published GitHub release was found",
            )
            HTTP_RATE_LIMITED -> localizedText(
                "GitHub 请求次数受限，请稍后重试",
                "GitHub request limit reached. Try again later",
            )
            else -> localizedText(
                "检查更新失败（HTTP $status）",
                "Could not check for updates (HTTP $status)",
            )
        }
        connection.errorStream?.close()
        return IOException(message)
    }

    private fun updateErrorMessage(failure: Exception): String =
        failure.message?.takeIf(String::isNotBlank)
            ?: localizedText("检查更新失败，请确认网络连接后重试", "Could not check for updates. Check your connection and try again")

    private fun verifyDigest(expectedDigest: String?, actualBytes: ByteArray) {
        val expected = expectedDigest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
            ?: return
        val actual = actualBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (expected != actual) {
            throw IOException(localizedText("更新安装包校验失败", "The update package failed integrity verification"))
        }
    }

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/looper42/mobile_agent/releases/latest"
        const val UPDATE_DIRECTORY = "app-updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_APK_BYTES = 300L * 1024L * 1024L
        const val PROGRESS_REPORT_BYTES = 256L * 1024L
        const val HTTP_RATE_LIMITED = 403
    }
}

internal data class ParsedGitHubRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
)

internal fun parseGitHubRelease(payload: String): ParsedGitHubRelease {
    val root = Json.parseToJsonElement(payload).jsonObject
    return ParsedGitHubRelease(
        tagName = root.getValue("tag_name").jsonPrimitive.content,
        title = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        pageUrl = root.getValue("html_url").jsonPrimitive.content,
        assets = root.getValue("assets").jsonArray.map { element ->
            val asset = element.jsonObject
            ReleaseAsset(
                name = asset.getValue("name").jsonPrimitive.content,
                downloadUrl = asset.getValue("browser_download_url").jsonPrimitive.content,
                sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                digest = asset["digest"]?.jsonPrimitive?.contentOrNull,
            )
        },
    )
}

internal fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    val supported = supportedAbis.joinToString(" ").lowercase()
    return assets
        .asSequence()
        .filter { it.name.endsWith(".apk", ignoreCase = true) }
        .filterNot { it.name.contains("debug", ignoreCase = true) || it.name.contains("unsigned", ignoreCase = true) }
        .maxWithOrNull(compareBy<ReleaseAsset> { asset ->
            val name = asset.name.lowercase()
            when {
                "universal" in name -> 100
                "arm64" in name && "arm64" in supported -> 80
                ("armeabi" in name || "armv7" in name) && "armeabi" in supported -> 70
                "x86_64" in name && "x86_64" in supported -> 60
                "x86" in name && "x86" in supported -> 50
                else -> 0
            } + (if ("release" in name) 20 else 0) + (if ("mobile" in name) 5 else 0)
        }.thenByDescending { it.name })
}

internal fun isNewerReleaseVersion(releaseTag: String, installedVersion: String): Boolean {
    val release = parseSemanticVersion(releaseTag)
        ?: throw IOException(localizedText(
            "Release 标签“$releaseTag”不是有效版本号",
            "Release tag '$releaseTag' is not a valid version. ",
        ))
    val installed = parseSemanticVersion(installedVersion)
        ?: throw IOException(localizedText(
            "当前应用版本“$installedVersion”不是有效版本号",
            "Installed app version '$installedVersion' is not valid",
        ))
    return compareSemanticVersions(release, installed) > 0
}

private data class SemanticVersion(
    val numbers: List<String>,
    val prerelease: List<String>?,
)

private fun parseSemanticVersion(value: String): SemanticVersion? {
    val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
    return SemanticVersion(
        numbers = match.groupValues[1].split('.'),
        prerelease = match.groupValues[2].takeIf(String::isNotEmpty)?.split('.'),
    )
}

private fun compareSemanticVersions(left: SemanticVersion, right: SemanticVersion): Int {
    val width = maxOf(left.numbers.size, right.numbers.size)
    repeat(width) { index ->
        val comparison = compareNumericIdentifiers(
            left.numbers.getOrElse(index) { "0" },
            right.numbers.getOrElse(index) { "0" },
        )
        if (comparison != 0) return comparison
    }
    val leftPre = left.prerelease
    val rightPre = right.prerelease
    if (leftPre == null && rightPre == null) return 0
    if (leftPre == null) return 1
    if (rightPre == null) return -1
    val widthPre = maxOf(leftPre.size, rightPre.size)
    repeat(widthPre) { index ->
        val leftPart = leftPre.getOrNull(index) ?: return -1
        val rightPart = rightPre.getOrNull(index) ?: return 1
        val leftNumeric = leftPart.all(Char::isDigit)
        val rightNumeric = rightPart.all(Char::isDigit)
        val comparison = when {
            leftNumeric && rightNumeric -> compareNumericIdentifiers(leftPart, rightPart)
            leftNumeric -> -1
            rightNumeric -> 1
            else -> leftPart.compareTo(rightPart)
        }
        if (comparison != 0) return comparison
    }
    return 0
}

private fun compareNumericIdentifiers(left: String, right: String): Int {
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return normalizedLeft.length.compareTo(normalizedRight.length)
        .takeIf { it != 0 }
        ?: normalizedLeft.compareTo(normalizedRight)
}

private val VERSION_PATTERN = Regex(
    "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
)
