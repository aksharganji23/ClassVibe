package com.classvibe.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.classvibe.app.BuildConfig
import com.classvibe.app.data.AppSettings
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer ClassVibe release found on GitHub. */
data class UpdateInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val shaUrl: String?,
    val pageUrl: String,
    val prerelease: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("tag", tag); put("title", title); put("notes", notes); put("apkUrl", apkUrl); put("apkName", apkName)
        put("shaUrl", shaUrl ?: ""); put("pageUrl", pageUrl); put("prerelease", prerelease)
    }.toString()

    companion object {
        fun fromJson(s: String?): UpdateInfo? = try {
            if (s.isNullOrBlank()) null else JSONObject(s).let {
                UpdateInfo(
                    it.getString("tag"), it.optString("title"), it.optString("notes"), it.getString("apkUrl"),
                    it.optString("apkName", "ClassVibe.apk"), it.optString("shaUrl").ifBlank { null },
                    it.optString("pageUrl"), it.optBoolean("prerelease")
                )
            }
        } catch (e: Exception) { null }
    }
}

/**
 * Checks GitHub Releases of BuildConfig.GITHUB_REPO for a newer version.
 * Privacy: sends only an anonymous HTTPS GET to api.github.com – no personal data.
 * Security: the APK is downloaded by the system DownloadManager, its SHA-256 is checked
 * against the published .sha256 file and its signing certificate must match this app's.
 * The app never installs anything itself (no "install unknown apps" permission).
 */
object UpdateChecker {

    val isConfigured: Boolean
        get() = !BuildConfig.GITHUB_REPO.startsWith("YOUR_GITHUB_USERNAME") && !BuildConfig.DEBUG

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        object UpToDate : Result()
        data class Failed(val reason: String) : Result()
        object NotConfigured : Result()
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClassVibe-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun check(context: Context, includePrerelease: Boolean): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.NotConfigured
        try {
            val releases = JSONArray(get("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases?per_page=15"))
            val current = Semver.parse(BuildConfig.VERSION_NAME) ?: Semver(0, 0, 0, "")
            var best: Pair<Semver, UpdateInfo>? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val pre = r.optBoolean("prerelease")
                if (pre && !includePrerelease) continue
                val tag = r.getString("tag_name")
                val version = Semver.parse(tag) ?: continue
                if (version <= current) continue
                val assets = r.optJSONArray("assets") ?: JSONArray()
                var apk: JSONObject? = null
                var sha: JSONObject? = null
                for (a in 0 until assets.length()) {
                    val asset = assets.getJSONObject(a)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) apk = asset
                    if (name.endsWith(".sha256")) sha = asset
                }
                apk ?: continue
                val info = UpdateInfo(
                    tag = tag,
                    title = r.optString("name").ifBlank { tag },
                    notes = r.optString("body"),
                    apkUrl = apk.getString("browser_download_url"),
                    apkName = apk.getString("name"),
                    shaUrl = sha?.getString("browser_download_url"),
                    pageUrl = r.optString("html_url"),
                    prerelease = pre
                )
                if (best == null || version > best.first) best = version to info
            }
            AppSettings.update(context) {
                it[AppSettings.Keys.LAST_UPDATE_CHECK] = System.currentTimeMillis()
                it[AppSettings.Keys.AVAILABLE_UPDATE] = best?.second?.toJson() ?: ""
            }
            best?.let { Result.Available(it.second) } ?: Result.UpToDate
        } catch (e: Exception) {
            Result.Failed(e.message ?: "No internet connection")
        }
    }

    /** Starts the download with the system DownloadManager (no storage or install permission needed). */
    suspend fun download(context: Context, info: UpdateInfo): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName).delete()
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("ClassVibe ${info.tag}")
            .setDescription("Downloading update…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, info.apkName)
        val id = dm.enqueue(request)
        AppSettings.update(context) {
            it[AppSettings.Keys.DOWNLOAD_ID] = id
            it[AppSettings.Keys.DOWNLOAD_TAG] = info.tag
        }
        return id
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo?): Set<String>? {
        info ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
        } else info.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs?.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet()
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(path, flags)
    }

    @Suppress("DEPRECATION")
    private fun ownInfo(context: Context): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    /**
     * Verifies the downloaded APK. Returns null when everything checks out, otherwise the reason.
     */
    suspend fun verify(context: Context, info: UpdateInfo): String? = withContext(Dispatchers.IO) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName)
        if (!file.exists()) return@withContext "Downloaded file not found."
        // 1) Checksum published with the release.
        val shaUrl = info.shaUrl ?: return@withContext "This release has no .sha256 checksum file."
        val expected = try {
            get(shaUrl).trim().split(Regex("""\s+""")).first().lowercase()
        } catch (e: Exception) {
            return@withContext "Couldn't download the checksum."
        }
        if (sha256(file) != expected) return@withContext "Checksum mismatch – the file may be corrupted or tampered with."
        // 2) Same package + same signing key as the installed app.
        val archive = archiveInfo(context, file.absolutePath) ?: return@withContext "The file isn't a valid APK."
        if (archive.packageName != context.packageName) return@withContext "This APK is for a different app."
        val theirs = signerHashes(archive)
        val ours = signerHashes(ownInfo(context))
        if (theirs != null && ours != null && theirs.intersect(ours).isEmpty()) {
            return@withContext "Signature mismatch – not signed by the ClassVibe developer key."
        }
        null
    }

    /** Opens the system Downloads screen, where tapping the APK installs it. */
    fun openDownloads(context: Context) {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun notifyAvailable(context: Context, info: UpdateInfo) {
        NotificationHelper.post(
            context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
            "🚀 ClassVibe ${info.tag} is available",
            info.title,
            bigText = info.title + "\n\n" + info.notes.take(400),
            contentIntent = NotificationHelper.openAppIntent(context, tab = 0),
            category = androidx.core.app.NotificationCompat.CATEGORY_RECOMMENDATION
        )
    }
}
