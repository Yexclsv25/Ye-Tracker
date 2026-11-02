package cc.yetracker.mobile

import android.app.Activity
import androidx.annotation.Keep

/**
 * Discord "Listening to" status through the official Discord Social SDK.
 * Without app/libs/discord_partner_sdk.aar the native library is absent and everything
 * here quietly does nothing (available == false).
 */
object Discord {
    const val APP_ID = 1566609960685535273L

    var available = false
        private set
    var status = "Not connected"
        private set
    val ready: Boolean get() = status == "Ready"
    var onReady: (() -> Unit)? = null

    private external fun nativeInit(appId: Long)
    private external fun nativeAuthorize()
    private external fun nativeResume(refreshToken: String)
    private external fun nativeSetPresence(title: String, state: String, imageUrl: String, startMs: Long, endMs: Long)
    private external fun nativeClear()
    private external fun nativeTick()

    fun init(activity: Activity) {
        try {
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                .getMethod("setEngineActivity", Activity::class.java)
                .invoke(null, activity)
        } catch (_: Throwable) {
        }
        try {
            System.loadLibrary("yediscord")
            nativeInit(APP_ID)
            available = true
            val rt = Store.discordRefresh
            if (Store.discordOn && rt.isNotEmpty()) nativeResume(rt)
        } catch (_: Throwable) {
            available = false
        }
    }

    fun connect() {
        if (!available) return
        Store.discordOn = true
        val rt = Store.discordRefresh
        try {
            if (rt.isNotEmpty()) nativeResume(rt) else nativeAuthorize()
        } catch (_: Throwable) {
        }
    }

    fun disconnect() {
        Store.discordOn = false
        if (available) try { nativeClear() } catch (_: Throwable) {}
    }

    fun forget() {
        Store.discordRefresh = ""
        disconnect()
        status = "Not connected"
    }

    fun tick() {
        if (available) try { nativeTick() } catch (_: Throwable) {}
    }

    fun set(title: String, state: String, imageUrl: String?, startMs: Long, endMs: Long) {
        if (!available || !ready || !Store.discordOn) return
        try { nativeSetPresence(title, state, imageUrl ?: "", startMs, endMs) } catch (_: Throwable) {}
    }

    fun clear() {
        if (available && ready) try { nativeClear() } catch (_: Throwable) {}
    }

    // ---- called from native ----
    @Keep
    fun onStatus(s: String) {
        val wasReady = ready
        status = s
        if (ready && !wasReady) onReady?.invoke()
    }

    @Keep
    fun onToken(access: String, refresh: String, expiresIn: Int) {
        if (refresh.isNotEmpty()) Store.discordRefresh = refresh
    }
}
