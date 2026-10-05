package com.replybot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.replybot.R
import com.replybot.bank.ReplyBank
import com.replybot.data.RELATIONSHIPS
import com.replybot.data.SLURS
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.importer.ChatExportParser
import com.replybot.service.ChatReaderService
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Setup and settings: turn the assistant on, server, style, and per-contact notes. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: Store

    private lateinit var serviceStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var styleSummary: TextView
    private lateinit var rebuildButton: Button
    private lateinit var bankStatus: TextView
    private lateinit var styleStatus: TextView
    private lateinit var contactsBox: LinearLayout

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    /** Light or dark colours, following the phone's dark-mode setting. */
    private val c: Palette by lazy {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) Palette.DARK else Palette.LIGHT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(20), px(16), px(36))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(c.bgTop, c.bgBottom)
            }
            addView(col)
        })

        // Top Header
        val headerCard = glassCard().apply {
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, px(6))
            }
            val logoView = ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                setPadding(px(4), px(4), px(4), px(4))
                background = GradientDrawable().apply {
                    cornerRadius = px(12).toFloat()
                    setColor(0x156366F1.toInt())
                    setStroke(px(1), 0x406366F1.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(px(48), px(48)).apply {
                    rightMargin = px(14)
                }
            }
            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Vakya"
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c.text)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "AI Reply Assistant"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c.accent)
                })
            }
            headerRow.addView(logoView)
            headerRow.addView(titleCol)
            addView(headerRow)
            addView(TextView(this@MainActivity).apply {
                text = "Reply suggestions in your own style, right inside your chats."
                textSize = 13f
                setTextColor(c.muted)
                setPadding(0, px(6), 0, 0)
            })
        }
        col.addView(headerCard)

        // 1. Assistant Card
        val card1 = glassCard().apply {
            addView(cardHeading("Turn on the assistant", "01 ASSISTANT"))
            serviceStatus = text("", 14f)
            addView(serviceStatus)
            addView(glassButton("Turn on in Accessibility settings", primary = true) { showDisclosure() })
        }
        col.addView(card1)

        // 2. Server Card
        val card2 = glassCard().apply {
            addView(cardHeading("Server Connection", "02 BACKEND"))
            val url = glassInput("Server address (http://localhost:8000)", isUri = true).apply {
                setText(store.serverUrl)
            }
            val key = glassInput("App key (optional)", isPassword = true).apply {
                setText(store.appKey.orEmpty())
            }
            val groqKey = glassInput("Your own Groq key (optional)", isPassword = true).apply {
                setText(store.ownGroqKey.orEmpty())
            }
            serverStatus = text("", 13f, muted = true)
            addView(url)
            addView(key)
            addView(groqKey)
            addView(text(
                "Optional: a free key from console.groq.com/keys gives you unlimited AI replies on your own quota. Without it, you share the free daily replies.",
                12f, muted = true,
            ))
            addView(glassButton("Save and test", primary = true) {
                store.serverUrl = url.text.toString()
                store.appKey = key.text.toString()
                store.ownGroqKey = groqKey.text.toString()
                testServer()
            })
            addView(serverStatus)
        }
        col.addView(card2)

        // 3. Style Card
        val card3 = glassCard().apply {
            addView(cardHeading("Your Texting Style", "03 PERSONALIZATION"))
            val summaryInset = glassInset()
            styleSummary = text("", 14f)
            summaryInset.addView(styleSummary)
            addView(summaryInset)
            addView(text(
                "In WhatsApp, open a chat with someone you text a lot, then ⋮ → More → Export chat → Without media. Save the file and import it here.",
                13f, muted = true,
            ))
            addView(glassButton("Import a WhatsApp chat", primary = true) { pickExport() })
            rebuildButton = glassButton("", primary = false) { buildStyle() }
            addView(rebuildButton)
            styleStatus = text("", 14f).apply { visibility = android.view.View.GONE }
            addView(styleStatus)
            bankStatus = text("", 13f, muted = true)
            addView(bankStatus)
            addView(glassButton("Clear reply bank", primary = false) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Forget all your saved replies? Style and contact notes stay.")
                    .setPositiveButton("Clear") { _, _ -> ReplyBank.get(this@MainActivity).clear(); renderStyle() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            addView(glassCheckBox(
                "Use AI for new messages (needs laptop server)",
                store.useAi,
            ) { checked -> store.useAi = checked })
            addView(glassCheckBox(
                "Learn from messages I send",
                store.learnFromSent,
            ) { checked -> store.learnFromSent = checked })
            addView(glassCheckBox(
                "Look at photos and stickers",
                store.seeMedia,
            ) { checked -> store.seeMedia = checked })
        }
        col.addView(card3)

        // 4. Contacts Card
        val card4 = glassCard().apply {
            addView(cardHeading("What Vakya Remembers", "04 KNOWLEDGE"))
            contactsBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(contactsBox)
        }
        col.addView(card4)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        serviceStatus.text = if (serviceEnabled()) {
            "✅ On. Open a chat in WhatsApp and tap the ✨ bubble above the message box."
        } else {
            "Off. Vakya needs the Accessibility permission to read the chat on screen and type your reply."
        }
        renderStyle()
        renderContacts()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, ChatReaderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Prominent disclosure, required before sending the user to the Accessibility settings. */
    private fun showDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("How Vakya uses Accessibility")
            .setMessage(R.string.disclosure)
            .setPositiveButton("Agree and continue") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun testServer() {
        serverStatus.text = "Testing…"
        val api = store.api()
        io.execute {
            val result = runCatching { api.health() }
            main.post {
                serverStatus.text = result.fold(
                    onSuccess = { ok -> if (ok) "✅ Connected" else "Server answered, but not as expected." },
                    onFailure = { ReplyApi.describe(it) },
                )
            }
        }
    }

    // ---------- Style ----------

    private fun renderStyle() {
        val card = store.styleCardJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        styleSummary.text = if (card == null) {
            "No style yet. Until you import a chat, replies use a plain, natural texting style."
        } else {
            val traits = card.getJSONObject("traits")
            val fillers = traits.optJSONArray("fillers")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty().filterNot { SLURS.containsMatchIn(it) }
            buildString {
                append(traits.optString("summary").replace(SLURS, "").replace(Regex("""\s{2,}"""), " "))
                append("\n\nLanguage: ").append(traits.optString("language_mix"))
                if (fillers.isNotEmpty()) append("\nWords you use: ").append(fillers.joinToString(", "))
            }
        }
        val n = store.poolSize()
        rebuildButton.text = "Rebuild my style from $n messages"
        rebuildButton.isEnabled = n >= 5
        bankStatus.text = "Reply bank: ${ReplyBank.get(this).size()} of your own replies, kept on this phone. " +
            "Close matches are suggested instantly without AI."
    }

    private fun pickExport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Activity result API needs androidx; the framework callback is fine here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && uri != null) importExport(uri)
    }

    private fun importExport(uri: Uri) {
        val fileName = displayName(uri)
        setStatus("⏳ Reading the chat…", ok = null)
        io.execute {
            val raw = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            val messages = ChatExportParser.parse(raw)
            val contact = fileName?.let(ChatExportParser::contactFromFilename)
            main.post {
                if (messages.isEmpty()) {
                    setStatus("❌ That doesn't look like a WhatsApp chat export. Export it with \"Without media\" and pick the .txt file.", ok = false)
                    renderStyle()
                    return@post
                }
                val me = ChatExportParser.guessMe(messages, contact)
                if (me != null) finishImport(messages, me, contact) else askWhoIsMe(messages, contact)
            }
        }
    }

    private fun askWhoIsMe(messages: List<ChatExportParser.Message>, contact: String?) {
        val names = ChatExportParser.senders(messages).take(12)
        AlertDialog.Builder(this)
            .setTitle("Which one is you?")
            .setItems(names.toTypedArray()) { _, i -> finishImport(messages, names[i], contact) }
            .setOnCancelListener { renderStyle() }
            .show()
    }

    private fun finishImport(messages: List<ChatExportParser.Message>, me: String, contact: String?) {
        val mine = messages.filter { it.sender == me }.map { it.text }
        if (mine.size < 5) {
            setStatus("❌ Only ${mine.size} messages from $me. Pick a chat where you've written more.", ok = false)
            renderStyle()
            return
        }
        val title = contact ?: ChatExportParser.senders(messages).firstOrNull { it != me } ?: "Imported chat"
        val key = Store.key("com.whatsapp", title)
        store.addImported(key, mine)
        val pairs = ChatExportParser.replyPairs(messages, me)
        ReplyBank.get(this).add(pairs.map { (said, replied) -> ReplyBank.Entry(said, replied, key) })
        toast("Imported ${mine.size} of your messages and ${pairs.size} replies with $title")
        renderContacts()
        renderStyle()
        buildStyle(prefix = "✅ Imported ${mine.size} of your messages and ${pairs.size} replies with $title\n")
    }

    private fun buildStyle(prefix: String = "") {
        val sample = store.poolSnapshot().takeLast(300)
        if (sample.size < 5) {
            setStatus("❌ Need at least 5 of your messages. Import a chat first.", ok = false)
            return
        }
        setStatus("$prefix⏳ Learning your style from ${sample.size} messages… (a few seconds)", ok = null)
        rebuildButton.isEnabled = false
        val api = store.api()
        io.execute {
            val result = runCatching { api.styleCard(sample) }
            main.post {
                result
                    .onSuccess {
                        store.setStyleCard(it)
                        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                        setStatus("$prefix✅ Style updated from ${sample.size} messages at $time", ok = true)
                        toast("Style updated ✅")
                    }
                    .onFailure { setStatus("$prefix❌ Couldn't update your style: ${ReplyApi.describe(it)}", ok = false) }
                renderStyle()
            }
        }
    }

    /** One clear line saying what the last import/rebuild did (null = in progress). */
    private fun setStatus(message: String, ok: Boolean?) {
        styleStatus.text = message
        styleStatus.setTextColor(
            when (ok) {
                true -> 0xFF34D399.toInt()
                false -> 0xFFF87171.toInt()
                null -> 0xFF94A3B8.toInt()
            },
        )
        styleStatus.visibility = android.view.View.VISIBLE
    }

    // ---------- Contacts ----------

    private fun renderContacts() {
        contactsBox.removeAllViews()
        val contacts = store.contacts()
        if (contacts.isEmpty()) {
            contactsBox.addView(text("Nothing yet. Relationships and notes appear here as you use Vakya.", 13f, muted = true))
            return
        }
        contacts.forEach { (key, contact) ->
            val contactCard = glassInset().apply {
                addView(text("${Store.appName(key)} · ${Store.title(key)}", 15f, bold = true))
                val rel = contact.relationship ?: contact.guessedRelationship?.let { "$it (guessed)" } ?: "not set"
                addView(text("Relationship: $rel  ·  change", 14f).apply {
                    setTextColor(c.link)
                    setOnClickListener { chooseRelationship(key) }
                })
                if (contact.memory.isEmpty()) {
                    addView(text("No notes yet", 13f, muted = true))
                }
                contact.memory.forEach { item ->
                    addView(text("• $item", 14f).apply {
                        setOnLongClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setMessage("Forget \"$item\"?")
                                .setPositiveButton("Forget") { _, _ -> store.removeMemory(key, item); renderContacts() }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                    })
                }
                addView(text("${contact.examples.size} of your messages saved as style examples  ·  forget this chat", 12f, muted = true).apply {
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Forget everything about ${Store.title(key)}?")
                            .setPositiveButton("Forget") { _, _ -> store.forget(key); renderContacts() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
            contactsBox.addView(contactCard)
        }
        contactsBox.addView(text("Long-press a note to delete it.", 12f, muted = true).apply { setPadding(0, px(8), 0, 0) })
    }

    private fun chooseRelationship(key: String) {
        val options = RELATIONSHIPS + "let Vakya guess"
        AlertDialog.Builder(this)
            .setTitle(Store.title(key))
            .setItems(options.toTypedArray()) { _, i ->
                store.setRelationship(key, RELATIONSHIPS.getOrNull(i))
                renderContacts()
            }
            .show()
    }

    // ---------- Small view helpers ----------

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(18), px(18), px(18))
        background = GradientDrawable().apply {
            cornerRadius = px(20).toFloat()
            setColor(c.card)
            setStroke(px(1), c.cardStroke)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = px(16)
        }
    }

    private fun glassInset() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(14), px(14), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(14).toFloat()
            setColor(c.inset)
            setStroke(px(1), c.insetStroke)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(8)
            bottomMargin = px(8)
        }
    }

    private fun glassInput(hintText: String, isPassword: Boolean = false, isUri: Boolean = false) = EditText(this).apply {
        hint = hintText
        setHintTextColor(c.hint)
        setTextColor(c.text)
        textSize = 14f
        isSingleLine = true
        if (isPassword) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT  // Android switches password fields to monospace
        } else if (isUri) {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(c.input)
            setStroke(px(1), c.inputStroke)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(8)
            bottomMargin = px(8)
        }
    }

    private fun glassButton(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = if (primary) 15f else 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else c.secondaryText)
        setPadding(px(16), px(12), px(16), px(12))
        background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            if (primary) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(0xFF4F46E5.toInt(), 0xFF6366F1.toInt())
            } else {
                setColor(c.secondary)
                setStroke(px(1), c.secondaryStroke)
            }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(10)
        }
    }

    private fun cardHeading(title: String, badgeText: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, px(12))
        if (badgeText != null) {
            addView(TextView(this@MainActivity).apply {
                text = badgeText.uppercase()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(c.accent)
                setPadding(px(8), px(3), px(8), px(3))
                background = GradientDrawable().apply {
                    cornerRadius = px(6).toFloat()
                    setColor(c.badge)
                }
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    bottomMargin = px(6)
                }
            })
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.text)
        })
    }

    private fun glassCheckBox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 13f
        setTextColor(c.body)
        isChecked = checked
        setPadding(px(8), px(8), px(8), px(8))
        setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(6)
        }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(if (muted) c.muted else c.text)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, px(4), 0, px(4))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_EXPORT = 1
    }
}

/** The main screen's colours. LIGHT is the original glass design; DARK mirrors it for dark mode. */
private class Palette(
    val bgTop: Int, val bgBottom: Int,
    val text: Int, val body: Int, val muted: Int, val hint: Int,
    val accent: Int, val link: Int, val badge: Int,
    val card: Int, val cardStroke: Int, val inset: Int, val insetStroke: Int,
    val input: Int, val inputStroke: Int,
    val secondary: Int, val secondaryStroke: Int, val secondaryText: Int,
) {
    companion object {
        val LIGHT = Palette(
            bgTop = 0xFFF8FAFC.toInt(), bgBottom = 0xFFF1F5F9.toInt(),
            text = 0xFF0F172A.toInt(), body = 0xFF334155.toInt(), muted = 0xFF64748B.toInt(), hint = 0xFF94A3B8.toInt(),
            accent = 0xFF4F46E5.toInt(), link = 0xFF818CF8.toInt(), badge = 0x154F46E5,
            card = 0xD9FFFFFF.toInt(), cardStroke = 0x6094A3B8, inset = 0x66F8FAFC, insetStroke = 0x40E2E8F0,
            input = 0x80F8FAFC.toInt(), inputStroke = 0x60CBD5E1,
            secondary = 0x33F1F5F9, secondaryStroke = 0x60E2E8F0, secondaryText = 0xFF3730A3.toInt(),
        )
        val DARK = Palette(
            bgTop = 0xFF0B1120.toInt(), bgBottom = 0xFF111827.toInt(),
            text = 0xFFE2E8F0.toInt(), body = 0xFFCBD5E1.toInt(), muted = 0xFF94A3B8.toInt(), hint = 0xFF64748B.toInt(),
            accent = 0xFF818CF8.toInt(), link = 0xFFA5B4FC.toInt(), badge = 0x33818CF8,
            card = 0xCC1E293B.toInt(), cardStroke = 0x40475569, inset = 0x660F172A, insetStroke = 0x40334155,
            input = 0x800F172A.toInt(), inputStroke = 0x60475569,
            secondary = 0x331E293B, secondaryStroke = 0x60475569, secondaryText = 0xFFC7D2FE.toInt(),
        )
    }
}
