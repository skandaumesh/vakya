package com.replybot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.replybot.agent.FridayBus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Friday's orb: a glowing core inside turning rings, an orbit of particles and a voice ring that
 * ripples while she listens or speaks. Its colour and pace follow what she's doing ([phase]):
 * calm cyan while waiting, bright while listening, blue and quick while thinking, teal while
 * looking at the screen. Drawn with a few cached shaders and no allocation per frame, so it stays
 * smooth on mid-range phones; [compact] drops the finer details for small sizes.
 */
class OrbView(context: Context, private val compact: Boolean = false) : View(context) {

    var phase: FridayBus.Phase = FridayBus.Phase.IDLE
        set(v) {
            field = v
            target = colorFor(v)
            speed = when (v) {
                FridayBus.Phase.THINKING, FridayBus.Phase.LOOKING -> 3.2f
                FridayBus.Phase.WORKING -> 2.2f
                FridayBus.Phase.LISTENING, FridayBus.Phase.SPEAKING -> 1.4f
                FridayBus.Phase.OFF -> 0.35f
                else -> 0.8f
            }
        }

    /** Microphone loudness, 0..1. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    private val d = resources.displayMetrics.density
    private var color = colorFor(phase)
    private var target = color
    private var speed = 0.8f
    private var angle = 0f
    private var t = 0f
    private var smooth = 0f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    private val box = RectF()
    private var filterColor = 0
    private var sized = 0

    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = System.nanoTime()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            t += dt
            angle = (angle + dt * 40f * speed) % 360f
            smooth += (level - smooth) * 0.25f
            color = blend(color, target, 0.08f)
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f
        if (r <= 0f) return
        // White gradients, tinted per frame with a colour filter: no new shader as the colour changes.
        halo.shader = RadialGradient(0f, 0f, r, intArrayOf(0x66FFFFFF, 0x22FFFFFF, 0x00FFFFFF), floatArrayOf(0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        core.shader = RadialGradient(0f, 0f, r * 0.42f, intArrayOf(0xFFFFFFFF.toInt(), 0xDDFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        hot.shader = RadialGradient(0f, 0f, r * 0.2f, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        dashed.pathEffect = DashPathEffect(floatArrayOf(2f * d, 6f * d), 0f)
        sized = w
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f
        if (r <= 0f || sized == 0) return
        if (filterColor != color) {
            filterColor = color
            val tint = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            halo.colorFilter = tint
            core.colorFilter = tint
        }
        val breathe = (sin(t * 2.1f) * 0.5f + 0.5f)
        val voice = smooth
        canvas.save()
        canvas.translate(w / 2f, h / 2f)

        // Halo: breathes, and swells with the voice.
        canvas.save()
        val hs = 0.82f + 0.08f * breathe + 0.15f * voice
        canvas.scale(hs, hs)
        canvas.drawCircle(0f, 0f, r, halo)
        canvas.restore()

        // Ticks around the edge, slowly turning.
        if (!compact) {
            stroke.color = color
            stroke.strokeWidth = 1f * d
            canvas.save()
            canvas.rotate(-angle * 0.25f)
            for (i in 0 until 60) {
                stroke.alpha = if (i % 5 == 0) 150 else 55
                val len = if (i % 5 == 0) 0.06f else 0.03f
                canvas.drawLine(0f, -r * 0.97f, 0f, -r * (0.97f - len), stroke)
                canvas.rotate(6f)
            }
            canvas.restore()
        }

        // Outer arcs.
        stroke.color = color
        stroke.alpha = 230
        stroke.strokeWidth = (if (compact) 2.2f else 2.6f) * d
        box.set(-r * 0.86f, -r * 0.86f, r * 0.86f, r * 0.86f)
        for (i in 0 until 3) canvas.drawArc(box, angle + i * 120f, 62f, false, stroke)

        // Dashed ring, the other way.
        if (!compact) {
            dashed.color = color
            dashed.alpha = 140
            dashed.strokeWidth = 1.4f * d
            canvas.save()
            canvas.rotate(-angle * 1.6f)
            canvas.drawCircle(0f, 0f, r * 0.74f, dashed)
            canvas.restore()
        }

        // Particles in orbit.
        fill.color = color
        val count = if (compact) 12 else 36
        for (i in 0 until count) {
            val a = (i * 2 * PI / count + angle * PI / 180 * (if (i % 2 == 0) 1.0 else -0.7)).toFloat()
            val wobble = sin(t * 1.7f + i) * 0.04f
            val rr = r * (0.66f + wobble + voice * 0.06f)
            fill.alpha = (90 + 120 * (sin(t * 3f + i * 0.9f) * 0.5f + 0.5f)).toInt()
            canvas.drawCircle(cos(a) * rr, sin(a) * rr, (if (i % 3 == 0) 2.2f else 1.4f) * d, fill)
        }

        // Voice ring: ripples with the sound.
        val ripple = when (phase) {
            FridayBus.Phase.LISTENING -> 0.05f + voice * 0.22f
            FridayBus.Phase.SPEAKING -> 0.06f + 0.08f * (sin(t * 9f) * 0.5f + 0.5f)
            FridayBus.Phase.THINKING, FridayBus.Phase.LOOKING, FridayBus.Phase.WORKING -> 0.035f
            else -> 0.015f
        }
        wave.reset()
        val points = if (compact) 36 else 72
        for (i in 0..points) {
            val a = (i * 2 * PI / points).toFloat()
            val n = sin(a * 5 + t * 4f) * 0.6f + sin(a * 9 - t * 6f) * 0.4f
            val rr = r * (0.5f + ripple * n)
            val x = cos(a) * rr
            val y = sin(a) * rr
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        stroke.alpha = 200
        stroke.strokeWidth = 1.8f * d
        canvas.drawPath(wave, stroke)

        // Core and its white-hot centre.
        canvas.save()
        val cs = 0.86f + 0.1f * breathe + 0.3f * voice
        canvas.scale(cs, cs)
        canvas.drawCircle(0f, 0f, r * 0.42f, core)
        canvas.drawCircle(0f, 0f, r * 0.2f, hot)
        canvas.restore()
        canvas.restore()
    }

    companion object {
        fun colorFor(p: FridayBus.Phase): Int = when (p) {
            FridayBus.Phase.OFF -> 0xFF3A6A80.toInt()
            FridayBus.Phase.LISTENING -> 0xFF5CE1FF.toInt()
            FridayBus.Phase.THINKING -> 0xFF6E8BFF.toInt()
            FridayBus.Phase.LOOKING -> 0xFF2EE6C8.toInt()
            FridayBus.Phase.WORKING -> 0xFF38BDF8.toInt()
            FridayBus.Phase.SPEAKING -> 0xFF7DEBFF.toInt()
            FridayBus.Phase.WAITING -> 0xFF9AD8FF.toInt()
            FridayBus.Phase.IDLE -> 0xFF4FC3E8.toInt()
        }

        fun blend(a: Int, b: Int, f: Float): Int {
            if (a == b) return a
            // Each channel moves at least one step, so the colour always arrives (and the filter stops changing).
            fun ch(x: Int, y: Int): Int {
                val step = ((y - x) * f).toInt()
                return x + if (step == 0 && y != x) (if (y > x) 1 else -1) else step
            }
            return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }
    }
}
