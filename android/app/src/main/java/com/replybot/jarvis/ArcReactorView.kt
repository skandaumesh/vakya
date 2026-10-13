package com.replybot.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Friday's glowing ring: three arcs turning around a bright core that pulses with your voice.
 * Spins faster while Friday is thinking.
 */
class ArcReactorView(context: Context) : View(context) {

    private val cyan = 0xFF5CE1FF.toInt()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var angle = 0f
    private var speed = 1f

    /** Microphone loudness, 0..1: makes the core pulse. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** Thinking spins the arcs faster. */
    var thinking = false
        set(v) { field = v; speed = if (v) 3f else 1f }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0f
        addUpdateListener {
            val v = it.animatedValue as Float
            val delta = if (v >= last) v - last else v + 360f - last
            last = v
            angle = (angle + delta * speed) % 360f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.9f
        val d = resources.displayMetrics.density

        // Soft glow: a few wide, faint strokes.
        ring.color = cyan
        for ((w, a) in listOf(9f to 28, 6f to 50, 3f to 110)) {
            ring.strokeWidth = w * d
            ring.alpha = a
            canvas.drawCircle(cx, cy, r * 0.82f, ring)
        }
        // Three turning arcs.
        ring.strokeWidth = 2.6f * d
        ring.alpha = 235
        box.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until 3) canvas.drawArc(box, angle + i * 120f, 70f, false, ring)
        // Inner ring, turning the other way.
        ring.strokeWidth = 1.4f * d
        ring.alpha = 160
        val ri = r * 0.58f
        box.set(cx - ri, cy - ri, cx + ri, cy + ri)
        for (i in 0 until 4) canvas.drawArc(box, -angle * 1.5f + i * 90f, 55f, false, ring)
        // Core: white-hot centre, pulsing with the voice.
        val rc = r * (0.30f + 0.14f * level)
        core.shader = RadialGradient(cx, cy, rc, intArrayOf(0xFFFFFFFF.toInt(), cyan, 0x005CE1FF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rc, core)
    }
}
