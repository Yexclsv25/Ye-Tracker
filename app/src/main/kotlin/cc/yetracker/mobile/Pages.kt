package cc.yetracker.mobile

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun orderOf(key: String): Long {
    Regex("""^released:(\d+)$""").find(key)?.let { return 10_000_000_000L + it.groupValues[1].toLong() }
    val m = Regex("""^e(\d+):(\d+)$""").find(key) ?: return Long.MAX_VALUE
    return m.groupValues[1].toLong() * 100000L + m.groupValues[2].toLong()
}

/* ---------- song rows ---------- */

class RowHolder(val app: MainActivity) {
    val root = LinearLayout(app)
    private val art = ImageView(app)
    private val title = app.tv("", 16f, Pal.TEXT, false, 1)
    private val sub = app.tv("", 13f, Pal.SUB, false, 1)
    private val more = app.icon(R.drawable.ic_more, Pal.SUB, 8)

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(app.dp(16), app.dp(7), app.dp(4), app.dp(7))
        art.scaleType = ImageView.ScaleType.CENTER
        art.setPadding(app.dp(14), app.dp(14), app.dp(14), app.dp(14))
        art.setImageResource(R.drawable.ic_note)
        art.setColorFilter(Color.argb(150, 255, 255, 255))
        root.addView(art, LinearLayout.LayoutParams(app.dp(52), app.dp(52)))
        val col = LinearLayout(app)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title)
        col.addView(sub)
        root.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(14) })
        root.addView(more, LinearLayout.LayoutParams(app.dp(44), app.dp(44)))
    }

    fun setSub(t: String) { sub.text = t }

    fun bind(s: Song, showEra: Boolean, onMore: () -> Unit = { app.songMenu(s) }, onTap: () -> Unit) {
        art.background = app.art(s.era, 8f)
        art.setImageResource(R.drawable.ic_note)
        art.loadCover(s.era, 8f) { has ->
            if (has) art.setImageDrawable(null) else art.setImageResource(R.drawable.ic_note)
        }
        title.text = s.name
        val playingNow = app.current?.key == s.key
        title.setTextColor(if (playingNow) Pal.ACCENT else Pal.TEXT)
        val base = if (showEra && s.era.isNotEmpty()) s.era + (if (s.tag.isNotEmpty()) " · " + s.tag else "") else s.meta.ifEmpty { s.credits }
        val prog = Downloader.progress[s.key]
        sub.text = when {
            prog != null -> "Downloading $prog%"
            Store.isDownloaded(s.key) -> "Downloaded · $base"
            else -> base
        }
        root.alpha = if (s.canPlay) 1f else 0.45f
        root.pressable { onTap() }
        more.setOnClickListener { onMore() }
    }
}

class SongAdapter(private val app: MainActivity, private val showEra: Boolean) : BaseAdapter() {
    var items: List<Song> = emptyList()
    var playlist: String? = null
    var onChanged: () -> Unit = {}

    init {
        app.adapters[this] = true
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val h: RowHolder
        val v: View
        if (convertView == null) {
            h = RowHolder(app)
            v = h.root
            v.tag = h
        } else {
            v = convertView
            h = convertView.tag as RowHolder
        }
        val s = items[position]
        h.bind(s, showEra, { app.songMenu(s, playlist, onChanged) }) { app.tap(items, s) }
        return v
    }
}

fun styleList(lv: ListView, app: MainActivity) {
    lv.divider = null
    lv.dividerHeight = 0
    lv.selector = ColorDrawable(0)
    lv.clipToPadding = false
    lv.setPadding(0, 0, 0, app.dp(230))
    lv.overScrollMode = View.OVER_SCROLL_NEVER
    lv.setBackgroundColor(Pal.BG)
}

fun pillButton(app: MainActivity, label: String, iconRes: Int, filled: Boolean, onClick: () -> Unit): LinearLayout {
    val b = LinearLayout(app)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.background = app.rounded(if (filled) Pal.ACCENT else Pal.SURFACE, 14f)
    val ic = app.icon(iconRes, if (filled) Color.BLACK else Pal.ACCENT)
    b.addView(ic, LinearLayout.LayoutParams(app.dp(20), app.dp(20)))
    val t = app.tv(label, 16f, if (filled) Color.BLACK else Pal.ACCENT, true)
    b.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = app.dp(8) })
    b.pressable(onClick)
    return b
}

fun largeTitle(app: MainActivity, text: String): TextView {
    val t = app.tv(text, 34f, Pal.TEXT, true)
    t.setPadding(app.dp(20), app.dp(14), app.dp(20), app.dp(10))
    return t
}

fun sectionTitle(app: MainActivity, text: String): TextView {
    val t = app.tv(text, 22f, Pal.TEXT, true)
    t.setPadding(app.dp(20), app.dp(26), app.dp(20), app.dp(10))
    return t
}

fun topBar(app: MainActivity): LinearLayout {
    val row = LinearLayout(app)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(app.dp(16), app.dp(8), app.dp(16), 0)
    val logo = app.tv("ye", 17f, Color.WHITE, true)
    logo.gravity = Gravity.CENTER
    logo.background = app.rounded(Pal.BG, 24f, Pal.STROKE, 1)
    row.addView(logo, LinearLayout.LayoutParams(app.dp(48), app.dp(48)))
    row.addView(View(app), LinearLayout.LayoutParams(0, 1, 1f))
    val orb = View(app)
    orb.background = app.art("orb", 24f)
    orb.pressable { app.settings() }
    row.addView(orb, LinearLayout.LayoutParams(app.dp(40), app.dp(40)))
    return row
}

/* ---------- generic song list (era, tab, favorites, recents, downloads) ---------- */

typealias Source = (cancel: () -> Boolean, emit: (List<Song>) -> Unit) -> Unit

class SongListPage(
    private val app: MainActivity,
    private val title: String,
    private val subtitle: String,
    private val seed: String,
    private val playlist: String? = null,
    private val source: Source
) {
    val view = ListView(app)
    private val adapter = SongAdapter(app, false)
    private val all = ArrayList<Song>()
    private val seen = HashSet<String>()
    private val count = app.tv("Loading…", 13f, Pal.SUB)
    private var dead = false

    init {
        styleList(view, app)
        view.addHeaderView(header(), null, false)
        view.adapter = adapter
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { dead = true }
        })
        adapter.playlist = playlist
        adapter.onChanged = { reload() }
        load()
    }

    private fun load() {
        count.text = "Loading…"
        app.bg {
            try {
                source({ dead }) { batch -> app.ui { add(batch) } }
                app.ui { finish(null) }
            } catch (e: Exception) {
                app.ui { finish(e.message ?: "Network error") }
            }
        }
    }

    private fun reload() {
        all.clear()
        seen.clear()
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        load()
    }

    private fun add(batch: List<Song>) {
        for (s in batch) if (seen.add(s.key)) all.add(s)
        all.sortBy { orderOf(it.key) }
        adapter.items = ArrayList(all)
        adapter.notifyDataSetChanged()
        count.text = "${all.size} songs · loading…"
    }

    private fun finish(error: String?) {
        count.text = if (error != null) "${all.size} songs · couldn't load everything: $error" else "${all.size} songs"
    }

    private fun header(): View {
        val col = LinearLayout(app)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(app.dp(20), app.dp(8), app.dp(20), app.dp(12))
        val back = app.icon(R.drawable.ic_back, Pal.TEXT, 10)
        back.pressable { app.pop() }
        col.addView(back, LinearLayout.LayoutParams(app.dp(44), app.dp(44)).apply { gravity = Gravity.START })
        val size = minOf(app.resources.displayMetrics.widthPixels - app.dp(120), app.dp(240))
        val art = FrameLayout(app)
        art.background = app.art(seed, 14f)
        art.elevation = app.dp(8).toFloat()
        val label = app.tv(title, 22f, Color.WHITE, true)
        label.gravity = Gravity.CENTER
        label.alpha = 0.9f
        label.setPadding(app.dp(16), 0, app.dp(16), 0)
        art.addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
        art.loadCover(seed, 14f) { has -> label.visibility = if (has) View.GONE else View.VISIBLE }
        col.addView(art, LinearLayout.LayoutParams(size, size).apply { topMargin = app.dp(4) })
        val t = app.tv(title, 24f, Pal.TEXT, true)
        t.gravity = Gravity.CENTER
        col.addView(t, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = app.dp(16) })
        if (subtitle.isNotEmpty()) {
            val st = app.tv(subtitle, 14f, Pal.SUB)
            st.gravity = Gravity.CENTER
            col.addView(st, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = app.dp(2) })
        }
        val btns = LinearLayout(app)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(pillButton(app, "Play", R.drawable.ic_play, true) {
            val l = adapter.items.filter { it.canPlay }
            if (l.isEmpty()) app.toast("Nothing playable yet") else app.playQueue(l, 0, title, false)
        }, LinearLayout.LayoutParams(0, app.dp(48), 1f))
        btns.addView(pillButton(app, "Shuffle", R.drawable.ic_shuffle, false) {
            val l = adapter.items.filter { it.canPlay }.shuffled()
            if (l.isEmpty()) app.toast("Nothing playable yet") else app.playQueue(l, 0, title, false)
        }, LinearLayout.LayoutParams(0, app.dp(48), 1f).apply { marginStart = app.dp(12) })
        col.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = app.dp(18) })
        count.gravity = Gravity.START
        col.addView(count, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = app.dp(14) })
        return col
    }
}

/* ---------- cards ---------- */

fun songCard(app: MainActivity, s: Song, widthDp: Int, onTap: () -> Unit): LinearLayout {
    val col = LinearLayout(app)
    col.orientation = LinearLayout.VERTICAL
    val art = FrameLayout(app)
    art.background = app.art(s.era, 12f)
    val n = app.icon(R.drawable.ic_note, Color.argb(120, 255, 255, 255), 40)
    art.addView(n, FrameLayout.LayoutParams(MATCH, MATCH))
    art.loadCover(s.era, 12f) { has -> n.visibility = if (has) View.GONE else View.VISIBLE }
    col.addView(art, LinearLayout.LayoutParams(app.dp(widthDp), app.dp(widthDp)))
    val t = app.tv(s.name, 14f, Pal.TEXT, false, 1)
    t.setPadding(0, app.dp(8), 0, 0)
    col.addView(t, LinearLayout.LayoutParams(app.dp(widthDp), WRAP))
    col.addView(app.tv(s.subtitle, 12f, Pal.SUB, false, 1), LinearLayout.LayoutParams(app.dp(widthDp), WRAP))
    col.pressable(onTap)
    return col
}

fun eraCard(app: MainActivity, e: Era, widthPx: Int, onTap: () -> Unit): LinearLayout {
    val col = LinearLayout(app)
    col.orientation = LinearLayout.VERTICAL
    val art = FrameLayout(app)
    art.background = app.art(e.name, 14f)
    val label = app.tv(e.name, 17f, Color.WHITE, true, 4)
    label.gravity = Gravity.BOTTOM or Gravity.START
    label.setPadding(app.dp(12), app.dp(12), app.dp(12), app.dp(12))
    art.addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
    art.loadCover(e.name, 14f) { has -> label.visibility = if (has) View.GONE else View.VISIBLE }
    col.addView(art, LinearLayout.LayoutParams(widthPx, widthPx))
    val sub = app.tv(if (e.count >= 0) e.years + " · " + e.count + " songs" else e.years, 12f, Pal.SUB, false, 1)
    sub.setPadding(0, app.dp(6), 0, 0)
    col.addView(sub, LinearLayout.LayoutParams(widthPx, WRAP))
    col.pressable(onTap)
    return col
}

fun shelf(app: MainActivity, cards: List<View>): HorizontalScrollView {
    val hs = HorizontalScrollView(app)
    hs.isHorizontalScrollBarEnabled = false
    val row = LinearLayout(app)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(app.dp(20), 0, app.dp(8), 0)
    for (c in cards) row.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = app.dp(12) })
    hs.addView(row)
    return hs
}

fun playlistCard(app: MainActivity, name: String, sub: String, isNew: Boolean, onTap: () -> Unit): LinearLayout {
    val col = LinearLayout(app)
    col.orientation = LinearLayout.VERTICAL
    val art = FrameLayout(app)
    if (isNew) {
        art.background = app.rounded(Pal.SURFACE, 12f)
        art.addView(app.icon(R.drawable.ic_add, Pal.ACCENT, 48), FrameLayout.LayoutParams(MATCH, MATCH))
    } else {
        art.background = app.art(name, 12f)
        val l = app.tv(name, 17f, Color.WHITE, true, 4)
        l.gravity = Gravity.BOTTOM or Gravity.START
        l.setPadding(app.dp(12), app.dp(12), app.dp(12), app.dp(12))
        art.addView(l, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    col.addView(art, LinearLayout.LayoutParams(app.dp(150), app.dp(150)))
    val t = app.tv(name, 14f, Pal.TEXT, false, 1)
    t.setPadding(0, app.dp(8), 0, 0)
    col.addView(t, LinearLayout.LayoutParams(app.dp(150), WRAP))
    col.addView(app.tv(sub, 12f, Pal.SUB, false, 1), LinearLayout.LayoutParams(app.dp(150), WRAP))
    col.pressable(onTap)
    return col
}

fun tile(app: MainActivity, iconRes: Int, label: String, trailing: String = "", onTap: () -> Unit): LinearLayout {
    val t = LinearLayout(app)
    t.orientation = LinearLayout.HORIZONTAL
    t.gravity = Gravity.CENTER_VERTICAL
    t.background = app.rounded(Pal.SURFACE, 18f)
    t.setPadding(app.dp(12), app.dp(12), app.dp(14), app.dp(12))
    val ic = app.icon(iconRes, Pal.ACCENT, 12)
    ic.background = app.rounded(Pal.ACCENT_DIM, 12f)
    t.addView(ic, LinearLayout.LayoutParams(app.dp(48), app.dp(48)))
    val l = app.tv(label, 16f, Pal.TEXT, false, 1)
    t.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(14) })
    if (trailing.isNotEmpty()) {
        t.addView(app.tv(trailing, 15f, Pal.SUB), LinearLayout.LayoutParams(WRAP, WRAP))
        t.addView(app.icon(R.drawable.ic_chevron, Pal.SUB), LinearLayout.LayoutParams(app.dp(22), app.dp(22)))
    }
    t.pressable(onTap)
    return t
}

/* ---------- Listen Now ---------- */

class HomePage(private val app: MainActivity) {
    val view = ScrollView(app)
    private val col = LinearLayout(app)
    private val recentsTitle = sectionTitle(app, "Recents")
    private val recentsBox = LinearLayout(app)
    private val shelves = LinearLayout(app)
    private val status = app.tv("Loading the tracker…", 14f, Pal.SUB)
    private var loaded = false

    init {
        view.isVerticalScrollBarEnabled = false
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, app.dp(230))
        view.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        col.addView(topBar(app))
        col.addView(largeTitle(app, "Listen Now"))

        val card = LinearLayout(app)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = app.rounded(Pal.SURFACE, 22f)
        card.setPadding(app.dp(14), app.dp(14), app.dp(14), app.dp(14))
        val circ = app.icon(R.drawable.ic_shuffle, Color.BLACK, 16)
        circ.background = app.rounded(Pal.ACCENT, 33f)
        card.addView(circ, LinearLayout.LayoutParams(app.dp(66), app.dp(66)))
        val txt = LinearLayout(app)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(app.tv("Shuffle the whole tracker", 19f, Pal.TEXT, true, 1))
        txt.addView(app.tv("Endless random playable tracks", 15f, Pal.SUB, false, 1))
        card.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(16) })
        card.pressable { app.startShuffle() }
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(app.dp(16), app.dp(8), app.dp(16), 0) })

        col.addView(recentsTitle)
        recentsBox.orientation = LinearLayout.VERTICAL
        col.addView(recentsBox)
        shelves.orientation = LinearLayout.VERTICAL
        col.addView(shelves)
        status.setPadding(app.dp(20), app.dp(20), app.dp(20), 0)
        col.addView(status)
        refresh()
    }

    fun refresh() {
        val r = Store.recent().take(4)
        recentsTitle.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
        recentsBox.removeAllViews()
        for (s in r) {
            val h = RowHolder(app)
            h.bind(s, true) { app.tap(Store.recent(), s) }
            recentsBox.addView(h.root)
        }
        if (!loaded) load()
    }

    private fun load() {
        loaded = true
        status.text = "Loading the tracker…"
        app.bg {
            try {
                val leaks = Tracker.recent(15)
                val best = Tracker.search(null, null, null, null, null, listOf("best"), 20).songs
                val eras = Tracker.eras()
                val rel = try { Tracker.random(15, "released") } catch (_: Exception) { emptyList() }
                app.ui {
                    status.visibility = View.GONE
                    app.hideSplash()
                    if (leaks.isNotEmpty()) addSongShelf("Latest Leaks", leaks)
                    if (best.isNotEmpty()) addSongShelf("Best Of", best)
                    if (rel.isNotEmpty()) addSongShelf("Released Picks", rel)
                    if (eras.isNotEmpty()) {
                        shelves.addView(sectionTitle(app, "Browse Eras"))
                        val cards = eras.map { e -> eraCard(app, e, app.dp(150)) { app.openEra(e) } }
                        shelves.addView(shelf(app, cards))
                    }
                }
            } catch (e: Exception) {
                loaded = false
                app.ui {
                    app.hideSplash()
                    status.text = "Couldn't reach yetracker.cc (${e.message}). Switch tabs to retry."
                }
            }
        }
    }

    private fun addSongShelf(title: String, songs: List<Song>) {
        shelves.addView(sectionTitle(app, title))
        val cards = songs.mapIndexed { i, s -> songCard(app, s, 150) { app.tap(songs, s) } }
        shelves.addView(shelf(app, cards))
    }
}

/* ---------- Eras ---------- */

class ErasPage(private val app: MainActivity) {
    val view = ScrollView(app)
    private val col = LinearLayout(app)
    private val grid = LinearLayout(app)
    private val status = app.tv("Loading eras…", 14f, Pal.SUB)
    private val chipViews = ArrayList<TextView>()
    private var eras: List<Era> = emptyList()
    private var released = false
    private var loaded = false

    init {
        view.isVerticalScrollBarEnabled = false
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, app.dp(8), 0, app.dp(230))
        view.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        col.addView(largeTitle(app, "Eras"))

        val chips = LinearLayout(app)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(app.dp(16), 0, app.dp(16), app.dp(14))
        listOf("Unreleased", "Released").forEachIndexed { i, label ->
            val c = app.tv(label, 15f, Pal.TEXT, true)
            c.gravity = Gravity.CENTER
            c.setPadding(app.dp(18), app.dp(9), app.dp(18), app.dp(9))
            c.pressable { released = i == 1; styleChips(); render() }
            chipViews.add(c)
            chips.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = app.dp(10) })
        }
        col.addView(chips)
        styleChips()

        status.setPadding(app.dp(20), 0, app.dp(20), 0)
        col.addView(status)
        grid.orientation = LinearLayout.VERTICAL
        col.addView(grid)
        refresh()
    }

    private fun styleChips() {
        chipViews.forEachIndexed { i, c ->
            val sel = (i == 1) == released
            c.background = app.rounded(if (sel) Pal.ACCENT else Pal.SURFACE, 20f)
            c.setTextColor(if (sel) Color.BLACK else Pal.TEXT)
        }
    }

    fun refresh() {
        if (loaded) return
        loaded = true
        app.bg {
            try {
                val list = Tracker.eras()
                app.ui { eras = list; render() }
            } catch (e: Exception) {
                loaded = false
                app.ui { status.text = "Couldn't reach yetracker.cc (${e.message})" }
            }
        }
    }

    private fun render() {
        status.visibility = View.GONE
        grid.removeAllViews()
        val w = (app.resources.displayMetrics.widthPixels - app.dp(16 * 3)) / 2
        val list = if (released) eras.map { it.copy(count = -1) } else eras
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(app)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(app.dp(16), 0, app.dp(16), app.dp(16))
            for (j in 0..1) {
                if (i + j >= list.size) break
                val e = list[i + j]
                row.addView(eraCard(app, e, w) { app.openEra(e, released) }, LinearLayout.LayoutParams(w, WRAP).apply {
                    if (j == 1) marginStart = app.dp(16)
                })
            }
            grid.addView(row)
            i += 2
        }
    }
}

/* ---------- Library ---------- */

class LibraryPage(private val app: MainActivity) {
    val view = ScrollView(app)
    private val col = LinearLayout(app)
    private val wrappedMinutes = app.tv("", 15f, Pal.SUB)
    private val plBox = LinearLayout(app)

    init {
        view.isVerticalScrollBarEnabled = false
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, app.dp(230))
        view.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        col.addView(topBar(app))
        col.addView(largeTitle(app, "Library"))

        val wrapped = tile(app, R.drawable.ic_stats, "Your Wrapped", " ") { app.push(WrappedPage(app).view) }
        // replace trailing text: rebuild minutes label inside tile
        col.addView(wrapped, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(app.dp(16), 0, app.dp(16), 0) })
        wrapped.removeViewAt(2)
        wrapped.removeViewAt(2)
        wrapped.addView(wrappedMinutes, LinearLayout.LayoutParams(WRAP, WRAP))
        wrapped.addView(app.icon(R.drawable.ic_chevron, Pal.SUB), LinearLayout.LayoutParams(app.dp(22), app.dp(22)))

        col.addView(sectionTitle(app, "On Device"))
        col.addView(pair(
            tile(app, R.drawable.ic_download, "Downloads") { app.openDownloads() },
            tile(app, R.drawable.ic_heart_outline, "Favorites") {
                app.push(SongListPage(app, "Favorites", "Songs you loved", "favorites") { _, emit -> emit(Store.favorites()) }.view)
            }
        ))
        col.addView(sectionTitle(app, "Local playlists"))
        plBox.orientation = LinearLayout.VERTICAL
        col.addView(plBox)
        col.addView(sectionTitle(app, "History"))
        col.addView(pair(
            tile(app, R.drawable.ic_queue, "Recently Played") {
                app.push(SongListPage(app, "Recently Played", "Your last 30 plays", "recent") { _, emit -> emit(Store.recent()) }.view)
            },
            tile(app, R.drawable.ic_note, "Released") { app.openTab("released", "Released") }
        ))
        col.addView(sectionTitle(app, "Browse the Tracker"))
        val tabs = listOf(
            Triple("best", "Best Of", R.drawable.ic_heart),
            Triple("special", "Special", R.drawable.ic_stats),
            Triple("grails", "Grails", R.drawable.ic_stats),
            Triple("worst", "Worst Of", R.drawable.ic_note),
            Triple("recent", "Recent", R.drawable.ic_queue),
            Triple("shared", "Shared", R.drawable.ic_library),
            Triple("ssc", "SSC", R.drawable.ic_library),
            Triple("fakes", "Fakes", R.drawable.ic_note),
            Triple("stems", "Stems", R.drawable.ic_grid),
            Triple("copies", "Copies", R.drawable.ic_library),
            Triple("misc", "Misc", R.drawable.ic_grid)
        )
        var i = 0
        while (i < tabs.size) {
            val a = tabs[i]
            val b = tabs.getOrNull(i + 1)
            col.addView(pair(
                tile(app, a.third, a.second) { app.openTab(a.first, a.second) },
                if (b != null) tile(app, b.third, b.second) { app.openTab(b.first, b.second) } else null
            ))
            i += 2
        }
        refresh()
    }

    fun refresh() {
        val m = Store.ms / 60000
        wrappedMinutes.text = if (m == 1L) "1 minute" else "$m minutes"
        plBox.removeAllViews()
        val cards = ArrayList<View>()
        cards.add(playlistCard(app, "New playlist", "Saved to this device", true) { app.newPlaylist { refresh() } })
        for (n in Store.playlistNames()) {
            val c = playlistCard(app, n, Store.playlist(n).size.toString() + " songs", false) { app.openPlaylist(n) }
            c.setOnLongClickListener { app.deletePlaylistDialog(n) { refresh() }; true }
            cards.add(c)
        }
        plBox.addView(shelf(app, cards))
    }

    private fun pair(a: View, b: View?): LinearLayout {
        val row = LinearLayout(app)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(app.dp(16), 0, app.dp(16), app.dp(12))
        row.addView(a, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(b ?: View(app), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = app.dp(12) })
        return row
    }
}

/* ---------- Search ---------- */

class SearchPage(private val app: MainActivity) {
    val view = LinearLayout(app)
    private val adapter = SongAdapter(app, true)
    private val list = ListView(app)
    private val input = EditText(app)
    private val status = app.tv("Search songs, eras, producers, features…", 14f, Pal.SUB)
    private val chipViews = ArrayList<TextView>()
    private var chip = 0
    private var token = 0
    private val debounce = Runnable { doSearch() }

    private val chips = listOf("All", "OG File", "Full", "Lossless", "⭐ Best", "🏆 Grails", "Released")

    init {
        view.orientation = LinearLayout.VERTICAL
        view.addView(largeTitle(app, "Search"))

        input.hint = "Songs, eras, producers"
        input.setHintTextColor(Pal.SUB)
        input.setTextColor(Pal.TEXT)
        input.textSize = 17f
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.isSingleLine = true
        input.background = app.rounded(Pal.SURFACE, 26f)
        input.setPadding(app.dp(20), 0, app.dp(20), 0)
        input.doAfterTextChanged {
            app.main.removeCallbacks(debounce)
            app.main.postDelayed(debounce, 450)
        }
        input.setOnEditorActionListener { _, _, _ -> doSearch(); false }
        view.addView(input, LinearLayout.LayoutParams(MATCH, app.dp(52)).apply { setMargins(app.dp(16), 0, app.dp(16), app.dp(12)) })

        val hs = HorizontalScrollView(app)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(app)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(app.dp(16), 0, app.dp(8), 0)
        chips.forEachIndexed { i, label ->
            val c = app.tv(label, 14f, Pal.TEXT)
            c.setPadding(app.dp(16), app.dp(8), app.dp(16), app.dp(8))
            c.pressable { chip = i; styleChips(); doSearch() }
            chipViews.add(c)
            row.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = app.dp(8) })
        }
        hs.addView(row)
        view.addView(hs)
        styleChips()

        status.setPadding(app.dp(20), app.dp(14), app.dp(20), app.dp(4))
        view.addView(status)

        styleList(list, app)
        list.adapter = adapter
        view.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun styleChips() {
        chipViews.forEachIndexed { i, c ->
            val sel = i == chip
            c.background = app.rounded(if (sel) Pal.ACCENT else Pal.SURFACE, 18f)
            c.setTextColor(if (sel) Color.BLACK else Pal.TEXT)
        }
    }

    private fun doSearch() {
        val q = input.text.toString().trim()
        val my = ++token
        if (q.isEmpty() && chip == 0) {
            adapter.items = emptyList()
            adapter.notifyDataSetChanged()
            status.text = "Search songs, eras, producers, features…"
            return
        }
        status.text = "Searching…"
        val c = chip
        app.bg {
            try {
                val avail = if (c == 1) listOf("OG File") else if (c == 2) listOf("Full") else null
                val qual = if (c == 3) listOf("Lossless") else null
                val marked = if (c == 4) listOf("best") else if (c == 5) listOf("grail") else null
                val page = Tracker.search(if (c == 6) "released" else null, q, null, avail, qual, marked, 50)
                app.ui {
                    if (my == token) {
                        adapter.items = page.songs
                        adapter.notifyDataSetChanged()
                        status.text = if (page.total > page.songs.size) "Top ${page.songs.size} of ${page.total} results" else "${page.total} results"
                    }
                }
            } catch (e: Exception) {
                app.ui { if (my == token) status.text = "Search failed: ${e.message}" }
            }
        }
    }
}

/* ---------- Wrapped ---------- */

class WrappedPage(private val app: MainActivity) {
    val view = ScrollView(app)

    private fun statCard(big: String, small: String): LinearLayout {
        val c = LinearLayout(app)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.background = app.rounded(Pal.SURFACE, 18f)
        c.setPadding(app.dp(8), app.dp(16), app.dp(8), app.dp(16))
        c.addView(app.tv(big, 26f, Pal.ACCENT, true))
        c.addView(app.tv(small, 13f, Pal.SUB))
        return c
    }

    init {
        view.isVerticalScrollBarEnabled = false
        val col = LinearLayout(app)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, app.dp(8), 0, app.dp(230))
        view.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        val back = app.icon(R.drawable.ic_back, Pal.TEXT, 10)
        back.pressable { app.pop() }
        col.addView(back, LinearLayout.LayoutParams(app.dp(44), app.dp(44)).apply { marginStart = app.dp(12) })
        col.addView(largeTitle(app, "Your Wrapped"))

        val mins = Store.ms / 60000
        val row = LinearLayout(app)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(app.dp(16), 0, app.dp(16), 0)
        row.addView(statCard(mins.toString(), if (mins == 1L) "minute" else "minutes"), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(statCard(Store.totalPlays().toString(), "plays"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(10) })
        row.addView(statCard(Store.uniqueSongs().toString(), "songs"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(10) })
        col.addView(row)

        val top = Store.topSongs(25)
        if (top.isEmpty()) {
            val t = app.tv("Play some songs and your most played tracks will show up here. A play counts after 20 seconds.", 15f, Pal.SUB)
            t.setPadding(app.dp(20), app.dp(24), app.dp(20), 0)
            col.addView(t)
        } else {
            col.addView(sectionTitle(app, "Top Songs"))
            val songs = top.map { it.song }
            top.forEachIndexed { i, st ->
                val line = LinearLayout(app)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.CENTER_VERTICAL
                val rank = app.tv((i + 1).toString(), 18f, if (i < 3) Pal.ACCENT else Pal.SUB, true)
                rank.gravity = Gravity.CENTER
                line.addView(rank, LinearLayout.LayoutParams(app.dp(40), WRAP))
                val h = RowHolder(app)
                h.bind(st.song, true) { app.tap(songs, st.song) }
                h.root.setPadding(0, app.dp(7), app.dp(4), app.dp(7))
                val min = st.ms / 60000
                h.setSub(st.plays.toString() + (if (st.plays == 1) " play" else " plays") + " · " + min + " min")
                line.addView(h.root, LinearLayout.LayoutParams(0, WRAP, 1f))
                col.addView(line)
            }
            val eras = Store.topEras(6)
            if (eras.isNotEmpty()) {
                col.addView(sectionTitle(app, "Top Eras"))
                eras.forEach { (era, ms) ->
                    val line = LinearLayout(app)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.gravity = Gravity.CENTER_VERTICAL
                    line.setPadding(app.dp(20), app.dp(7), app.dp(20), app.dp(7))
                    val art = View(app)
                    art.background = app.art(era, 8f)
                    art.loadCover(era, 8f)
                    line.addView(art, LinearLayout.LayoutParams(app.dp(52), app.dp(52)))
                    val tx = LinearLayout(app)
                    tx.orientation = LinearLayout.VERTICAL
                    tx.addView(app.tv(era, 16f, Pal.TEXT, false, 1))
                    tx.addView(app.tv((ms / 60000).toString() + " min listened", 13f, Pal.SUB))
                    line.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = app.dp(14) })
                    col.addView(line)
                }
            }
        }
    }
}
