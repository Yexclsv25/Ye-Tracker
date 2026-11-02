package cc.yetracker.mobile

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater for builds that aren't on a store. The app downloads a small JSON file you host
 * (see README), and if it lists a higher versionCode it offers to download and install the APK.
 */
object Updater {
    /** Address of your update.json (https). Leave empty to turn the updater off. */
    const val MANIFEST_URL = ""

    class Info(val code: Long, val name: String, val url: String, val notes: String, val sha256: String)

    fun currentCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    fun fetch(): Info {
        val c = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return Info(
                o.getLong("versionCode"), o.optString("versionName"), o.getString("apkUrl"),
                o.optString("notes"), o.optString("sha256").lowercase()
            )
        } finally {
            c.disconnect()
        }
    }

    /** Blocking download into the app cache. Verifies SHA-256 when the manifest provides one. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        if (!info.url.startsWith("https://")) throw IOException("Update link must be https")
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        val out = File(dir, "ye-tracker-" + info.code + ".apk")
        dir.listFiles()?.forEach { if (it.name != out.name) it.delete() }
        if (out.exists() && verified(out, info)) { onProgress(100); return out }
        out.delete()
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
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
