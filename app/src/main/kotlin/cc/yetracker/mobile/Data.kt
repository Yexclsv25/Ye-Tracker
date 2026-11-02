package cc.yetracker.mobile

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

data class Era(val index: Int, val name: String, val years: String, val aka: String, val count: Int)

data class Song(
    val key: String,
    val title: String,
    val era: String,
    val availability: String = "",
    val quality: String = "",
    val length: String = "",
    val leaked: String = "",
    val recorded: String = "",
    val playable: Boolean = false,
    val notes: String = "",
    val localUri: String? = null,
    val released: String = ""
) {
    private val isReleased: Boolean get() = key.startsWith("released:")

    /** Released-tab titles look like "Artist - Song (feat. …)"; the artist is split off. */
    val artist: String
        get() = if (isReleased && title.contains(" - ")) title.substringBefore(" - ").trim() else ""

    val name: String
        get() {
            var t = CREDIT.replace(title, "").trim()
            if (isReleased && t.contains(" - ")) t = t.substringAfter(" - ").trim()
            return t.ifEmpty { title }
        }

    /** Line shown under the title in players: artist for released songs, era otherwise. */
    val subtitle: String get() = artist.ifEmpty { era }

    val credits: String
        get() = (CREDIT.find(title)?.value ?: "").trim().replace(") (", " · ").trim('(', ')')

    val canPlay: Boolean get() = playable || localUri != null

    val meta: String
        get() = (if (isReleased) listOf(artist, length) else listOf(availability, quality, length))
            .filter { it.isNotEmpty() }.joinToString(" · ")

    /** Second item in search rows, after the era. */
    val tag: String get() = if (isReleased) artist else availability

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("title", title).put("era", era)
        .put("availability", availability).put("quality", quality)
        .put("length", length).put("leaked", leaked).put("recorded", recorded)
        .put("playable", playable).put("notes", notes)
        .put("localUri", localUri ?: JSONObject.NULL).put("released", released)

    companion object {
        private val CREDIT = Regex("""\s\((?:prod|feat|ref|with)\b.*$""", RegexOption.IGNORE_CASE)

        fun fromJson(o: JSONObject) = Song(
            key = o.str("key"), title = o.str("title"), era = o.str("era"),
            availability = o.str("availability"), quality = o.str("quality"),
            length = o.str("length"), leaked = o.str("leaked"), recorded = o.str("recorded"),
            playable = o.optBoolean("playable", false), notes = o.str("notes"),
            localUri = if (o.isNull("localUri")) null else o.optString("localUri"),
            released = o.str("released")
        )

        /** Parses one entry from search_entries / random_entries / recent_leaks. */
        fun fromEntry(o: JSONObject) = Song(
            key = o.str("key"), title = o.str("title"), era = o.str("era"),
            availability = o.str("availability"), quality = o.str("quality"),
            length = o.str("length"), leaked = o.str("leaked"), recorded = o.str("recorded"),
            playable = o.optInt("playable_files", 0) > 0, notes = o.str("notes"),
            released = o.str("released")
        )
    }
}

/** Minimal MCP (streamable HTTP) client for https://yetracker.cc/mcp */
object Mcp {
    private const val ENDPOINT = "https://yetracker.cc/mcp"
    private const val DEFAULT_PROTO = "2025-03-26"

    @Volatile private var session: String? = null
    @Volatile private var proto: String = DEFAULT_PROTO
    @Volatile private var ready = false
    private val ids = AtomicInteger(1)
    private val lock = Any()

    private class Resp(val code: Int, val type: String, val body: String, val session: String?)

    private fun post(payload: JSONObject, withProto: Boolean = true): Resp {
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            if (withProto) c.setRequestProperty("MCP-Protocol-Version", proto)
            session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, c.contentType ?: "", body, c.getHeaderField("Mcp-Session-Id"))
        } finally {
            c.disconnect()
        }
    }

    private fun parse(r: Resp, id: Int): JSONObject {
        val text = r.body.trim()
        if (r.type.contains("text/event-stream")) {
            for (line in text.lines()) {
                val l = line.trim()
                if (!l.startsWith("data:")) continue
                val d = l.substring(5).trim()
                if (!d.startsWith("{")) continue
                val o = JSONObject(d)
                if (o.optInt("id", -1) == id || o.has("result") || o.has("error")) return o
            }
            throw IOException("Empty event stream")
        }
        return JSONObject(text)
    }

    private fun ensureInit() {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            session = null
            val id = ids.getAndIncrement()
            val params = JSONObject()
                .put("protocolVersion", DEFAULT_PROTO)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Ye Tracker Mobile").put("version", "1.0"))
            val req = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize").put("params", params)
            val r = post(req, withProto = false)
            if (r.code >= 400) throw IOException("yetracker.cc answered HTTP ${r.code}")
            session = r.session
            val o = parse(r, id)
            o.optJSONObject("result")?.optString("protocolVersion")?.takeIf { it.isNotEmpty() }?.let { proto = it }
            try {
                post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
            } catch (_: Exception) {
            }
            ready = true
        }
    }

    fun call(tool: String, args: JSONObject): JSONObject {
        ensureInit()
        var r: Resp
        var id: Int
        var attempt = 0
        while (true) {
            id = ids.getAndIncrement()
            val req = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args))
            r = post(req)
            if ((r.code == 404 || r.code == 400) && attempt == 0) {
                attempt++
                ready = false
                ensureInit()
                continue
            }
            break
        }
        if (r.code >= 400) throw IOException("yetracker.cc answered HTTP ${r.code}")
        val o = parse(r, id)
        o.optJSONObject("error")?.let { throw IOException(it.optString("message", "MCP error")) }
        val res = o.optJSONObject("result") ?: throw IOException("Empty MCP result")
        res.optJSONObject("structuredContent")?.let { return it }
        val content = res.optJSONArray("content")
        var text = ""
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                if (c.optString("type") == "text") { text = c.optString("text"); break }
            }
        }
        if (res.optBoolean("isError", false)) throw IOException(text.ifEmpty { "Tool error" })
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("text", text)
        }
    }
}

object Tracker {
    val AVAIL = listOf("OG File", "Full", "Tagged", "Partial", "Snippet", "Stem Bounce", "Beat Only", "Confirmed", "Rumored", "Conflicting Sources")
    val QUAL = listOf("Lossless", "CD Quality", "High Quality", "Low Quality", "Recording", "Not Available")

    class Page(val total: Int, val songs: List<Song>)

    private var eraCache: List<Era>? = null

    fun eras(): List<Era> {
        eraCache?.let { return it }
        val a = Mcp.call("list_eras", JSONObject()).optJSONArray("eras") ?: JSONArray()
        val out = ArrayList<Era>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Era(i, o.str("name"), o.str("years"), o.str("aka"), o.optInt("entries")))
        }
        if (out.isNotEmpty()) eraCache = out
        return out
    }

    private fun entries(o: JSONObject): JSONArray {
        o.optJSONArray("entries")?.let { return it }
        val ks = o.keys()
        while (ks.hasNext()) {
            val v = o.opt(ks.next())
            if (v is JSONArray) return v
        }
        return JSONArray()
    }

    private fun songs(a: JSONArray): List<Song> {
        val out = ArrayList<Song>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(Song.fromEntry(it)) }
        return out
    }

    fun search(
        tab: String?, query: String?, era: String?,
        avail: List<String>?, quality: List<String>?, marked: List<String>?, limit: Int = 50
    ): Page {
        val args = JSONObject().put("limit", limit)
        if (!tab.isNullOrEmpty()) args.put("tab", tab)
        if (!query.isNullOrBlank()) args.put("query", query)
        if (!era.isNullOrEmpty()) args.put("era", era)
        if (avail != null) args.put("availability", JSONArray(avail))
        if (quality != null) args.put("quality", JSONArray(quality))
        if (marked != null) args.put("marked", JSONArray(marked))
        val r = Mcp.call("search_entries", args)
        val list = songs(entries(r))
        return Page(r.optInt("total", list.size), list)
    }

    fun random(count: Int, tab: String? = null, era: String? = null): List<Song> {
        val a = JSONObject().put("count", count.coerceAtMost(20)).put("playable_only", true)
        if (!tab.isNullOrEmpty()) a.put("tab", tab)
        if (!era.isNullOrEmpty()) a.put("era", era)
        return songs(entries(Mcp.call("random_entries", a)))
    }

    /**
     * The Released tab has no availability/quality to split by, so: take the first 50 of each era
     * in tracker order, then draw random playable tracks until nothing new turns up.
     */
    fun crawlReleased(era: String?, cancel: () -> Boolean, emit: (List<Song>) -> Unit) {
        val seen = HashSet<String>()
        fun take(l: List<Song>): Int {
            val fresh = l.filter { seen.add(it.key) }
            if (fresh.isNotEmpty()) emit(fresh)
            return fresh.size
        }
        fun sweep(e: String?) {
            val p = search("released", null, e, null, null, null, 50)
            take(p.songs)
            if (p.total <= p.songs.size) return
            var dry = 0
            var rounds = 0
            while (dry < 3 && rounds < 14 && !cancel()) {
                rounds++
                if (take(random(20, "released", e)) == 0) dry++ else dry = 0
            }
        }
        if (era != null) { sweep(era); return }
        for (e in eras().map { it.name }) {
            if (cancel()) return
            sweep(e)
        }
        sweep(null)
    }

    fun recent(limit: Int): List<Song> {
        val r = Mcp.call("recent_leaks", JSONObject().put("limit", limit))
        return songs(entries(r))
    }

    fun entry(key: String): JSONObject = Mcp.call("get_entry", JSONObject().put("key", key))

    fun pickFile(e: JSONObject): JSONObject? {
        val files = e.optJSONArray("files") ?: return null
        var fallback: JSONObject? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (!f.optBoolean("playable", false)) continue
            if (f.str("type").startsWith("audio")) return f
            if (fallback == null) fallback = f
        }
        return fallback
    }

    /**
     * The tracker search returns at most 50 entries per call, so big eras are split
     * by availability, then quality, then era, until every entry has been seen.
     */
    fun crawl(tab: String?, era: String?, query: String?, cancel: () -> Boolean, emit: (List<Song>) -> Unit) {
        val seen = HashSet<String>()
        fun take(p: Page): Boolean {
            val fresh = p.songs.filter { seen.add(it.key) }
            if (fresh.isNotEmpty()) emit(fresh)
            return p.total > p.songs.size
        }
        val base = search(tab, query, era, null, null, null, 50)
        if (!take(base)) return
        val total = base.total
        val eraNames = if (era == null) eras().map { it.name } else emptyList()
        for (av in AVAIL) {
            if (cancel() || seen.size >= total) return
            val a = search(tab, query, era, listOf(av), null, null, 50)
            if (!take(a)) continue
            for (q in QUAL) {
                if (cancel() || seen.size >= total) return
                val b = search(tab, query, era, listOf(av), listOf(q), null, 50)
                if (!take(b)) continue
                for (e in eraNames) {
                    if (cancel() || seen.size >= total) return
                    take(search(tab, query, e, listOf(av), listOf(q), null, 50))
                }
            }
        }
    }
}

class Stat(val song: Song, var plays: Int, var ms: Long)

object Store {
    private lateinit var sp: SharedPreferences
    private val favs = LinkedHashMap<String, Song>()
    private val recents = ArrayList<Song>()
    private val playlists = LinkedHashMap<String, ArrayList<Song>>()
    private val downloads = LinkedHashMap<String, Song>()
    private val stats = LinkedHashMap<String, Stat>()
    private var statsDirty = false
    var ms = 0L
        private set
    private var lastFlush = 0L

    fun init(c: Context) {
        sp = c.getSharedPreferences("yt", Context.MODE_PRIVATE)
        ms = sp.getLong("ms", 0L)
        try {
            val a = JSONArray(sp.getString("favs", "[]"))
            for (i in 0 until a.length()) Song.fromJson(a.getJSONObject(i)).let { favs[it.key] = it }
            val r = JSONArray(sp.getString("recents", "[]"))
            for (i in 0 until r.length()) recents.add(Song.fromJson(r.getJSONObject(i)))
            val d = JSONArray(sp.getString("downloads", "[]"))
            for (i in 0 until d.length()) Song.fromJson(d.getJSONObject(i)).let { downloads[it.key] = it }
            val st = JSONArray(sp.getString("stats", "[]"))
            for (i in 0 until st.length()) {
                val o = st.getJSONObject(i)
                val song = Song.fromJson(o.getJSONObject("song"))
                stats[song.key] = Stat(song, o.optInt("plays"), o.optLong("ms"))
            }
            val p = JSONObject(sp.getString("playlists", "{}"))
            val ks = p.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val arr = p.getJSONArray(k)
                val l = ArrayList<Song>()
                for (i in 0 until arr.length()) l.add(Song.fromJson(arr.getJSONObject(i)))
                playlists[k] = l
            }
        } catch (_: Exception) {
        }
    }

    private fun saveList(key: String, list: Collection<Song>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp.edit().putString(key, a.toString()).apply()
    }

    fun favorites(): List<Song> = favs.values.toList().asReversed()
    fun recent(): List<Song> = recents.toList()
    fun isFav(key: String) = favs.containsKey(key)

    fun toggleFav(s: Song): Boolean {
        val now = if (favs.containsKey(s.key)) { favs.remove(s.key); false } else { favs[s.key] = s; true }
        saveList("favs", favs.values)
        return now
    }

    fun addRecent(s: Song) {
        recents.removeAll { it.key == s.key }
        recents.add(0, s)
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        saveList("recents", recents)
    }

    /* ----- downloads ----- */

    private fun fileOf(s: Song): File? = s.localUri?.let { Uri.parse(it).path }?.let { File(it) }

    fun downloads(): List<Song> = downloads.values.filter { fileOf(it)?.exists() == true }.reversed()

    fun localFor(key: String): String? = downloads[key]?.takeIf { fileOf(it)?.exists() == true }?.localUri

    fun isDownloaded(key: String) = localFor(key) != null

    fun addDownload(s: Song) {
        downloads[s.key] = s
        saveList("downloads", downloads.values)
    }

    fun deleteDownload(key: String) {
        downloads.remove(key)?.let { fileOf(it)?.delete() }
        saveList("downloads", downloads.values)
    }

    /* ----- playlists ----- */

    fun playlistNames(): List<String> = playlists.keys.toList()
    fun playlist(name: String): List<Song> = playlists[name]?.toList() ?: emptyList()

    private fun savePlaylists() {
        val o = JSONObject()
        for ((k, v) in playlists) {
            val a = JSONArray()
            v.forEach { a.put(it.toJson()) }
            o.put(k, a)
        }
        sp.edit().putString("playlists", o.toString()).apply()
    }

    fun createPlaylist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || playlists.containsKey(n)) return false
        playlists[n] = ArrayList()
        savePlaylists()
        return true
    }

    fun addToPlaylist(name: String, s: Song) {
        val l = playlists.getOrPut(name) { ArrayList() }
        if (l.none { it.key == s.key }) l.add(s.copy(localUri = null))
        savePlaylists()
    }

    fun removeFromPlaylist(name: String, key: String) {
        playlists[name]?.removeAll { it.key == key }
        savePlaylists()
    }

    fun deletePlaylist(name: String) {
        playlists.remove(name)
        savePlaylists()
    }

    var accentIdx: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply() }

    fun resetStats() {
        stats.clear()
        ms = 0L
        statsDirty = false
        sp.edit().putString("stats", "[]").putLong("ms", 0L).apply()
    }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }

    var glass: Boolean
        get() = sp.getBoolean("glass", true)
        set(v) { sp.edit().putBoolean("glass", v).apply() }
    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.edit().putFloat("speed", v).apply() }
    var pitchSpeed: Boolean
        get() = sp.getBoolean("pitchSpeed", false)
        set(v) { sp.edit().putBoolean("pitchSpeed", v).apply() }
    var prefetch: Boolean
        get() = sp.getBoolean("prefetch", true)
        set(v) { sp.edit().putBoolean("prefetch", v).apply() }

    var discordOn: Boolean
        get() = sp.getBoolean("discordOn", false)
        set(v) { sp.edit().putBoolean("discordOn", v).apply() }
    var discordRefresh: String
        get() = sp.getString("discordRefresh", "") ?: ""
        set(v) { sp.edit().putString("discordRefresh", v).apply() }

    /* ----- wrapped stats ----- */

    private fun stat(s: Song): Stat = stats.getOrPut(s.key) { Stat(s.copy(localUri = null), 0, 0L) }

    fun addPlay(s: Song) { stat(s).plays++; statsDirty = true }
    fun addSongMs(s: Song, d: Long) { stat(s).ms += d; statsDirty = true }

    fun topSongs(n: Int): List<Stat> = stats.values
        .filter { it.plays > 0 }
        .sortedWith(compareByDescending<Stat> { it.plays }.thenByDescending { it.ms })
        .take(n)

    fun topEras(n: Int): List<Pair<String, Long>> = stats.values
        .groupBy { it.song.era }
        .map { Pair(it.key, it.value.sumOf { s -> s.ms }) }
        .filter { it.first.isNotEmpty() && it.second > 0 }
        .sortedByDescending { it.second }
        .take(n)

    fun totalPlays(): Int = stats.values.sumOf { it.plays }
    fun uniqueSongs(): Int = stats.values.count { it.plays > 0 }

    fun addMs(d: Long) {
        ms += d
        val now = System.currentTimeMillis()
        if (now - lastFlush > 20000) flush()
    }

    fun flush() {
        lastFlush = System.currentTimeMillis()
        val e = sp.edit().putLong("ms", ms)
        if (statsDirty) {
            statsDirty = false
            val a = JSONArray()
            stats.values.forEach { a.put(JSONObject().put("song", it.song.toJson()).put("plays", it.plays).put("ms", it.ms)) }
            e.putString("stats", a.toString())
        }
        e.apply()
    }
}
