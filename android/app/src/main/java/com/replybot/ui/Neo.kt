package com.replybot.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Friday's look: deep navy-black, glass panels with a hairline of cyan, monospace labels. */
object Neo {
    const val BG_TOP = 0xFF030712.toInt()
    const val BG_BOTTOM = 0xFF071A2E.toInt()
    const val CARD = 0xB30B1A30.toInt()
    const val CARD_STROKE = 0x335CE1FF
    const val INSET = 0x66102540
    const val CYAN = 0xFF5CE1FF.toInt()
    const val BLUE = 0xFF3B82F6.toInt()
    const val TEXT = 0xFFE8F7FF.toInt()
    const val BODY = 0xFFAFC7D8.toInt()
    const val MUTED = 0xFF6B8AA3.toInt()
    const val OK = 0xFF34D399.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val BAD = 0xFFF87171.toInt()

    val MONO: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    val MONO_BOLD: Typeface = Typeface.create("monospace", Typeface.BOLD)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun background() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
}

/** View builders in Friday's look, for screens made in code. */
class NeoKit(private val ctx: Context) {

    fun px(dp: Int) = (dp * ctx.resources.displayMetrics.density).roundToInt()
    fun pxf(dp: Float) = dp * ctx.resources.displayMetrics.density

    fun rounded(color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        cornerRadius = pxf(radiusDp.toFloat())
        setColor(color)
        stroke?.let { setStroke(px(strokeDp), it) }
    }

    fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(16))
        background = rounded(Neo.CARD, 22, Neo.CARD_STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(14) }
    }

    fun inset() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(12), px(14), px(12))
        background = rounded(Neo.INSET, 16, 0x225CE1FF)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6); bottomMargin = px(6) }
    }

    /** A monospace tag ("// DEVICE") above a light title. */
    fun heading(title: String, tag: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, px(8))
        if (tag != null) addView(TextView(ctx).apply {
            text = "// " + tag.uppercase()
            textSize = 10.5f
            letterSpacing = 0.18f
            typeface = Neo.MONO_BOLD
            setTextColor(Neo.CYAN)
        })
        addView(TextView(ctx).apply {
            text = title
            textSize = 22f
            typeface = Neo.LIGHT
            setTextColor(Neo.TEXT)
            setPadding(0, px(2), 0, 0)
        })
    }

    fun text(value: CharSequence, size: Float = 14f, color: Int = Neo.BODY, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = when {
            mono && bold -> Neo.MONO_BOLD
            mono -> Neo.MONO
            bold -> Neo.MEDIUM
            else -> Typeface.DEFAULT
        }
        setLineSpacing(0f, 1.12f)
        setPadding(0, px(3), 0, px(3))
    }

    fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14.5f
        typeface = Neo.MEDIUM
        gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFF021018.toInt() else Neo.CYAN)
        setPadding(px(16), px(13), px(16), px(13))
        background = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Neo.CYAN, 0xFF60A5FA.toInt())).apply { cornerRadius = pxf(999f) }
        } else {
            rounded(0x1A5CE1FF, 999, 0x665CE1FF)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) }
    }

    /** A small pill: [on] lights it up. */
    fun chip(label: String, on: Boolean = false, onClick: (() -> Unit)? = null) = TextView(ctx).apply {
        text = label
        textSize = 12.5f
        typeface = Neo.MONO
        setTextColor(if (on) 0xFF021018.toInt() else Neo.BODY)
        setPadding(px(12), px(7), px(12), px(7))
        background = if (on) rounded(Neo.CYAN, 999) else rounded(0x14FFFFFF, 999, 0x335CE1FF)
        onClick?.let { c -> setOnClickListener { c() } }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = px(6); topMargin = px(6) }
    }

    /** A setting: title, a line of explanation, and a switch. */
    fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Neo.CYAN, 0xFF8AA0B4.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x805CE1FF.toInt(), 0x40FFFFFF))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, px(8))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, Neo.TEXT))
                sub?.let { addView(text(it, 12.5f, Neo.MUTED)) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
            tag = sw
        }
    }

    fun input(hint: String, multiLine: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(Neo.MUTED)
        setTextColor(Neo.TEXT)
        textSize = 15f
        if (!multiLine) isSingleLine = true else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setPadding(px(14), px(12), px(14), px(12))
        background = rounded(0x260B2A48, 14, 0x445CE1FF)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6) }
    }

    /** A status dot and label: green when [ok]. */
    fun dot(ok: Boolean?): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when (ok) { true -> Neo.OK; false -> Neo.BAD; null -> Neo.MUTED })
        }
        layoutParams = LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(10) }
    }

    fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }
}
