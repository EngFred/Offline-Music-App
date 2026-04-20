package com.engfred.musicplayer.core.domain.usecases

import android.os.Build
import android.util.Log
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val TAG = "CheckForUpdateUseCase"
private const val OWNER = "EngFred"
private const val REPO  = "Offline-Music-App"

class CheckForUpdateUseCase @Inject constructor() {

    /**
     * Hits the GitHub Releases API and returns [UpdateInfo] if a newer version
     * exists, or **null** if the app is genuinely up-to-date.
     *
     * Unlike before, network / API errors are **not** silently swallowed here —
     * they propagate as exceptions so callers can distinguish between:
     *   • `null`      → check succeeded, app is up-to-date
     *   • `UpdateInfo` → check succeeded, update is available
     *   • `Exception` → check failed (network error, API error, etc.)
     *
     * This lets callers decide whether to retry or show an error, and prevents
     * the 24-hour gate in MainActivity from locking out retries after a failure.
     *
     * [currentVersion] should be passed as BuildConfig.VERSION_NAME by callers
     * so this use-case stays BuildConfig-free and fully testable.
     */
    suspend operator fun invoke(currentVersion: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // Throw so callers know the check genuinely failed — not the
                    // same as "no update available" (which returns null cleanly).
                    throw Exception("GitHub API returned HTTP $responseCode")
                }

                val body = connection.inputStream.bufferedReader().readText()

                val json         = JSONObject(body)
                val tagName      = json.getString("tag_name").removePrefix("v")
                val htmlUrl      = json.getString("html_url")
                val releaseNotes = json.optString("body", "").trim()
                val assets       = json.optJSONArray("assets")

                val downloadUrl = resolveDownloadUrl(assets, htmlUrl)

                Log.d(TAG, "Device ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}")
                Log.d(TAG, "Resolved download URL: $downloadUrl")

                if (isNewerVersion(latest = tagName, current = currentVersion)) {
                    Log.d(TAG, "Update available: $currentVersion → $tagName")
                    UpdateInfo(
                        latestVersion = tagName,
                        releaseNotes  = releaseNotes,
                        downloadUrl   = downloadUrl,
                        htmlUrl       = htmlUrl
                    )
                } else {
                    Log.d(TAG, "App is up-to-date ($currentVersion)")
                    null
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Picks the best APK asset for the device's ABI.
     *
     * Priority:
     *  1. Exact match for the device's primary ABI (e.g. arm64-v8a)
     *  2. armeabi-v7a fallback (runs on all ARM devices, including 64-bit)
     *  3. Any .apk asset as a last resort
     *  4. The release HTML page so the user can pick manually
     */
    private fun resolveDownloadUrl(
        assets: org.json.JSONArray?,
        fallbackHtmlUrl: String
    ): String {
        if (assets == null || assets.length() == 0) return fallbackHtmlUrl

        // The device's best ABI is always first in this list
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        Log.d(TAG, "Picking APK for device ABI: $deviceAbi")

        var exactMatch:   String? = null
        var armeabiMatch: String? = null
        var anyApk:       String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name  = asset.getString("name")
            val url   = asset.getString("browser_download_url")

            if (!name.endsWith(".apk")) continue

            when {
                // e.g. "Music_arm64-v8a_v2.6.0.apk" on an arm64 device
                name.contains(deviceAbi) && exactMatch == null -> exactMatch = url

                // Fallback: armeabi-v7a works on both 32-bit and 64-bit ARM devices
                name.contains("armeabi-v7a") && armeabiMatch == null -> armeabiMatch = url
            }

            if (anyApk == null) anyApk = url
        }

        return exactMatch
            ?: armeabiMatch
            ?: anyApk
            ?: fallbackHtmlUrl
    }

    /**
     * Semantic version comparison: "3.0.0" > "2.5.3" → true.
     * Handles any number of dot-separated segments gracefully.
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").map  { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(l.size, c.size)
        for (i in 0 until len) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}