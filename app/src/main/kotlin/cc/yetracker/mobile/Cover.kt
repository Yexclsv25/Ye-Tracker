package cc.yetracker.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.util.LruCache
import android.view.View
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Album art. The tracker itself exposes no cover images, so eras that match an official
 * release (Graduation, Yeezus, DONDA ...) get that album's cover from the iTunes Search API.
 * Everything else keeps its generated gradient.
 */
object Cover {
    private val urls = ConcurrentHashMap<String, String>() // era -> url, "" = none
    private val bmps = object : LruCache<String, Bitmap>(60) {}
    private val pool = Executors.newFixedThreadPool(3)
    private val waiting = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()

    private fun clean(s: String): String =
        s.replace(Regex("""\s*\[V\d+\]"""), "").replace(Regex("""\s*[\(\[].*?[\)\]]"""), "").trim()

    private fun norm(s: String): String = clean(s).lowercase().filter { it.isLetterOrDigit() }

    private fun get(u: String): ByteArray {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "YeTracker/1.0")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun lookup(era: String): String {
        urls[era]?.let { return it }
        var found = ""
        try {
            val term = clean(era)
            val want = norm(era)
            if (want.isNotEmpty()) {
                val q = URLEncoder.encode("Kanye West $term", "UTF-8")
                val txt = String(get("https://itunes.apple.com/search?term=$q&entity=album&limit=10"), Charsets.UTF_8)
                val arr = JSONObject(txt).optJSONArray("results")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val artist = o.str("artistName").lowercase()
                        val words = artist.split(Regex("[^a-z¥$]+"))
                        val okArtist = words.contains("ye") || words.contains("kanye") || words.contains("jay") ||
                            words.contains("cudi") || words.contains("dolla") || words.contains("ghosts") || words.contains("chance")
                        if (okArtist && norm(o.str("collectionName")) == want) {
                            found = o.str("artworkUrl100").replace("100x100bb", "600x600bb")
                            break
                        }
                    }
                }
            }
            urls[era] = found
        } catch (e: Exception) {
            // network hiccup: don't cache, try again later
        }
        return found
    }

    fun urlFor(era: String): String? = urls[era]?.takeIf { it.isNotEmpty() }

    fun cached(era: String): Bitmap? = urlFor(era)?.let { bmps.get(it) }

    fun knownNone(era: String): Boolean = urls[era] == ""

    fun fetch(era: String, cb: (Bitmap?) -> Unit) {
        synchronized(waiting) {
            val l = waiting[era]
            if (l != null) { l.add(cb); return }
            waiting[era] = arrayListOf(cb)
        }
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val u = lookup(era)
                if (u.isNotEmpty()) {
                    bmp = bmps.get(u) ?: run {
                        val bytes = get(u)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bmps.put(u, it) }
                    }
                }
            } catch (e: Exception) {
            }
            val cbs = synchronized(waiting) { waiting.remove(era) }
            cbs?.forEach { it(bmp) }
        }
    }
}

fun View.loadCover(era: String, radiusDp: Float, onResult: (Boolean) -> Unit = {}) {
    setTag(R.id.cover_tag, era)
    onResult(false)
    if (era.isEmpty()) return
    fun apply(b: Bitmap) {
        val d = RoundedBitmapDrawableFactory.create(resources, b)
        d.cornerRadius = radiusDp * resources.displayMetrics.density
        d.setAntiAlias(true)
        background = d
        onResult(true)
    }
    val c = Cover.cached(era)
    if (c != null) { apply(c); return }
    if (Cover.knownNone(era)) return
    Cover.fetch(era) { bmp ->
        post {
            if (bmp != null && getTag(R.id.cover_tag) == era) apply(bmp)
        }
    }
}

fun coverUri(era: String): Uri? = Cover.urlFor(era)?.let { Uri.parse(it) }

/** Generated artwork for notifications / lockscreen when no official cover exists. */
fun gradientArt(era: String): ByteArray {
    val size = 512
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = Canvas(b)
    val (c1, c2) = seedColors(era)
    val p = Paint()
    p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
    val t = TextPaint()
    t.color = Color.WHITE
    t.textSize = 48f
    t.isAntiAlias = true
    t.isFakeBoldText = true
    val text = if (era.isEmpty()) "Ye Tracker" else era
    val sl = StaticLayout.Builder.obtain(text, 0, text.length, t, size - 80)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
    cv.translate(40f, (size - sl.height) / 2f)
    sl.draw(cv)
    val o = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 85, o)
    return o.toByteArray()
}
