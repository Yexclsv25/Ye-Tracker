package cc.yetracker.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    /** key -> percent (0..100) for downloads in progress */
    val progress = ConcurrentHashMap<String, Int>()

    /** Blocking. Returns null on success or an error message. Call from a background thread. */
    fun download(ctx: Context, s: Song): String? {
        if (progress.containsKey(s.key)) return "Already downloading"
        progress[s.key] = 0
        try {
            val f = Tracker.pickFile(Tracker.entry(s.key)) ?: return "No file available for this song"
            val urls = listOf(f.str("download"), f.str("url")).filter { it.isNotEmpty() }
            if (urls.isEmpty()) return "No file available for this song"
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            dir.mkdirs()
            val ext = f.str("name").substringAfterLast('.', "mp3").take(5)
            val base = s.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "track" }
            val out = File(dir, base + " [" + s.key.replace(':', '-') + "]." + ext)
            val part = File(dir, out.name + ".part")
            var lastErr = "Download failed"
            for (u in urls) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) YeTracker/1.0")
                    c.setRequestProperty("Referer", "https://yetracker.cc/")
                    val code = c.responseCode
                    if (code !in 200..299) { lastErr = "HTTP $code"; c.disconnect(); continue }
                    if ((c.contentType ?: "").startsWith("text/html")) { lastErr = "Server sent a web page, not audio"; c.disconnect(); continue }
                    val total = c.contentLengthLong
                    c.inputStream.use { inp ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                read += n
                                if (total > 0) progress[s.key] = (read * 100 / total).toInt()
                            }
                        }
                    }
                    c.disconnect()
                    if (part.length() == 0L) { lastErr = "Empty file"; continue }
                    out.delete()
                    if (!part.renameTo(out)) { lastErr = "Couldn't save file"; continue }
                    Store.addDownload(s.copy(localUri = Uri.fromFile(out).toString(), playable = true))
                    copyToMusic(ctx, out, ext, s.name)
                    return null
                } catch (e: Exception) {
                    lastErr = e.message ?: "Network error"
                }
            }
            part.delete()
            return lastErr
        } catch (e: Exception) {
            return e.message ?: "Download failed"
        } finally {
            progress.remove(s.key)
        }
    }

    /** Also drops a copy into the public Music/Ye Tracker folder so other players can see it. */
    private fun copyToMusic(ctx: Context, file: File, ext: String, title: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val mime = when (ext.lowercase()) {
                "wav" -> "audio/x-wav"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                else -> "audio/mpeg"
            }
            val v = ContentValues()
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime)
            v.put(MediaStore.Audio.Media.TITLE, title)
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Ye Tracker")
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        } catch (_: Exception) {
        }
    }
}
