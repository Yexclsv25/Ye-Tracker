package cc.yetracker.mobile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Build
import android.os.Bundle
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

object Bus {
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrev: (() -> Unit)? = null
    @Volatile var onFav: (() -> Unit)? = null
    @Volatile var onShuffle: (() -> Unit)? = null
    @Volatile var setLayout: ((Boolean, Boolean) -> Unit)? = null
    @Volatile var eqPreset = 0
    @Volatile var skipSilence = false
    @Volatile var applyAudio: (() -> Unit)? = null
    @Volatile var lastShuffle = false
    @Volatile var lastFav = false
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    private fun releaseFx() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        eq = null
        bass = null
    }

    private fun attachFx(sessionId: Int) {
        releaseFx()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            eq = Equalizer(0, sessionId).also { it.enabled = true }
            bass = BassBoost(0, sessionId)
        } catch (_: Throwable) {
        }
        applyEq(Bus.eqPreset)
    }

    /** 0 flat, 1 bass boost, 2 bass boost+, 3 treble, 4 vocal, 5 loudness */
    private fun applyEq(preset: Int) {
        val e = eq ?: return
        try {
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            for (i in 0 until n) {
                val pos = if (n > 1) i.toFloat() / (n - 1) else 0f
                val g = when (preset) {
                    1 -> if (pos < 0.34f) 0.6f else if (pos < 0.67f) 0.1f else 0f
                    2 -> if (pos < 0.34f) 0.95f else if (pos < 0.67f) 0.4f else 0f
                    3 -> if (pos > 0.66f) 0.6f else if (pos > 0.33f) 0.1f else 0f
                    4 -> if (pos < 0.25f) -0.2f else if (pos < 0.8f) 0.5f else 0.2f
                    5 -> if (pos < 0.3f) 0.5f else if (pos > 0.7f) 0.4f else -0.1f
                    else -> 0f
                }
                e.setBandLevel(i.toShort(), (g * max).toInt().coerceIn(min, max).toShort())
            }
            val strength = when (preset) { 1 -> 400; 2 -> 850; 5 -> 300; else -> 0 }
            bass?.setStrength(strength.toShort())
            bass?.enabled = strength > 0
        } catch (_: Throwable) {
        }
    }

    private fun layout(shuffle: Boolean, fav: Boolean): ImmutableList<CommandButton> {
        val s = CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand("shuffle", Bundle.EMPTY))
            .build()
        val f = CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand("fav", Bundle.EMPTY))
            .build()
        return ImmutableList.of(s, f)
    }

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Small start/rebuffer thresholds so tracks begin playing almost immediately.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 400, 800)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(load)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachFx(audioSessionId) }
        })
        Bus.applyAudio = {
            applyEq(Bus.eqPreset)
            exo.setSkipSilenceEnabled(Bus.skipSilence)
        }
        Bus.applyAudio?.invoke()
        attachFx(exo.audioSessionId)
        val extra = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
        // The app owns the queue, so the notification's next/previous buttons call back into it.
        val player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*extra).build()

            override fun isCommandAvailable(command: Int): Boolean =
                extra.contains(command) || super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
            override fun seekToNext() { Bus.onNext?.invoke() }
            override fun seekToPrevious() { Bus.onPrev?.invoke() }
            override fun seekToNextMediaItem() { Bus.onNext?.invoke() }
            override fun seekToPreviousMediaItem() { Bus.onPrev?.invoke() }
        }
        val cb = object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand("shuffle", Bundle.EMPTY))
                    .add(SessionCommand("fav", Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(cmds).build()
            }

            override fun onCustomCommand(
                session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    "shuffle" -> Bus.onShuffle?.invoke()
                    "fav" -> Bus.onFav?.invoke()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        // Tapping the notification / lockscreen / status-bar player opens the app's Now Playing screen.
        val open = Intent(this, MainActivity::class.java)
        open.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        open.putExtra("open_player", true)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = MediaSession.Builder(this, player).setCallback(cb).setSessionActivity(pi)
            .setCustomLayout(layout(Bus.lastShuffle, Bus.lastFav)).build()
        session = s
        Bus.setLayout = { shuf, fav -> s.setCustomLayout(layout(shuf, fav)) }
        val provider = DefaultMediaNotificationProvider(this)
        provider.setSmallIcon(R.drawable.ic_note)
        setMediaNotificationProvider(provider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        Bus.setLayout = null
        Bus.applyAudio = null
        releaseFx()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

object Pal {
    var BG = Color.parseColor("#0D0D0D")
    var SURFACE = Color.parseColor("#1F1E20")
    var ACCENT = Color.parseColor("#E8917F")
    var ACCENT_DIM = Color.parseColor("#3B2A28")

    val ACCENT_NAMES = listOf("Salmon", "Blue", "Green", "Purple", "Pink", "Gold")
    private val ACCENTS = listOf(
        "#E8917F" to "#3B2A28", "#5AA9FF" to "#1F2E40", "#5FD18B" to "#1E3328",
        "#B38CFF" to "#2D2540", "#FF6FA8" to "#40222F", "#F2C15A" to "#3A3220"
    )

    fun setAmoled(on: Boolean) {
        BG = if (on) Color.BLACK else Color.parseColor("#0D0D0D")
        SURFACE = if (on) Color.parseColor("#171718") else Color.parseColor("#1F1E20")
    }

    fun setAccent(i: Int) {
        val p = ACCENTS[i.coerceIn(0, ACCENTS.size - 1)]
        ACCENT = Color.parseColor(p.first)
        ACCENT_DIM = Color.parseColor(p.second)
    }
    val TEXT = Color.WHITE
    val SUB = Color.parseColor("#9B9A9E")
    val GLASS = Color.parseColor("#E61C1B1D")
    val STROKE = Color.parseColor("#33FFFFFF")
    val SOFT = Color.parseColor("#33FFFFFF")
}

/** Glass-style background for cards, chips and buttons (flat when glass is off). */
fun Context.glass(radiusDp: Float, tint: Int = 0, tintAlpha: Int = 205): Drawable =
    GlassSurface(this, radiusDp, tint, tintAlpha)

/** Shows a dialog on a frosted, translucent panel (with real background blur where the phone supports it). */
fun MaterialAlertDialogBuilder.showGlass(): AlertDialog {
    val d = create()
    val ctx = context
    d.window?.let { w ->
        w.setBackgroundDrawable(InsetDrawable(ctx.rounded(Pal.GLASS, 28f, Pal.STROKE, 1), ctx.dp(18)))
        w.setDimAmount(0.35f)
        if (Build.VERSION.SDK_INT >= 31 && Store.glassMode > 0) {
            try {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = w.attributes
                lp.blurBehindRadius = 28
                w.attributes = lp
            } catch (_: Throwable) {
            }
        }
    }
    d.show()
    return d
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun fmt(ms: Long): String {
    val s = if (ms < 0) 0 else ms / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

fun seedColors(seed: String): Pair<Int, Int> {
    val h = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val a = Color.HSVToColor(floatArrayOf(h, 0.55f, 0.78f))
    val b = Color.HSVToColor(floatArrayOf((h + 40f) % 360f, 0.8f, 0.34f))
    return Pair(a, b)
}

fun Context.art(seed: String, radiusDp: Float): GradientDrawable {
    val (a, b) = seedColors(seed)
    val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b))
    g.cornerRadius = radiusDp * resources.displayMetrics.density
    return g
}

fun Context.rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radiusDp * resources.displayMetrics.density
    if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
    return g
}

fun Context.tv(
    t: CharSequence, sp: Float, color: Int = Pal.TEXT, bold: Boolean = false, maxLines: Int = 0
): TextView {
    val v = TextView(this)
    v.text = t
    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    v.setTextColor(color)
    if (bold) v.typeface = Typeface.DEFAULT_BOLD
    if (maxLines > 0) {
        v.maxLines = maxLines
        v.ellipsize = TextUtils.TruncateAt.END
    }
    return v
}

fun Context.icon(res: Int, tint: Int = Pal.TEXT, padDp: Int = 0): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    ImageViewCompat.setImageTintList(v, ColorStateList.valueOf(tint))
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    v.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    return v
}

@SuppressLint("ClickableViewAccessibility")
fun View.pressable(onClick: () -> Unit) {
    isClickable = true
    setOnClickListener { onClick() }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().alpha(0.6f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(120).start()
        }
        false
    }
}
