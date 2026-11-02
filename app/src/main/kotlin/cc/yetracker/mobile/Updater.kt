package cc.yetracker.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater for GitHub releases. The app reads the newest published release of
 * https://github.com/Yexclsv25/Ye-Tracker, compares its tag (e.g. v1.1) with the installed
 * versionName, and offers to download and install the .apk attached to that release.
 */
object Updater {
    const val REPO = "Yexclsv25/Ye-Tracker"
    const val RELEASES_PAGE = "https://github.com/" + REPO + "/releases"

    class Info(val name: String, val url: String, val notes: String, val sha256: String)

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** "v1.10.2" -> [1, 10, 2]; anything non-numeric is ignored. */
    private fun parts(v: String): List<Int> =
        Regex("""\d+""").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest non-draft, non-prerelease GitHub release that has an .apk attached. */
    fun fetch(): Info {
        val c = URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "YeTracker-Android")
            if (c.responseCode == 404) throw IOException("No release published yet")
            if (c.responseCode !in 200..299) throw IOException("GitHub answered HTTP " + c.responseCode)
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            val assets = o.optJSONArray("assets") ?: throw IOException("Release has no files")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").lowercase().endsWith(".apk")) {
                    return Info(
                        tag.removePrefix("v"), a.getString("browser_download_url"), o.optString("body"),
                        a.optString("digest").removePrefix("sha256:").lowercase()
                    )
                }
            }
            throw IOException("Release " + tag + " has no .apk attached")
        } finally {
            c.disconnect()
        }
    }

    /** Blocking download into the app cache. Verifies SHA-256 when GitHub provides one. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        if (!info.url.startsWith("https://")) throw IOException("Update link must be https")
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        val out = File(dir, "ye-tracker-" + info.name.replace(Regex("[^A-Za-z0-9.]"), "_") + ".apk")
        dir.listFiles()?.forEach { if (it.name != out.name) it.delete() }
        if (out.exists() && verified(out, info)) { onProgress(100); return out }
        out.delete()
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "YeTracker-Android")
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (!verified(out, info)) {
            out.delete()
            throw IOException("Downloaded file failed the integrity check")
        }
        return out
    }

    private fun verified(f: File, info: Info): Boolean {
        if (f.length() == 0L) return false
        if (info.sha256.isEmpty()) return true
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == info.sha256
    }
}
