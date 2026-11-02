package cc.yetracker.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * "Liquid glass": a live, blurred, saturated copy of whatever sits behind the view
 * (the page content), a white sheen and a light-catching edge. When disabled it falls
 * back to a flat translucent dark pill.
 */
class Glass(val v: View, private val radiusDp: Float) {
    var source: View? = null
    var enabled = true
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
        val r = radiusDp * v.resources.displayMetrics.density
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val path = Path()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        val src = source
        if (enabled && src != null && src.width > 0) {
            val lite = Store.glassLite
            val scale = if (lite) 0.07f else 0.1f
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
            cv.translate((s[0] - a[0]).toFloat(), (s[1] - a[1]).toFloat())
            try { src.draw(cv) } catch (_: Exception) {}
            blur(b, if (lite) 1 else 2)
            c.drawBitmap(b, null, rect, bmpPaint)
            fill.color = Color.argb(95, 12, 12, 14)
            c.drawRect(rect, fill)
            c.drawRect(rect, sheen)
        } else {
            fill.color = Pal.GLASS
            c.drawRect(rect, fill)
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

class GlassLinear(ctx: Context, radiusDp: Float) : LinearLayout(ctx) {
    val glass = Glass(this, radiusDp)

    init { setWillNotDraw(false) }

    override fun dispatchDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.dispatchDraw(canvas)
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
