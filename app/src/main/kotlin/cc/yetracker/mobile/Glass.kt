package cc.yetracker.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Live "liquid glass" for the floating bars: a blurred, saturated copy of whatever is behind the view.
 * Store.glassMode: 0 flat, 1 static glass (no live blur), 2 live (light), 3 live (full).
 */
class Glass(val v: View, private val radiusDp: Float) {
    var source: View? = null
    var backdrop: View? = null
    private var bmp: Bitmap? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastW = 0
    private var lastH = 0

    init {
        val cm = ColorMatrix()
        cm.setSaturation(1.7f)
        bmpPaint.colorFilter = ColorMatrixColorFilter(cm)
        edge.style = Paint.Style.STROKE
    }

    private fun shaders(w: Int, h: Int) {
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        sheen.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(70, 255, 255, 255), Color.argb(8, 255, 255, 255), Shader.TileMode.CLAMP)
        edge.strokeWidth = v.resources.displayMetrics.density * 1.2f
        edge.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            null, Shader.TileMode.CLAMP)
    }

    fun draw(c: Canvas) {
        val w = v.width
        val h = v.height
        if (w == 0 || h == 0) return
        shaders(w, h)
        val mode = Store.glassMode
        val r = radiusDp * v.resources.displayMetrics.density
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val path = Path()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        val src = source
        if (mode >= 2 && src != null && src.width > 0) {
            val scale = if (mode == 2) 0.07f else 0.1f
            val bw = maxOf(2, (w * scale).toInt())
            val bh = maxOf(2, (h * scale).toInt())
            var b = bmp
            if (b == null || b.width != bw || b.height != bh) {
                b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            b!!.eraseColor(Pal.BG)
            val a = IntArray(2)
            val s = IntArray(2)
            v.getLocationInWindow(a)
            src.getLocationInWindow(s)
            val cv = Canvas(b)
            cv.scale(scale, scale)
            val bd = backdrop
            val bg = bd?.background
            if (bd != null && bg != null) {
                val rl = IntArray(2)
                bd.getLocationInWindow(rl)
                cv.save()
                cv.translate((rl[0] - a[0]).toFloat(), (rl[1] - a[1]).toFloat())
                bg.setBounds(0, 0, bd.width, bd.height)
                bg.draw(cv)
                cv.restore()
            }
            cv.translate((s[0] - a[0]).toFloat(), (s[1] - a[1]).toFloat())
            try { src.draw(cv) } catch (_: Exception) {}
            blur(b, if (mode == 2) 1 else 2)
            c.drawBitmap(b, null, rect, bmpPaint)
            fill.color = Color.argb(95, 12, 12, 14)
            c.drawRect(rect, fill)
            c.drawRect(rect, sheen)
        } else {
            fill.color = if (mode == 0) Pal.GLASS else Color.argb(232, 24, 24, 28)
            c.drawRect(rect, fill)
            if (mode == 1) c.drawRect(rect, sheen)
        }
        c.restore()
        val inset = edge.strokeWidth / 2f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r, r, edge)
    }

    private fun blur(b: Bitmap, rad: Int) {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        repeat(2) {
            pass(px, w, h, rad, true)
            pass(px, w, h, rad, false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(px: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val out = px.copyOf()
        val lines = if (horiz) h else w
        val len = if (horiz) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var rr = 0
                var g = 0
                var bl = 0
                var n = 0
                for (k in -r..r) {
                    val j = i + k
                    if (j < 0 || j >= len) continue
                    val p = if (horiz) px[l * w + j] else px[j * w + l]
                    rr += (p shr 16) and 255
                    g += (p shr 8) and 255
                    bl += p and 255
                    n++
                }
                val value = (255 shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bl / n)
                if (horiz) out[l * w + i] = value else out[i * w + l] = value
            }
        }
        System.arraycopy(out, 0, px, 0, px.size)
    }
}

/**
 * Cheap "glass" for cards, chips and buttons: a translucent frosted fill, a top sheen and a
 * light-catching edge. No per-frame work. Falls back to a flat surface when glass is off.
 */
class GlassSurface(ctx: Context, radiusDp: Float, private val tint: Int = 0, private val tintAlpha: Int = 205) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val r = radiusDp * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private var w = 0
    private var h = 0

    init {
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = density * 1.1f
        registry[this] = true
    }

    private fun prep(b: Rect) {
        if (b.width() == w && b.height() == h) return
        w = b.width()
        h = b.height()
        sheen.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.argb(if (tint != 0) 80 else 46, 255, 255, 255), Color.argb(5, 255, 255, 255), Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.argb(170, 255, 255, 255), Color.argb(22, 255, 255, 255), Color.argb(105, 255, 255, 255)),
            null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rect = RectF(b)
        val live = Store.glassMode > 0 && Store.glassAll
        if (!live) {
            fill.color = if (tint != 0) Color.argb(235, Color.red(tint), Color.green(tint), Color.blue(tint)) else Pal.SURFACE
            c.drawRoundRect(rect, r, r, fill)
            return
        }
        prep(b)
        fill.color = if (tint != 0) Color.argb(tintAlpha, Color.red(tint), Color.green(tint), Color.blue(tint))
        else Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, sheen)
        val inset = edge.strokeWidth / 2f
        c.drawRoundRect(RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), r, r, edge)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val registry = java.util.WeakHashMap<GlassSurface, Boolean>()

        fun refreshAll() {
            registry.keys.toList().forEach { it.invalidateSelf() }
        }
    }
}

/** App backdrop: near-black with two soft glows tinted by the current song's era. */
class AmbientDrawable : Drawable() {
    var c1 = Pal.ACCENT
    var c2 = Pal.ACCENT
    var glow = true
    private val bg = Paint()
    private val p1 = Paint()
    private val p2 = Paint()

    private fun a(c: Int, alpha: Int): Int = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        p1.shader = RadialGradient(w * 0.1f, h * 0.02f, w * 1.05f, a(c1, 90), a(c1, 0), Shader.TileMode.CLAMP)
        p2.shader = RadialGradient(w * 0.95f, h * 0.97f, w * 0.95f, a(c2, 60), a(c2, 0), Shader.TileMode.CLAMP)
    }

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    fun setColors(x: Int, y: Int) {
        if (x == c1 && y == c2) return
        c1 = x
        c2 = y
        rebuild()
        invalidateSelf()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        bg.color = Pal.BG
        c.drawRect(b, bg)
        if (glow && p1.shader != null) {
            c.drawRect(b, p1)
            c.drawRect(b, p2)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class GlassLinear(ctx: Context, radiusDp: Float) : LinearLayout(ctx) {
    val glass = Glass(this, radiusDp)
    var progress = -1f
    private val pp = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setWillNotDraw(false) }

    override fun dispatchDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.dispatchDraw(canvas)
        if (progress >= 0f) {
            val d = resources.displayMetrics.density
            val l = 24f * d
            val rr = width - 24f * d
            val y = height - 8f * d
            pp.color = Color.argb(45, 255, 255, 255)
            canvas.drawRoundRect(RectF(l, y, rr, y + 2.6f * d), 2f * d, 2f * d, pp)
            pp.color = Pal.ACCENT
            canvas.drawRoundRect(RectF(l, y, l + (rr - l) * progress.coerceIn(0f, 1f), y + 2.6f * d), 2f * d, 2f * d, pp)
        }
    }
}

class GlassFrame(ctx: Context, radiusDp: Float) : FrameLayout(ctx) {
    val glass = Glass(this, radiusDp)

    init { setWillNotDraw(false) }

    override fun dispatchDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.dispatchDraw(canvas)
    }
}
