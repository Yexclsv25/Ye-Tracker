package cc.yetracker.mobile

import android.Manifest
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.content.ClipboardManager
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.content.res.ColorStateList
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

private const val MATCH_ = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_ = ViewGroup.LayoutParams.WRAP_CONTENT

private val EQ_NAMES = listOf("Flat", "Bass boost", "Bass boost+", "Treble boost", "Vocal", "Loudness")
private val GLASS_NAMES = listOf("Off (flat)", "Static (lightest)", "Live · light", "Live · full")
private val REFRESH_NAMES = listOf("Smooth", "Balanced", "When scrolling stops")
private val COVER_NAMES = listOf("Always", "Wi-Fi only", "Off")

private fun formatBytes(b: Long): String = when {
    b >= 1_073_741_824L -> String.format("%.1f GB", b / 1073741824.0)
    b >= 1_048_576L -> (b / 1_048_576L).toString() + " MB"
    b >= 1024L -> (b / 1024L).toString() + " KB"
    else -> b.toString() + " B"
}

/* ---------- full-screen "Now Playing" ---------- */

class PlayerOverlay(private val app: MainActivity) {
    val view = FrameLayout(app)
    private val col = LinearLayout(app)
    private val sourceLabel = app.tv("", 13f, Color.WHITE, true, 1)
    private val artBox = FrameLayout(app)
    private val artLabel = app.tv("", 26f, Color.WHITE, true)
    private val title = app.tv("", 22f, Pal.TEXT, true, 1)
    private val artist = app.tv("", 20f, Pal.SUB, false, 1)
    private val heart = app.icon(R.drawable.ic_heart_outline, Pal.TEXT, 11)
    private val info = app.tv("", 16f, Pal.SUB, false, 1)
    private val seek = SeekBar(app)
    private val elapsed = app.tv("0:00", 13f, Pal.SUB)
    private val remaining = app.tv("-0:00", 13f, Pal.SUB)
    private val quality = app.tv("", 14f, Pal.SUB, true)
    private val qualityIcon = app.icon(R.drawable.ic_headset, Pal.SUB)
    private val playBtn = app.icon(R.drawable.ic_play, Pal.TEXT, 10)
    private val shuffleBtn = app.icon(R.drawable.ic_shuffle, Pal.SUB, 10)
    private val repeatBtn = app.icon(R.drawable.ic_repeat, Pal.SUB, 10)
    private var dragging = false

    init {
        view.setBackgroundColor(Pal.BG)
        view.isClickable = true
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(app.dp(28), app.dp(8), app.dp(28), app.dp(16))
        view.addView(col, FrameLayout.LayoutParams(MATCH_, MATCH_))

        val down = app.icon(R.drawable.ic_down, Pal.SUB, 6)
        down.pressable { app.closePlayer() }
        col.addView(down, LinearLayout.LayoutParams(app.dp(44), app.dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sourceLabel.gravity = Gravity.CENTER
        col.addView(sourceLabel, LinearLayout.LayoutParams(MATCH_, WRAP_))

        col.addView(View(app), LinearLayout.LayoutParams(1, 0, 1f))
        val w = app.resources.displayMetrics.widthPixels - app.dp(56)
        val sz = minOf(w, (app.resources.displayMetrics.heightPixels * 0.40f).toInt())
        artBox.elevation = app.dp(12).toFloat()
        artLabel.gravity = Gravity.CENTER
        artLabel.alpha = 0.85f
        artLabel.setPadding(app.dp(20), 0, app.dp(20), 0)
        artBox.addView(artLabel, FrameLayout.LayoutParams(MATCH_, MATCH_))
        col.addView(artBox, LinearLayout.LayoutParams(sz, sz).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(View(app), LinearLayout.LayoutParams(1, 0, 1f))

        val titleRow = LinearLayout(app)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val tcol = LinearLayout(app)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.addView(title)
        tcol.addView(artist)
        titleRow.addView(tcol, LinearLayout.LayoutParams(0, WRAP_, 1f))
        heart.background = app.glass(22f)
        heart.pressable { app.current?.let { Store.toggleFav(it); app.updatePlayUi() } }
        titleRow.addView(heart, LinearLayout.LayoutParams(app.dp(44), app.dp(44)))
        val more = app.icon(R.drawable.ic_more, Pal.TEXT, 10)
        more.background = app.glass(22f)
        more.pressable { app.current?.let { app.songMenu(it) } }
        titleRow.addView(more, LinearLayout.LayoutParams(app.dp(44), app.dp(44)).apply { marginStart = app.dp(10) })
        col.addView(titleRow, LinearLayout.LayoutParams(MATCH_, WRAP_))

        val infoRow = LinearLayout(app)
        infoRow.orientation = LinearLayout.HORIZONTAL
        infoRow.gravity = Gravity.CENTER_VERTICAL
        infoRow.addView(app.icon(R.drawable.ic_note, Pal.SUB), LinearLayout.LayoutParams(app.dp(22), app.dp(22)))
        infoRow.addView(info, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { marginStart = app.dp(8) })
        infoRow.addView(app.icon(R.drawable.ic_chevron, Pal.SUB), LinearLayout.LayoutParams(app.dp(22), app.dp(22)))
        infoRow.pressable { app.current?.let { app.details(it) } }
        col.addView(infoRow, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { topMargin = app.dp(18) })

        seek.max = 1000
        seek.progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.SOFT)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                app.seekFraction(sb.progress / 1000f)
            }
        })
        col.addView(seek, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { topMargin = app.dp(8) })

        val times = LinearLayout(app)
        times.orientation = LinearLayout.HORIZONTAL
        times.gravity = Gravity.CENTER_VERTICAL
        times.addView(elapsed, LinearLayout.LayoutParams(0, WRAP_, 1f))
        val q = LinearLayout(app)
        q.orientation = LinearLayout.HORIZONTAL
        q.gravity = Gravity.CENTER
        q.addView(qualityIcon, LinearLayout.LayoutParams(app.dp(16), app.dp(16)).apply { marginEnd = app.dp(4) })
        q.addView(quality)
        times.addView(q, LinearLayout.LayoutParams(WRAP_, WRAP_))
        remaining.gravity = Gravity.END
        times.addView(remaining, LinearLayout.LayoutParams(0, WRAP_, 1f))
        col.addView(times, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { topMargin = app.dp(2) })

        val controls = LinearLayout(app)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        val prev = app.icon(R.drawable.ic_prev, Pal.TEXT, 22)
        prev.pressable { app.prev() }
        val next = app.icon(R.drawable.ic_next, Pal.TEXT, 22)
        next.pressable { app.next() }
        playBtn.pressable { app.togglePlay() }
        controls.addView(prev, LinearLayout.LayoutParams(0, app.dp(84), 1f))
        controls.addView(playBtn, LinearLayout.LayoutParams(0, app.dp(84), 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, app.dp(84), 1f))
        col.addView(controls, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { topMargin = app.dp(12) })

        val foot = LinearLayout(app)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_VERTICAL
        val notes = app.icon(R.drawable.ic_chat, Pal.SUB, 6)
        notes.pressable { app.current?.let { app.details(it) } }
        foot.addView(notes, LinearLayout.LayoutParams(0, app.dp(44), 1f))
        shuffleBtn.pressable { app.toggleShuffle() }
        foot.addView(shuffleBtn, LinearLayout.LayoutParams(0, app.dp(44), 1f))
        repeatBtn.pressable { app.cycleRepeat() }
        foot.addView(repeatBtn, LinearLayout.LayoutParams(0, app.dp(44), 1f))
        val queueBtn = app.icon(R.drawable.ic_queue, Pal.SUB, 6)
        queueBtn.pressable { app.queueDialog() }
        foot.addView(queueBtn, LinearLayout.LayoutParams(0, app.dp(44), 1f))
        col.addView(foot, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { topMargin = app.dp(8) })
    }

    fun setInsets(top: Int, bottom: Int) {
        col.setPadding(app.dp(28), top + app.dp(8), app.dp(28), bottom + app.dp(16))
    }

    fun bind(s: Song?, playing: Boolean, loading: Boolean, source: String) {
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (s == null) return
        sourceLabel.text = if (source.isEmpty()) "" else "Playing $source"
        title.text = s.name
        artist.text = s.subtitle
        artBox.background = app.art(s.era, 14f)
        artLabel.text = s.era
        artBox.loadCover(s.era, 14f) { has -> artLabel.visibility = if (has) View.GONE else View.VISIBLE }
        ImageViewTint.set(shuffleBtn, if (app.shuffleOn) Pal.ACCENT else Pal.SUB)
        ImageViewTint.set(repeatBtn, if (app.repeatMode > 0) Pal.ACCENT else Pal.SUB)
        val (c1, _) = seedColors(s.era)
        val top = Color.rgb((Color.red(c1) * 0.42f).toInt(), (Color.green(c1) * 0.42f).toInt(), (Color.blue(c1) * 0.42f).toInt())
        view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, Pal.BG))
        heart.setImageResource(if (Store.isFav(s.key)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        ImageViewTint.set(heart, if (Store.isFav(s.key)) Pal.ACCENT else Pal.TEXT)
        info.text = if (loading) "Loading…" else (s.availability.ifEmpty { s.released.ifEmpty { "Details" } })
        val q = if (s.localUri != null) "Downloaded" else s.quality
        quality.text = q
        val show = if (q.isEmpty()) View.GONE else View.VISIBLE
        quality.visibility = show
        qualityIcon.visibility = show
    }

    fun progress(pos: Long, dur: Long) {
        if (dragging) return
        elapsed.text = fmt(pos)
        if (dur > 0) {
            seek.progress = ((pos.toFloat() / dur) * 1000f).toInt().coerceIn(0, 1000)
            remaining.text = "-" + fmt(dur - pos)
        } else {
            seek.progress = 0
            remaining.text = ""
        }
    }
}

object ImageViewTint {
    fun set(v: ImageView, color: Int) {
        androidx.core.widget.ImageViewCompat.setImageTintList(v, android.content.res.ColorStateList.valueOf(color))
    }
}

/* ---------- main activity ---------- */

class MainActivity : AppCompatActivity() {
    val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var mini: GlassLinear
    private lateinit var header: GlassLinear
    private lateinit var headerTitle: TextView
    val ambient = AmbientDrawable()
    private val tabLabels = ArrayList<TextView>()
    private var lastScrollAt = 0L
    private val glassIdle = Runnable { invalidateGlass(true) }
    private val glassViews = ArrayList<Glass>()
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private lateinit var miniArt: View
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var player: PlayerOverlay
    private val tabViews = arrayOfNulls<View>(4)
    private val tabButtons = ArrayList<View>()
    private val tabIcons = ArrayList<ImageView>()
    private var tab = 0
    private var playerOpen = false

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    var queue: List<Song> = emptyList()
    var qi = -1
    var current: Song? = null
    var source: String = ""
    val adapters = java.util.WeakHashMap<SongAdapter, Boolean>()
    var shuffleOn = false
    var repeatMode = 0 // 0 off, 1 all, 2 one
    private var origQueue: List<Song> = emptyList()
    private var hadDownloads = false
    private var lastPresence = ""
    private lateinit var splash: FrameLayout
    private lateinit var offline: FrameLayout
    private var offlineBypass = false
    private var sleepAt = 0L
    private var sleepEndOfSong = false
    private val sleepRun = Runnable {
        controller?.pause()
        sleepAt = 0L
        toast("Sleep timer: playback paused")
    }
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { ui { checkOnline() } }
        override fun onLost(network: Network) { ui { checkOnline() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { ui { checkOnline() } }
    }
    private var popping = false
    private var lastGlass = 0L
    private var songMs = 0L
    private var songCounted = false
    private var lastTick = 0
    private var resumePos = 0L
    private var radioEra: String? = null
    private var endless = false
    private var extending = false
    private var loading = false
    private var token = 0
    private var errorStreak = 0
    private var tickCount = 0

    fun bg(f: () -> Unit) {
        pool.execute {
            try { f() } catch (e: Throwable) { Log.e("YT", "bg task failed", e) }
        }
    }

    fun ui(f: () -> Unit) { main.post(Runnable { f() }) }

    fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        Store.init(this)
        Pal.setAccent(Store.accentIdx)
        Pal.setAmoled(Store.amoled)
        Bus.eqPreset = Store.eqPreset
        Bus.skipSilence = Store.skipSilence
        main.postDelayed({ showWhatsNew() }, 1200)

        root = FrameLayout(this)
        ambient.glow = Store.ambient
        root.background = ambient
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH_, MATCH_))

        val bottom = buildBottom()
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_, WRAP_, Gravity.BOTTOM))

        header = buildHeader()
        root.addView(header, FrameLayout.LayoutParams(MATCH_, dp(62), Gravity.TOP).apply { setMargins(dp(12), 0, dp(12), 0) })
        glassViews.forEach { it.source = content; it.backdrop = root }

        player = PlayerOverlay(this)
        player.view.visibility = View.GONE
        root.addView(player.view, FrameLayout.LayoutParams(MATCH_, MATCH_))
        splash = buildSplash()
        root.addView(splash, FrameLayout.LayoutParams(MATCH_, MATCH_))
        main.postDelayed({ hideSplash() }, 5000)
        offline = buildOffline()
        offline.visibility = View.GONE
        root.addView(offline, FrameLayout.LayoutParams(MATCH_, MATCH_))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(0, b.top, 0, 0)
            bottom.setPadding(0, 0, 0, b.bottom)
            (header.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dp(6)
            header.requestLayout()
            player.setInsets(b.top, b.bottom)
            ins
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playerOpen -> closePlayer()
                    content.childCount > 1 -> pop()
                    tab != 0 -> selectTab(0)
                    else -> finish()
                }
            }
        })

        Bus.onNext = { next() }
        Bus.onPrev = { prev() }
        Discord.init(this)
        Discord.onReady = { lastPresence = ""; updatePlayUi() }
        main.post(discordTicker)
        Bus.onFav = { current?.let { Store.toggleFav(it); updatePlayUi() } }
        Bus.onShuffle = { toggleShuffle() }
        content.viewTreeObserver.addOnScrollChangedListener {
            lastScrollAt = SystemClock.uptimeMillis()
            if (Store.glassRefresh == 2) {
                main.removeCallbacks(glassIdle)
                main.postDelayed(glassIdle, 160)
            } else {
                invalidateGlass()
            }
        }
        selectTab(0)
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        } catch (_: Exception) {
        }
        checkOnline()
        connect()
        handleLink(intent)
        handleShortcut(intent)
        if (System.currentTimeMillis() - Store.lastUpdateCheck > 12 * 3600 * 1000L) {
            Store.lastUpdateCheck = System.currentTimeMillis()
            checkUpdate(false)
        }
        main.post(ticker)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        current?.let { Store.setLast(it, controller?.currentPosition ?: 0L) }
        Store.flush()
    }

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb)
        } catch (_: Exception) {
        }
        main.removeCallbacks(sleepRun)
        Bus.onNext = null
        Bus.onPrev = null
        Bus.onFav = null
        Bus.onShuffle = null
        main.removeCallbacks(ticker)
        main.removeCallbacks(discordTicker)
        controller?.release()
        super.onDestroy()
    }

    /* ----- offline ----- */

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun checkOnline() {
        if (!::offline.isInitialized) return
        if (isOnline()) {
            offlineBypass = false
            if (offline.visibility == View.VISIBLE) {
                offline.visibility = View.GONE
                (tabViews[tab]?.tag as? Runnable)?.run()
            }
        } else if (!offlineBypass && controller?.isPlaying != true && offline.visibility != View.VISIBLE) {
            hideSplash()
            offline.alpha = 0f
            offline.visibility = View.VISIBLE
            offline.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun buildOffline(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Pal.BG)
        f.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(32), 0, dp(32), 0)
        val ic = icon(R.drawable.ic_wifi_off, Pal.ACCENT, 22)
        ic.background = rounded(Pal.ACCENT_DIM, 36f)
        col.addView(ic, LinearLayout.LayoutParams(dp(96), dp(96)))
        val t = tv("You're offline", 26f, Pal.TEXT, true)
        col.addView(t, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { topMargin = dp(22) })
        val sub = tv("Ye Tracker needs an internet connection to browse and stream songs. Connect to Wi-Fi or mobile data and try again.", 15f, Pal.SUB)
        sub.gravity = Gravity.CENTER
        col.addView(sub, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { topMargin = dp(10) })
        col.addView(pillButton(this, "Try again", R.drawable.ic_repeat, true) {
            if (isOnline()) checkOnline() else toast("Still offline")
        }, LinearLayout.LayoutParams(MATCH_, dp(50)).apply { topMargin = dp(28) })
        col.addView(pillButton(this, "Play my downloads", R.drawable.ic_download, false) {
            offlineBypass = true
            offline.visibility = View.GONE
            selectTab(2)
            openDownloads()
        }, LinearLayout.LayoutParams(MATCH_, dp(50)).apply { topMargin = dp(12) })
        f.addView(col, FrameLayout.LayoutParams(MATCH_, WRAP_, Gravity.CENTER))
        return f
    }

    /* ----- splash ----- */

    private fun buildSplash(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Pal.BG)
        f.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val logo = icon(R.drawable.ic_note, Color.WHITE, 22)
        logo.background = art("splash", 24f)
        col.addView(logo, LinearLayout.LayoutParams(dp(92), dp(92)))
        val name = tv("Ye Tracker", 26f, Pal.TEXT, true)
        col.addView(name, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { topMargin = dp(18) })
        val sub = tv("Loading the tracker…", 14f, Pal.SUB)
        col.addView(sub, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { topMargin = dp(6) })
        val pb = ProgressBar(this)
        pb.isIndeterminate = true
        pb.indeterminateTintList = ColorStateList.valueOf(Pal.ACCENT)
        col.addView(pb, LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(26) })
        f.addView(col, FrameLayout.LayoutParams(WRAP_, WRAP_, Gravity.CENTER))
        return f
    }

    fun hideSplash() {
        if (!::splash.isInitialized || splash.visibility != View.VISIBLE || splash.tag == "hiding") return
        splash.tag = "hiding"
        splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_player", false) && current != null && !playerOpen) openPlayer()
        handleLink(intent)
        handleShortcut(intent)
    }

    /** Opens a yetracker.cc song link (…/?tab=unreleased&era=…&id=e23%3A3) inside the app and plays it. */
    private fun handleLink(i: Intent?) {
        val data = i?.data ?: return
        val host = data.host ?: return
        if (!host.endsWith("yetracker.cc")) return
        i.data = null // handled; don't re-run if the activity is recreated
        val id = data.getQueryParameter("id")
        if (id.isNullOrEmpty()) return
        hideSplash()
        toast("Opening shared song…")
        bg {
            try {
                val e = Tracker.entry(id)
                val song = Song.fromEntry(e)
                ui {
                    if (song.key.isEmpty()) {
                        toast("Couldn't find that song")
                    } else {
                        if (song.canPlay) {
                            playQueue(listOf(song), 0, song.subtitle, false)
                            main.postDelayed({ if (!playerOpen) openPlayer() }, 350)
                        } else {
                            details(song)
                        }
                    }
                }
            } catch (e: Exception) {
                ui { toast("Couldn't open link: " + (e.message ?: "network error")) }
            }
        }
    }

    /* ----- layout ----- */

    private fun buildBottom(): LinearLayout {
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL

        mini = GlassLinear(this, 33f)
        glassViews.add(mini.glass)
        mini.orientation = LinearLayout.HORIZONTAL
        mini.gravity = Gravity.CENTER_VERTICAL
        mini.setPadding(dp(10), dp(8), dp(8), dp(8))
        miniArt = View(this)
        mini.addView(miniArt, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        miniTitle = tv("", 17f, Pal.TEXT, true, 1)
        miniSub = tv("", 14f, Pal.SUB, false, 1)
        texts.addView(miniTitle)
        texts.addView(miniSub)
        mini.addView(texts, LinearLayout.LayoutParams(0, WRAP_, 1f).apply { marginStart = dp(12) })
        miniPlay = icon(R.drawable.ic_play, Pal.TEXT, 8)
        miniPlay.pressable { togglePlay() }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        val miniNext = icon(R.drawable.ic_next, Pal.TEXT, 8)
        miniNext.pressable { next() }
        mini.addView(miniNext, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.isClickable = true
        var downX = 0f
        mini.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    v.animate().alpha(0.7f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().alpha(1f).setDuration(120).start()
                    val dx = e.rawX - downX
                    if (dx < -dp(70)) next() else if (dx > dp(70)) prev() else openPlayer()
                }
                MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(120).start()
            }
            true
        }
        mini.visibility = View.GONE
        bottom.addView(mini, LinearLayout.LayoutParams(MATCH_, dp(66)).apply { setMargins(dp(14), 0, dp(14), dp(10)) })

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pill = GlassLinear(this, 34f)
        glassViews.add(pill.glass)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(6), dp(6), dp(6), dp(6))
        val icons = intArrayOf(R.drawable.ic_home, R.drawable.ic_grid, R.drawable.ic_library)
        val names = arrayOf("Listen Now", "Eras", "Library")
        for (i in 0..2) {
            val f = LinearLayout(this)
            f.orientation = LinearLayout.VERTICAL
            f.gravity = Gravity.CENTER
            val ic = icon(icons[i], Pal.SUB)
            f.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            val lb = tv(names[i], 11f, Pal.SUB, true, 1)
            lb.gravity = Gravity.CENTER
            f.addView(lb, LinearLayout.LayoutParams(WRAP_, WRAP_).apply { topMargin = dp(2) })
            f.pressable { selectTab(i) }
            pill.addView(f, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            tabButtons.add(f)
            tabIcons.add(ic)
            tabLabels.add(lb)
        }
        row.addView(pill, LinearLayout.LayoutParams(0, dp(68), 1f))
        val sf = GlassFrame(this, 34f)
        glassViews.add(sf.glass)
        val sic = icon(R.drawable.ic_search, Pal.SUB)
        sf.addView(sic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        sf.pressable { selectTab(3) }
        row.addView(sf, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(10) })
        tabButtons.add(sf)
        tabIcons.add(sic)
        bottom.addView(row, LinearLayout.LayoutParams(MATCH_, WRAP_).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        return bottom
    }

    private fun buildTab(i: Int): View = when (i) {
        0 -> HomePage(this).also { it.view.tag = Runnable { it.refresh() } }.view
        1 -> ErasPage(this).also { it.view.tag = Runnable { it.refresh() } }.view
        2 -> LibraryPage(this).also { it.view.tag = Runnable { it.refresh() } }.view
        else -> SearchPage(this).view
    }

    private fun buildHeader(): GlassLinear {
        val h = GlassLinear(this, 31f)
        glassViews.add(h.glass)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(dp(22), 0, dp(9), 0)
        headerTitle = tv("Listen Now", 28f, Pal.TEXT, true, 1)
        h.addView(headerTitle, LinearLayout.LayoutParams(0, WRAP_, 1f))
        fun circle(res: Int, onClick: () -> Unit): View {
            val f = FrameLayout(this)
            f.background = glass(22f)
            f.addView(icon(res, Pal.TEXT), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            f.pressable(onClick)
            return f
        }
        h.addView(circle(R.drawable.ic_search) { selectTab(3) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        h.addView(circle(R.drawable.ic_settings) { settings() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        return h
    }

    private fun updateHeader() {
        header.visibility = if (content.childCount > 1) View.GONE else View.VISIBLE
    }

    fun selectTab(i: Int) {
        tab = i
        haptic()
        content.removeAllViews()
        val v = tabViews[i] ?: buildTab(i).also { tabViews[i] = it }
        content.addView(v, FrameLayout.LayoutParams(MATCH_, MATCH_))
        if (Store.animations) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(170).start()
        } else {
            v.alpha = 1f
        }
        headerTitle.text = arrayOf("Listen Now", "Eras", "Library", "Search")[i]
        updateHeader()
        styleTabs()
        (v.tag as? Runnable)?.run()
    }

    private fun styleTabs() {
        for (k in tabButtons.indices) {
            val sel = k == tab
            tabButtons[k].background = if (sel) glass(if (k == 3) 34f else 28f, Pal.ACCENT, 70) else null
            ImageViewTint.set(tabIcons[k], if (sel) Pal.ACCENT else Pal.SUB)
            tabLabels.getOrNull(k)?.setTextColor(if (sel) Pal.ACCENT else Pal.SUB)
        }
        invalidateGlass(true)
    }

    fun invalidateGlass(force: Boolean = false) {
        if (!force && Store.glassMode < 2) return
        val now = SystemClock.uptimeMillis()
        if (!force) {
            val gap = when (Store.glassRefresh) { 1 -> 110L; 2 -> Long.MAX_VALUE; else -> 40L }
            if (now - lastGlass < gap) return
        }
        lastGlass = now
        glassViews.forEach { it.v.invalidate() }
    }

    private fun applyGlass() {
        ambient.glow = Store.ambient
        ambient.invalidateSelf()
        GlassSurface.refreshAll()
        styleTabs()
    }

    private fun applySpeed() {
        val sp = Store.speed
        controller?.playbackParameters = PlaybackParameters(sp, if (Store.pitchSpeed) sp else 1f)
    }

    fun settings() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun row(l: String, a: () -> Unit) { labels.add(l); actions.add(a) }
        fun onOff(v: Boolean): String = if (v) "On" else "Off"
        row("Liquid Glass: " + GLASS_NAMES[Store.glassMode.coerceIn(0, 3)]) { glassModeDialog() }
        row("Glass cards & buttons: " + onOff(Store.glassAll)) { Store.glassAll = !Store.glassAll; applyGlass(); settings() }
        row("Glass update while scrolling: " + REFRESH_NAMES[Store.glassRefresh.coerceIn(0, 2)]) { glassRefreshDialog() }
        row("Ambient glow: " + onOff(Store.ambient)) { Store.ambient = !Store.ambient; applyGlass(); settings() }
        row("Animations: " + onOff(Store.animations)) { Store.animations = !Store.animations; settings() }
        row("Cover art: " + COVER_NAMES[Store.covers.coerceIn(0, 2)]) { coversDialog() }
        row("Playback speed: " + Store.speed + "x") { speedDialog() }
        row("Pitch follows speed: " + onOff(Store.pitchSpeed)) { Store.pitchSpeed = !Store.pitchSpeed; applySpeed(); settings() }
        row("Equalizer: " + EQ_NAMES[Store.eqPreset.coerceIn(0, EQ_NAMES.size - 1)]) { eqDialog() }
        row("Skip silence: " + onOff(Store.skipSilence)) {
            Store.skipSilence = !Store.skipSilence
            Bus.skipSilence = Store.skipSilence
            Bus.applyAudio?.invoke()
            settings()
        }
        row("Preload next track: " + onOff(Store.prefetch)) { Store.prefetch = !Store.prefetch; settings() }
        row("Sleep timer: " + sleepLabel()) { sleepDialog() }
        row("Accent color: " + Pal.ACCENT_NAMES[Store.accentIdx.coerceIn(0, Pal.ACCENT_NAMES.size - 1)]) { accentDialog() }
        row("Pure black background: " + onOff(Store.amoled) + " (next launch)") { Store.amoled = !Store.amoled; settings() }
        row("Haptic feedback: " + onOff(Store.haptics)) { Store.haptics = !Store.haptics; settings() }
        row("Discord status: " + discordLabel()) { discordMenu() }
        row("Downloads: " + formatBytes(Store.downloadsSize())) { storageDialog() }
        row("Back up my data (share)") { backupData() }
        row("Restore from clipboard") { restoreData() }
        row("Check for updates (v" + Updater.currentName(this) + ")") { checkUpdate(true) }
        row("About Ye Tracker") { aboutDialog() }
        row("Reset Wrapped stats") {
            MaterialAlertDialogBuilder(this).setTitle("Reset Wrapped?")
                .setMessage("This clears your listening minutes, play counts, streak and top songs.")
                .setPositiveButton("Reset") { _, _ -> Store.resetStats(); toast("Wrapped reset"); settings() }
                .setNegativeButton("Cancel") { _, _ -> settings() }.showGlass()
        }
        MaterialAlertDialogBuilder(this).setTitle("Settings")
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Done", null).showGlass()
    }

    fun haptic() {
        if (Store.haptics) window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun glassModeDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Liquid Glass")
            .setItems(GLASS_NAMES.toTypedArray()) { _, i ->
                Store.glassMode = i
                applyGlass()
                settings()
            }.showGlass()
    }

    private fun glassRefreshDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Glass update while scrolling")
            .setItems(REFRESH_NAMES.toTypedArray()) { _, i ->
                Store.glassRefresh = i
                invalidateGlass(true)
                settings()
            }.showGlass()
    }

    private fun coversDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Cover art")
            .setItems(COVER_NAMES.toTypedArray()) { _, i ->
                Store.covers = i
                settings()
            }.showGlass()
    }

    private fun eqDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Equalizer")
            .setItems(EQ_NAMES.toTypedArray()) { _, i ->
                Store.eqPreset = i
                Bus.eqPreset = i
                Bus.applyAudio?.invoke()
                settings()
            }.showGlass()
    }

    private fun storageDialog() {
        val n = Store.downloads().size
        MaterialAlertDialogBuilder(this).setTitle("Downloads")
            .setMessage(n.toString() + " songs · " + formatBytes(Store.downloadsSize()) +
                "\n\nSongs are also copied to Music/Ye Tracker. Clearing here only removes the in-app copies.")
            .setPositiveButton("Clear all downloads") { _, _ ->
                Store.clearDownloads()
                refreshAdapters()
                toast("Downloads cleared")
                settings()
            }
            .setNegativeButton("Back") { _, _ -> settings() }.showGlass()
    }

    private fun backupData() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "Ye Tracker backup")
        i.putExtra(Intent.EXTRA_TEXT, Store.exportJson())
        startActivity(Intent.createChooser(i, "Save your backup"))
    }

    private fun restoreData() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isEmpty()) { toast("Copy your backup text first, then try again"); return }
        MaterialAlertDialogBuilder(this).setTitle("Restore backup?")
            .setMessage("This replaces your favorites, playlists and Wrapped stats with the copied backup.")
            .setPositiveButton("Restore") { _, _ ->
                if (Store.importJson(text)) {
                    toast("Backup restored")
                    selectTab(tab)
                } else {
                    toast("That isn't a Ye Tracker backup")
                }
            }
            .setNegativeButton("Cancel", null).showGlass()
    }

    private fun handleShortcut(i: Intent?) {
        val a = i?.getStringExtra("action") ?: return
        i.removeExtra("action")
        when (a) {
            "shuffle" -> startShuffle()
            "search" -> selectTab(3)
            "downloads" -> { selectTab(2); openDownloads() }
        }
    }

    fun startRadio(era: String) {
        toast("Starting $era radio…")
        bg {
            try {
                val songs = Tracker.random(25, null, era).filter { it.playable }
                ui {
                    if (songs.isEmpty()) {
                        toast("No playable songs found for this era")
                    } else {
                        playQueue(songs, 0, "$era Radio", true)
                        radioEra = era
                    }
                }
            } catch (e: Exception) {
                ui { toast("Couldn't start radio: " + (e.message ?: "network error")) }
            }
        }
    }

    fun downloadAll(list: List<Song>) {
        if (list.isEmpty()) { toast("Nothing new to download"); return }
        MaterialAlertDialogBuilder(this).setTitle("Download " + list.size + " songs?")
            .setMessage("They download one after another while the app stays open.")
            .setPositiveButton("Download") { _, _ ->
                hadDownloads = true
                bg {
                    var ok = 0
                    var fail = 0
                    for (s in list) {
                        val err = Downloader.download(applicationContext, s)
                        if (err == null) ok++ else fail++
                        ui { refreshAdapters() }
                    }
                    ui { toast("Downloaded $ok songs" + (if (fail > 0) ", $fail failed" else "")) }
                }
            }
            .setNegativeButton("Cancel", null).showGlass()
    }

    fun resumeLast() {
        val s = Store.lastSong ?: return
        resumePos = Store.lastPos
        playQueue(listOf(s), 0, "Continue Listening", false)
    }

    fun playlistMenu(name: String, then: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(name).setItems(arrayOf("Rename", "Delete")) { _, i ->
            if (i == 0) renamePlaylistDialog(name, then) else deletePlaylistDialog(name, then)
        }.showGlass()
    }

    private fun renamePlaylistDialog(name: String, then: () -> Unit) {
        val input = android.widget.EditText(this)
        input.setText(name)
        input.setSingleLine(true)
        input.setSelection(name.length)
        MaterialAlertDialogBuilder(this).setTitle("Rename playlist").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                if (Store.renamePlaylist(name, input.text.toString())) then() else toast("Pick a different name")
            }
            .setNegativeButton("Cancel", null).showGlass()
    }

    private fun discordLabel(): String = when {
        !Discord.available -> "SDK not installed"
        Store.discordOn && Discord.ready -> "On"
        Store.discordOn -> "Connecting… (" + Discord.status + ")"
        else -> "Off"
    }

    private fun discordMenu() {
        if (!Discord.available) {
            MaterialAlertDialogBuilder(this).setTitle("Discord status")
                .setMessage("This build doesn't include the Discord Social SDK yet. Put discord_partner_sdk.aar in app/libs and rebuild (see README).")
                .setPositiveButton("OK", null).showGlass()
            return
        }
        val items = if (Store.discordOn) arrayOf("Turn off", "Unlink Discord account") else arrayOf("Connect Discord")
        MaterialAlertDialogBuilder(this).setTitle("Discord status").setItems(items) { _, i ->
            if (Store.discordOn) {
                if (i == 0) Discord.disconnect() else Discord.forget()
            } else {
                Discord.connect()
            }
            lastPresence = ""
            settings()
        }.showGlass()
    }

    private fun pushPresence() {
        if (!Discord.available || !Discord.ready || !Store.discordOn) return
        val s = current
        val c = controller
        val playing = c?.isPlaying == true
        val sig = (s?.key ?: "") + "|" + playing
        if (sig == lastPresence) return
        lastPresence = sig
        if (s == null || !playing) { Discord.clear(); return }
        val dur = (c?.duration ?: -1L).let { if (it > 0) it else lengthMs(s.length) }
        val pos = c?.currentPosition ?: 0L
        val start = System.currentTimeMillis() - pos
        Discord.set(s.name, s.era, Cover.urlFor(s.era), start, if (dur > 0) start + dur else 0L)
    }

    private fun lengthMs(l: String): Long {
        val p = l.split(":")
        return try {
            if (p.size == 2) (p[0].toLong() * 60 + p[1].toLong()) * 1000 else 0L
        } catch (_: Exception) { 0L }
    }

    private val discordTicker = object : Runnable {
        override fun run() {
            Discord.tick()
            main.postDelayed(this, 150)
        }
    }

    /* ----- about / what's new ----- */

    private fun aboutDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Ye Tracker v" + Updater.currentName(this))
            .setMessage("An unofficial, fan-made app for yetracker.cc.\n\nOpen source on GitHub.")
            .setPositiveButton("Open GitHub") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + Updater.REPO)))
            }
            .setNegativeButton("Close", null).showGlass()
    }

    /** After an update installs, show what changed once. */
    private fun showWhatsNew() {
        val cur = Updater.currentName(this)
        val last = Store.lastVersion
        Store.lastVersion = cur
        if (last.isEmpty() || last == cur) return
        MaterialAlertDialogBuilder(this).setTitle("Updated to v$cur")
            .setMessage("• Everything is Liquid Glass: floating glass header, labelled glass tab bar, glass cards, chips and dialogs\n• Ambient glow that follows the current song\n• Performance toggles in Settings: glass mode, update rate, ambient glow, animations, cover art\n• Song length on every row, progress line on the mini player")
            .setPositiveButton("Nice", null).showGlass()
    }

    /* ----- updater ----- */

    fun checkUpdate(manual: Boolean) {
        if (manual) toast("Checking for updates…")
        bg {
            try {
                val info = Updater.fetch()
                val cur = Updater.currentName(this)
                ui {
                    if (Updater.isNewer(info.name, cur)) updateDialog(info)
                    else if (manual) toast("You're on the latest version")
                }
            } catch (e: Exception) {
                if (manual) ui { toast("Couldn't check for updates: " + (e.message ?: "network error")) }
            }
        }
    }

    private fun updateDialog(info: Updater.Info) {
        val msg = (if (info.notes.isNotEmpty()) info.notes + "\n\n" else "") + "Download and install it now?"
        MaterialAlertDialogBuilder(this).setTitle("Update available · v" + info.name)
            .setMessage(msg)
            .setPositiveButton("Update") { _, _ -> downloadUpdate(info) }
            .setNegativeButton("Later", null).showGlass()
    }

    private fun downloadUpdate(info: Updater.Info) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(16), dp(24), dp(8))
        box.addView(bar, LinearLayout.LayoutParams(MATCH_, WRAP_))
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Downloading update…")
            .setView(box).setCancelable(false).showGlass()
        bg {
            try {
                val f = Updater.download(this, info) { p -> ui { bar.progress = p } }
                ui { dlg.dismiss(); installApk(f) }
            } catch (e: Exception) {
                ui { dlg.dismiss(); toast("Update failed: " + (e.message ?: "network error")) }
            }
        }
    }

    private fun installApk(f: java.io.File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow \"Install unknown apps\" for Ye Tracker, then check for updates again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun accentDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Accent color")
            .setItems(Pal.ACCENT_NAMES.toTypedArray()) { _, i ->
                Store.accentIdx = i
                toast("Accent color applies next time you open the app")
                settings()
            }.showGlass()
    }

    private fun sleepLabel(): String {
        val now = SystemClock.uptimeMillis()
        return when {
            sleepEndOfSong -> "End of song"
            sleepAt > now -> ((sleepAt - now) / 60000 + 1).toString() + " min left"
            else -> "Off"
        }
    }

    private fun sleepDialog() {
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this song")
        MaterialAlertDialogBuilder(this).setTitle("Sleep timer").setItems(labels) { _, i ->
            main.removeCallbacks(sleepRun)
            sleepAt = 0L
            sleepEndOfSong = false
            when (i) {
                1, 2, 3, 4 -> {
                    val min = i * 15
                    sleepAt = SystemClock.uptimeMillis() + min * 60000L
                    main.postDelayed(sleepRun, min * 60000L)
                    toast("Sleep timer set for $min minutes")
                }
                5 -> { sleepEndOfSong = true; toast("Will pause when this song ends") }
                else -> toast("Sleep timer off")
            }
            settings()
        }.showGlass()
    }

    private fun speedDialog() {
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        MaterialAlertDialogBuilder(this).setTitle("Playback speed")
            .setItems(speeds.map { it.toString() + "x" }.toTypedArray()) { _, i ->
                Store.speed = speeds[i]
                applySpeed()
                settings()
            }.showGlass()
    }

    fun push(v: View) {
        val bgd = AmbientDrawable()
        bgd.glow = Store.ambient
        bgd.setColors(ambient.c1, ambient.c2)
        v.background = bgd
        content.addView(v, FrameLayout.LayoutParams(MATCH_, MATCH_))
        updateHeader()
        if (Store.animations) {
            v.translationX = root.width.toFloat()
            v.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun pop() {
        if (popping || content.childCount <= 1) return
        val v = content.getChildAt(content.childCount - 1)
        if (!Store.animations) {
            content.removeView(v)
            updateHeader()
            return
        }
        popping = true
        v.animate().translationX(root.width.toFloat()).setDuration(210).withEndAction {
            content.removeView(v)
            popping = false
            updateHeader()
        }.start()
    }

    fun openEra(e: Era, released: Boolean = false) {
        if (released) {
            push(SongListPage(this, e.name, "Released · " + e.years, e.name) { cancel, emit ->
                Tracker.crawlReleased(e.name, cancel, emit)
            }.view)
            return
        }
        val sub = (if (e.count >= 0) e.years + " · " + e.count + " songs" else "") + if (e.aka.isNotEmpty()) "\n" + e.aka else ""
        push(SongListPage(this, e.name, sub, e.name, eraRadio = e.name) { cancel, emit ->
            Tracker.crawl(null, e.name, null, cancel, emit)
        }.view)
    }

    fun openTab(key: String, label: String) {
        push(SongListPage(this, label, "From the Ye Tracker", key) { cancel, emit ->
            if (key == "released") Tracker.crawlReleased(null, cancel, emit) else Tracker.crawl(key, null, null, cancel, emit)
        }.view)
    }

    fun openDownloads() {
        push(SongListPage(this, "Downloads", "Saved on this device", "downloads") { _, emit -> emit(Store.downloads()) }.view)
    }

    fun openPlaylist(name: String) {
        push(SongListPage(this, name, "Local playlist", name, name) { _, emit -> emit(Store.playlist(name)) }.view)
    }

    fun newPlaylist(then: (String) -> Unit) {
        val input = android.widget.EditText(this)
        input.hint = "Playlist name"
        input.setSingleLine(true)
        MaterialAlertDialogBuilder(this).setTitle("New playlist").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && Store.createPlaylist(n)) then(n) else toast("Pick a different name")
            }.setNegativeButton("Cancel", null).showGlass()
    }

    fun deletePlaylistDialog(name: String, then: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ -> Store.deletePlaylist(name); then() }
            .setNegativeButton("Cancel", null).showGlass()
    }

    private fun addToPlaylistDialog(s: Song) {
        val names = Store.playlistNames()
        val items = (names + "New playlist…").toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Add to playlist").setItems(items) { _, i ->
            if (i < names.size) {
                Store.addToPlaylist(names[i], s)
                toast("Added to ${names[i]}")
            } else {
                newPlaylist { n -> Store.addToPlaylist(n, s); toast("Added to $n") }
            }
        }.showGlass()
    }

    /* ----- dialogs ----- */

    fun tap(list: List<Song>, s: Song) {
        if (!s.canPlay) { details(s); return }
        val idx = list.indexOfFirst { it.key == s.key }
        playQueue(list, if (idx < 0) 0 else idx, s.era, false)
    }

    fun details(s: Song) {
        val sb = StringBuilder()
        fun add(k: String, v: String) { if (v.isNotEmpty()) sb.append(k).append(": ").append(v).append("\n") }
        add("Era", s.era); add("Artist", s.artist); add("Released", s.released); add("Availability", s.availability); add("Quality", s.quality)
        add("Length", s.length); add("Leaked", s.leaked); add("Recorded", s.recorded); add("Credits", s.credits)
        if (s.notes.isNotEmpty()) sb.append("\n").append(s.notes)
        MaterialAlertDialogBuilder(this).setTitle(s.name).setMessage(sb.toString().trim())
            .setPositiveButton("Close", null).showGlass()
    }

    fun songMenu(s: Song, playlist: String? = null, onChanged: () -> Unit = {}) {
        val fav = Store.isFav(s.key)
        val dl = Store.isDownloaded(s.key)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(l: String, a: () -> Unit) { labels.add(l); actions.add(a) }
        if (s.canPlay) {
            item("Play next") { playNext(s) }
            item("Add to queue") { addToQueue(s) }
        }
        item(if (fav) "Remove from Favorites" else "Add to Favorites") { Store.toggleFav(s); updatePlayUi(); onChanged() }
        item("Add to playlist…") { addToPlaylistDialog(s) }
        if (playlist != null) item("Remove from this playlist") { Store.removeFromPlaylist(playlist, s.key); onChanged() }
        item("Details & notes") { details(s) }
        if (s.era.isNotEmpty() && s.era != "Downloads") {
            item("Go to era") { openEra(Era(-1, s.era, "", "", -1), s.key.startsWith("released:")) }
        }
        if (s.artist.isNotEmpty()) {
            item("More by " + s.artist) {
                push(SongListPage(this, s.artist, "Released songs", s.era) { cancel, emit ->
                    Tracker.crawl("released", null, s.artist, cancel, emit)
                }.view)
            }
        }
        item("Share") { shareSong(s) }
        if (dl) item("Delete download") { Store.deleteDownload(s.key); toast("Deleted"); onChanged() }
        else if (!s.key.startsWith("local:")) item("Download") { download(s) }
        if (!s.key.startsWith("local:")) item("Open on yetracker.cc") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl(s))))
        }
        MaterialAlertDialogBuilder(this).setTitle(s.name)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }.showGlass()
    }

    private fun siteUrl(s: Song): String =
        if (s.key.startsWith("released:")) "https://yetracker.cc/?tab=released&id=" + Uri.encode(s.key)
        else "https://yetracker.cc/?id=" + Uri.encode(s.key)

    private fun shareSong(s: Song) {
        val line = s.name + " — " + s.subtitle + "\n" + siteUrl(s)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, line)
        startActivity(Intent.createChooser(i, "Share song"))
    }

    private fun download(s: Song) {
        if (Downloader.progress.containsKey(s.key)) { toast("Already downloading"); return }
        toast("Downloading ${s.name}…")
        hadDownloads = true
        bg {
            val err = Downloader.download(applicationContext, s)
            ui {
                if (err == null) toast("Downloaded ${s.name} · also saved to Music/Ye Tracker")
                else toast("Download failed: $err")
                refreshAdapters()
            }
        }
    }

    private fun refreshAdapters() {
        adapters.keys.toList().forEach { it.notifyDataSetChanged() }
    }

    fun playNext(s: Song) {
        if (queue.isEmpty()) { playQueue(listOf(s), 0, s.era, false); return }
        val l = queue.toMutableList()
        l.add((qi + 1).coerceIn(0, l.size), s)
        queue = l
        toast("Playing next")
    }

    fun addToQueue(s: Song) {
        if (queue.isEmpty()) { playQueue(listOf(s), 0, s.era, false); return }
        queue = queue + s
        toast("Added to queue")
    }

    fun toggleShuffle() {
        if (queue.isEmpty()) { shuffleOn = !shuffleOn; updatePlayUi(); return }
        shuffleOn = !shuffleOn
        val cur = current
        if (shuffleOn) {
            origQueue = queue
            val rest = queue.filterIndexed { i, _ -> i != qi }.shuffled()
            queue = if (cur != null) listOf(cur) + rest else rest
            qi = 0
        } else {
            if (origQueue.isNotEmpty()) {
                queue = origQueue
                val i = queue.indexOfFirst { it.key == cur?.key }
                qi = if (i >= 0) i else 0
            }
        }
        toast(if (shuffleOn) "Shuffle on" else "Shuffle off")
        updatePlayUi()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        toast(when (repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" })
        updatePlayUi()
    }

    fun queueDialog() {
        if (queue.isEmpty()) return
        val upcoming = queue.drop(qi + 1).take(40)
        if (upcoming.isEmpty()) { toast("Nothing up next"); return }
        MaterialAlertDialogBuilder(this).setTitle("Up Next")
            .setItems(upcoming.map { it.name }.toTypedArray()) { _, i ->
                qi += i + 1
                playCurrent()
            }
            .setNeutralButton("Save as playlist") { _, _ ->
                val snapshot = queue.toList()
                newPlaylist { n ->
                    snapshot.forEach { Store.addToPlaylist(n, it) }
                    toast("Saved " + snapshot.size + " songs to " + n)
                }
            }.showGlass()
    }

    /* ----- player open/close ----- */

    fun openPlayer() {
        if (current == null) return
        playerOpen = true
        player.view.visibility = View.VISIBLE
        if (Store.animations) {
            player.view.translationY = root.height.toFloat()
            player.view.animate().translationY(0f).setDuration(280).start()
        } else {
            player.view.translationY = 0f
        }
    }

    fun closePlayer() {
        playerOpen = false
        if (!Store.animations) {
            player.view.visibility = View.GONE
            return
        }
        player.view.animate().translationY(root.height.toFloat()).setDuration(240)
            .withEndAction { player.view.visibility = View.GONE }.start()
    }

    /* ----- playback ----- */

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errorStreak = 0
            updatePlayUi()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (sleepEndOfSong) { sleepEndOfSong = false; toast("Sleep timer: playback paused") }
                else if (repeatMode == 2) playCurrent() else next()
            }
            updatePlayUi()
        }

        override fun onPlayerError(error: PlaybackException) {
            errorStreak++
            toast("Couldn't play this file")
            if (errorStreak < 3) next()
        }
    }

    private fun connect() {
        val tokenS = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, tokenS).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                val todo = ArrayList(pending)
                pending.clear()
                todo.forEach { it(c) }
                updatePlayUi()
            } catch (e: Exception) {
                toast("Player unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun withController(f: (MediaController) -> Unit) {
        val c = controller
        if (c != null) f(c) else pending.add(f)
    }

    fun playQueue(list: List<Song>, index: Int, src: String, endlessMode: Boolean) {
        if (list.isEmpty()) return
        queue = list
        qi = index.coerceIn(0, list.size - 1)
        source = src
        if (!endlessMode) radioEra = null
        endless = endlessMode
        playCurrent()
    }

    fun startShuffle() {
        toast("Shuffling the tracker…")
        bg {
            try {
                val songs = Tracker.random(25).filter { it.playable }
                ui { if (songs.isEmpty()) toast("No tracks returned") else playQueue(songs, 0, "Shuffle the whole tracker Radio", true) }
            } catch (e: Exception) {
                ui { toast("Couldn't reach yetracker.cc: ${e.message}") }
            }
        }
    }

    private fun playCurrent() {
        val s = queue.getOrNull(qi) ?: return
        current = s
        val my = ++token
        songMs = 0L
        songCounted = false
        Store.addRecent(s)
        val local = s.localUri ?: Store.localFor(s.key)
        if (local != null) {
            loading = false
            startUri(s, local, my)
            updatePlayUi()
            return
        }
        loading = true
        updatePlayUi()
        bg {
            try {
                val url = resolveUrl(s)
                ui {
                    if (my != token) return@ui
                    if (url.isEmpty()) {
                        toast("No playable file for ${s.name}")
                        loading = false
                        next()
                    } else {
                        startUri(s, url, my)
                    }
                }
            } catch (e: Exception) {
                ui {
                    if (my != token) return@ui
                    loading = false
                    updatePlayUi()
                    toast("Couldn't load: ${e.message}")
                }
            }
        }
    }

    private fun resolveUrl(s: Song): String {
        urlCache[s.key]?.let { return it }
        val f = Tracker.pickFile(Tracker.entry(s.key))
        val url = f?.str("url") ?: ""
        if (url.isNotEmpty()) urlCache[s.key] = url
        return url
    }

    private fun prefetchNext() {
        if (!Store.prefetch) return
        var i = qi + 1
        while (i < queue.size && !queue[i].canPlay) i++
        val n = queue.getOrNull(i) ?: return
        if (n.localUri != null || urlCache.containsKey(n.key)) return
        bg { try { resolveUrl(n) } catch (_: Exception) {} }
    }

    private fun startUri(s: Song, uri: String, my: Int) {
        withController { c ->
            if (my != token) return@withController
            val mb = MediaMetadata.Builder().setTitle(s.name).setArtist(s.subtitle).setAlbumTitle(source)
            val cu = coverUri(s.era)
            if (cu != null) mb.setArtworkUri(cu) else mb.setArtworkData(gradientArt(s.era), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            val md = mb.build()
            c.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(md).build())
            c.prepare()
            applySpeed()
            if (resumePos > 0L) {
                c.seekTo(resumePos)
                resumePos = 0L
            }
            c.play()
            loading = false
            updatePlayUi()
            prefetchNext()
        }
    }

    fun next() {
        var i = qi + 1
        while (i < queue.size && !queue[i].canPlay) i++
        if (i < queue.size) {
            qi = i
            playCurrent()
            if (endless && i >= queue.size - 4) extendShuffle()
        } else if (endless) {
            extendShuffle()
        } else if (repeatMode == 1) {
            val j = queue.indexOfFirst { it.canPlay }
            if (j >= 0) { qi = j; playCurrent() }
        }
    }

    fun prev() {
        val c = controller
        if (c != null && c.currentPosition > 3000) { c.seekTo(0); return }
        var i = qi - 1
        while (i >= 0 && !queue[i].canPlay) i--
        if (i >= 0) { qi = i; playCurrent() } else c?.seekTo(0)
    }

    private fun extendShuffle() {
        if (extending) return
        extending = true
        bg {
            try {
                val more = Tracker.random(20, null, radioEra).filter { it.playable }
                ui {
                    if (more.isNotEmpty() && endless) {
                        val wasAtEnd = qi >= queue.size - 1
                        queue = queue + more
                        if (wasAtEnd) next()
                    }
                    extending = false
                }
            } catch (e: Exception) {
                ui { extending = false }
            }
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        haptic()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekFraction(f: Float) {
        val c = controller ?: return
        val d = c.duration
        if (d > 0) c.seekTo((d * f).toLong())
    }

    fun updatePlayUi() {
        val s = current
        mini.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) {
            miniTitle.text = s.name
            ambient.setColors(seedColors(s.era).first, Pal.ACCENT)
            miniSub.text = if (loading) "Loading…" else s.subtitle
            miniArt.background = art(s.era, 9f)
            miniArt.loadCover(s.era, 9f)
        }
        val playing = controller?.isPlaying == true
        miniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        player.bind(s, playing, loading, source)
        val fav = s != null && Store.isFav(s.key)
        pushPresence()
        Bus.lastShuffle = shuffleOn
        Bus.lastFav = fav
        Bus.setLayout?.invoke(shuffleOn, fav)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null) {
                if (c.isPlaying) {
                    Store.addMs(500)
                    lastTick++
                    if (lastTick % 10 == 0) Store.setLast(current, c.currentPosition)
                    val cs = current
                    if (cs != null) {
                        Store.addSongMs(cs, 500)
                        songMs += 500
                        if (!songCounted && songMs >= 20000) {
                            songCounted = true
                            Store.addPlay(cs)
                        }
                    }
                }
                val d = c.duration
                player.progress(c.currentPosition, if (d > 0) d else -1L)
                mini.progress = if (d > 0) c.currentPosition.toFloat() / d else -1f
                if (mini.visibility == View.VISIBLE) mini.invalidate()
            }
            if (Store.glassRefresh == 2) {
                if (SystemClock.uptimeMillis() - lastScrollAt > 400) invalidateGlass(true)
            } else {
                invalidateGlass()
            }
            if (Downloader.progress.isNotEmpty()) { hadDownloads = true; refreshAdapters() }
            else if (hadDownloads) { hadDownloads = false; refreshAdapters() }
            main.postDelayed(this, 500)
        }
    }
}
