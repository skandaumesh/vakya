package com.replybot.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.replybot.R
import com.replybot.data.STYLES
import com.replybot.data.SuggestResult
import com.replybot.data.Suggestion
import kotlin.math.roundToInt

/**
 * The bubble above the message box and the suggestion panel.
 * Both are accessibility-overlay windows that never take focus, so the
 * keyboard stays open and the chat app keeps the text cursor.
 */
class Overlay(private val ctx: Context, private val cb: Callbacks) {

    interface Callbacks {
        fun onBubbleTap()
        fun onSuggestionTap(s: Suggestion)
        fun onStyleChange(style: String)
        fun onPanelClose()
        fun onAiRequested()
    }

    private companion object {
        /** Own reply + 3 AI options; more would push the panel off the screen. */
        const val MAX_CARDS = 4
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    // Read each time the panel is built, so switching the phone's dark mode applies at once.
    // Same light/dark palette as the main screen.
    private val night get() = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val bg get() = if (night) 0xF71E293B.toInt() else 0xF7FFFFFF.toInt()
    private val fg get() = if (night) 0xFFE2E8F0.toInt() else 0xFF0F172A.toInt()
    private val muted get() = if (night) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
    private val chipBg get() = if (night) 0xFF0F172A.toInt() else 0xFFF1F5F9.toInt()
    private val border get() = if (night) 0xFF334155.toInt() else 0xFFE2E8F0.toInt()
    private val accent get() = if (night) 0xFF818CF8.toInt() else 0xFF4F46E5.toInt()

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var styleRow: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var footer: TextView
    private var anchor = Rect()

    val isShowing get() = bubble != null || panel != null
    val isPanelShown get() = panel != null

    private fun px(dp: Int) = (dp * density).roundToInt()

    private fun windowParams() = WindowManager.LayoutParams(
        WRAP_CONTENT,
        WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = px(radiusDp).toFloat()
        setColor(color)
        stroke?.let { setStroke(px(1), it) }
    }

    // ---------- Bubble ----------

    fun showBubble(input: Rect) {
        if (panel != null) return
        val size = px(44)  // big enough for the wordmark to read
        val x = input.right - size - px(4)
        val y = input.top - size - px(8)
        bubble?.let { view ->
            val p = bubbleParams ?: return
            if (p.x != x || p.y != y) {
                p.x = x
                p.y = y
                wm.updateViewLayout(view, p)
            }
            return
        }
        // A perfect circle: white disc, the Vakya logo zoomed to fill it and cropped
        // round, and a thin ring on top so it stands out on any chat wallpaper.
        val view = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())  // white in both modes: the logo is drawn for white
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND  // the oval background
            clipToOutline = true
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(px(2), if (night) 0xFF818CF8.toInt() else 0xFF4F46E5.toInt())
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.mipmap.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // The logo layer has launcher padding around the wordmark; zoom past it.
                scaleX = 1.45f
                scaleY = 1.45f
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            contentDescription = "Vakya: suggest replies"
            setOnClickListener { cb.onBubbleTap() }
        }
        val p = windowParams().apply {
            width = size
            height = size
            this.x = x
            this.y = y
        }
        wm.addView(view, p)
        bubble = view
        bubbleParams = p
    }

    fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    // ---------- Panel ----------

    fun showPanel(input: Rect, screen: Rect) {
        anchor = Rect(input)
        hideBubble()
        if (panel == null) buildPanel(screen)
        place()
    }

    fun movePanel(input: Rect) {
        if (panel == null || input == anchor) return
        anchor = Rect(input)
        place()
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
    }

    fun hideAll() {
        hideBubble()
        hidePanel()
    }

    /** The style menu, with [style] highlighted. (Who the person is, Vakya works out itself.) */
    fun setHeader(style: String) {
        if (panel == null) return
        styleRow.removeAllViews()
        STYLES.forEach { (value, label) ->
            styleRow.addView(pill(label, selected = value == style) { cb.onStyleChange(value) }, pillMargins())
        }
    }

    /** [pinned]: the user's own past reply, shown while the AI works on more. */
    fun showLoading(pinned: List<Suggestion> = emptyList()) =
        setBody(pinned.map(::card) + note(if (pinned.isEmpty()) "Reading the chat…" else "Asking AI for more…"), footerText = "")

    fun showError(message: String, pinned: List<Suggestion> = emptyList()) =
        setBody(pinned.map(::card) + note(message), footerText = "")

    fun showResult(r: SuggestResult, pinned: List<Suggestion> = emptyList()) {
        val language = r.language.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        // What Vakya understood, first: if this is wrong, the replies will be too.
        val understood = r.meaning.takeIf { it.isNotBlank() }?.let { listOf(note("💬 $it")) }.orEmpty()
        setBody(understood + (pinned + r.suggestions).take(MAX_CARDS).map(::card), footerText = "${r.intent.lowercase().replace('_', ' ')}$language")
    }

    /** Suggestions made on the phone (reply bank + quick replies); [note] explains why, e.g. an AI error. */
    fun showOffline(items: List<Suggestion>, note: String? = null) =
        setBody(items.take(MAX_CARDS).map(::card) + listOfNotNull(note?.let(::note)), footerText = "offline suggestions · no AI")

    /** Only the user's own past replies (no AI call), with a way to ask the AI anyway. */
    fun showOwnReplies(items: List<Suggestion>) =
        setBody(items.map(::card) + aiButton(), footerText = "from your chats · no AI used")

    private fun card(s: Suggestion): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(8), px(12), px(10))
        background = rounded(chipBg, 12)
        addView(TextView(ctx).apply {
            text = s.label
            textSize = 11f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(ctx).apply {
            text = s.text
            textSize = 15f
            setTextColor(fg)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { cb.onSuggestionTap(s) }
    }

    private fun aiButton(): View = TextView(ctx).apply {
        text = "✨ More ideas from AI"
        textSize = 14f
        setTextColor(accent)
        gravity = Gravity.CENTER
        setPadding(px(12), px(10), px(12), px(10))
        background = rounded(bg, 12, stroke = accent)
        setOnClickListener { cb.onAiRequested() }
    }

    private fun setBody(views: List<View>, footerText: String) {
        if (panel == null) return
        body.removeAllViews()
        views.forEach { v ->
            body.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = px(6)
            })
        }
        footer.text = footerText
        footer.visibility = if (footerText.isEmpty()) View.GONE else View.VISIBLE
        place()
    }

    private fun buildPanel(screen: Rect) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
            background = rounded(bg, 16, stroke = border)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoView = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(px(22), px(22)).apply {
                marginEnd = px(8)
            }
        }
        header.addView(logoView)
        header.addView(TextView(ctx).apply {
            text = "Vakya"
            textSize = 14f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(muted)
            setPadding(px(14), px(4), px(4), px(4))
            contentDescription = "Close"
            setOnClickListener { cb.onPanelClose() }
        })
        root.addView(header)

        // Theme / Tone Label
        val themeLabel = TextView(ctx).apply {
            text = "THEME / TONE:"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(px(2), px(6), 0, px(2))
        }
        root.addView(themeLabel)

        styleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // Five style pills may not fit a narrow phone; let the row scroll sideways.
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(styleRow)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT))

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)

        footer = TextView(ctx).apply {
            textSize = 11f
            setTextColor(muted)
            setPadding(px(2), px(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(footer)

        val p = windowParams().apply {
            width = screen.width() - px(16)
            x = screen.left + px(8)
            y = 0
        }
        wm.addView(root, p)
        panel = root
        panelParams = p
    }

    /** Sit just above the message box (and its bubble), growing upwards. */
    private fun place() {
        val view = panel ?: return
        val p = panelParams ?: return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val y = (anchor.top - view.measuredHeight - px(8)).coerceAtLeast(px(32))
        if (p.y != y) {
            p.y = y
            wm.updateViewLayout(view, p)
        }
    }

    private fun pillMargins() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = px(6) }

    private fun pill(label: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else fg)
        background = rounded(if (selected) accent else chipBg, 999)
        setPadding(px(12), px(5), px(12), px(5))
        setOnClickListener { onClick() }
    }

    private fun note(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(muted)
        setPadding(px(2), px(6), px(2), px(4))
    }
}
