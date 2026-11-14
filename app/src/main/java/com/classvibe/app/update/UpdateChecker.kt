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
    val prerelease: Boolean,
    /** Checksum published in version.json (if known); otherwise it is fetched from [shaUrl]. */
    val sha256: String? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("tag", tag); put("title", title); put("notes", notes); put("apkUrl", apkUrl); put("apkName", apkName)
        put("shaUrl", shaUrl ?: ""); put("pageUrl", pageUrl); put("prerelease", prerelease); put("sha256", sha256 ?: "")
    }.toString()

    companion object {
        fun fromJson(s: String?): UpdateInfo? = try {
            if (s.isNullOrBlank()) null else JSONObject(s).let {
                UpdateInfo(
                    it.getString("tag"), it.optString("title"), it.optString("notes"), it.getString("apkUrl"),
                    it.optString("apkName", "ClassVibe-${it.getString("tag")}.apk"), it.optString("shaUrl").ifBlank { null },
                    it.optString("pageUrl"), it.optBoolean("prerelease"), it.optString("sha256").ifBlank { null }
                )
            }
        } catch (e: Exception) { null }
    }
}

/**
 * Finds a newer ClassVibe release. Three sources, tried in order, so checks work even on
 * busy college Wi-Fi where GitHub's API limit (60 requests/hour per network) is used up:
 *  1. version.json on the project website (GitHub Pages, written by the release workflow) – no hourly limit;
 *  2. the github.com/…/releases/latest redirect – a normal web page (stable releases only);
 *  3. the GitHub REST API.
 * Privacy: anonymous HTTPS GETs only – no personal data.
 * Security: the APK's SHA-256 must match the published checksum and its signing certificate
 * must match this app's before [UpdateInstaller] hands it to Android's package installer.
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

    class HttpError(val code: Int) : Exception("Server answered $code")

    private fun open(url: String, follow: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = follow
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
            setRequestProperty("User-Agent", "ClassVibe-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw HttpError(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private val repo: String get() = BuildConfig.GITHUB_REPO
    private val owner: String get() = repo.substringBefore('/')
    private val repoName: String get() = repo.substringAfter('/')

    /** The project website, e.g. https://aksharganji23.github.io/ClassVibe/ */
    val websiteUrl: String get() = "https://${owner.lowercase()}.github.io/$repoName/"

    /** Standard asset names produced by .github/workflows/release.yml. */
    fun apkNameFor(tag: String) = "ClassVibe-$tag.apk"

    private val current: Semver get() = Semver.parse(BuildConfig.VERSION_NAME) ?: Semver(0, 0, 0, "")

    suspend fun check(context: Context, includePrerelease: Boolean): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.NotConfigured
        val failures = mutableListOf<Exception>()
        var found: Pair<Semver, UpdateInfo>? = null
        var answered = false
        // 1) version.json on the website
        try {
            found = fromVersionJson(includePrerelease); answered = true
        } catch (e: Exception) { failures.add(e) }
        // 2) releases/latest redirect (stable only – betas need the list)
        if (!answered && !includePrerelease) {
            try {
                found = fromLatestRedirect(); answered = true
            } catch (e: Exception) { failures.add(e) }
        }
        // 3) GitHub API
        if (!answered) {
            try {
                found = fromApi(includePrerelease); answered = true
            } catch (e: Exception) { failures.add(e) }
        }
        if (!answered) return@withContext Result.Failed(explain(failures))
        AppSettings.update(context) {
            it[AppSettings.Keys.LAST_UPDATE_CHECK] = System.currentTimeMillis()
            it[AppSettings.Keys.AVAILABLE_UPDATE] = found?.second?.toJson() ?: ""
        }
        found?.let { Result.Available(it.second) } ?: Result.UpToDate
    }

    private fun explain(failures: List<Exception>): String {
        val codes = failures.filterIsInstance<HttpError>().map { it.code }
        return when {
            codes.any { it == 403 || it == 429 } ->
                "Too many update checks from this network 😅 (shared college Wi-Fi?). Try mobile data or wait an hour."
            failures.any { it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException } ->
                "No internet connection (or the network blocks GitHub). Try mobile data."
            failures.any { it is javax.net.ssl.SSLException } ->
                "This network intercepts secure connections (college firewall?). Try mobile data."
            codes.isNotEmpty() -> "GitHub answered ${codes.last()}. Try again later."
            else -> failures.lastOrNull()?.message ?: "No internet connection"
        }
    }

    /** version.json: { "stable": {release}, "beta": {release} | null } */
    private fun fromVersionJson(includePrerelease: Boolean): Pair<Semver, UpdateInfo>? {
        val root = JSONObject(get(websiteUrl + "version.json?t=" + System.currentTimeMillis() / 60_000))
        val candidates = listOfNotNull(
            root.optJSONObject("stable"),
            if (includePrerelease) root.optJSONObject("beta") else null
        )
        var best: Pair<Semver, UpdateInfo>? = null
        for (r in candidates) {
            val tag = r.optString("tag")
            val apkUrl = r.optString("apk_url")
            if (tag.isBlank() || apkUrl.isBlank()) continue
            val v = Semver.parse(tag) ?: continue
            if (v <= current) continue
            val info = UpdateInfo(
                tag = tag,
                title = r.optString("title").ifBlank { tag },
                notes = r.optString("notes"),
                apkUrl = apkUrl,
                apkName = r.optString("apk_name").ifBlank { apkNameFor(tag) },
                shaUrl = r.optString("sha_url").ifBlank { null },
                pageUrl = r.optString("page_url"),
                prerelease = r.optBoolean("prerelease"),
                sha256 = r.optString("sha256").ifBlank { null }
            )
            if (best == null || v > best.first) best = v to info
        }
        return best
    }

    /** github.com/owner/repo/releases/latest → 302 to …/releases/tag/vX.Y.Z */
    private fun fromLatestRedirect(): Pair<Semver, UpdateInfo>? {
        val conn = open("https://github.com/$repo/releases/latest", follow = false)
        val location = try {
            if (conn.responseCode !in 300..399) throw HttpError(conn.responseCode)
            conn.getHeaderField("Location") ?: throw HttpError(conn.responseCode)
        } finally {
            conn.disconnect()
        }
        val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').ifBlank { return null }
        val v = Semver.parse(tag) ?: return null
        if (v <= current) return null
        val name = apkNameFor(tag)
        val apkUrl = "https://github.com/$repo/releases/download/$tag/$name"
        return v to UpdateInfo(tag, tag, "", apkUrl, name, "$apkUrl.sha256", location, prerelease = false)
    }

    private fun fromApi(includePrerelease: Boolean): Pair<Semver, UpdateInfo>? {
        run {
            val releases = JSONArray(get("https://api.github.com/repos/$repo/releases?per_page=15"))
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
                    // Prefer the versioned file (ClassVibe-v1.3.0.apk); old releases also had a plain ClassVibe.apk.
                    if (name.endsWith(".apk") && (apk == null || name != "ClassVibe.apk")) apk = asset
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
            return best
        }
    }

    /** Starts the download with the system DownloadManager into the app's own folder. */
    suspend fun download(context: Context, info: UpdateInfo): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName).delete()
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("ClassVibe ${info.tag}")
            .setDescription("Downloading ${info.apkName}…")
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
    fun downloadedFile(context: Context, info: UpdateInfo): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName)

    suspend fun verify(context: Context, info: UpdateInfo): String? = withContext(Dispatchers.IO) {
        val file = downloadedFile(context, info)
        if (!file.exists()) return@withContext "Downloaded file not found."
        // 1) Checksum published with the release (in version.json, or the .sha256 file).
        val expected = info.sha256?.lowercase() ?: run {
            val shaUrl = info.shaUrl ?: return@withContext "This release has no .sha256 checksum file."
            try {
                get(shaUrl).trim().split(Regex("""\s+""")).first().lowercase()
            } catch (e: Exception) {
                return@withContext "Couldn't download the checksum."
            }
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
