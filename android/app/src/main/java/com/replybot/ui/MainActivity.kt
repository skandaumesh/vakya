package com.replybot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.replybot.R
import com.replybot.bank.ReplyBank
import com.replybot.data.CHAT_APPS
import com.replybot.data.RELATIONSHIPS
import com.replybot.data.SLURS
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.importer.ChatExportParser
import com.replybot.service.ChatReaderService
import com.replybot.service.PanelTheme
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Setup and settings: turn the assistant on, server, style, and per-contact notes. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: Store

    private lateinit var serviceStatus: TextView
    private lateinit var restrictedHelp: LinearLayout
    private lateinit var serverStatus: TextView
    private lateinit var styleSummary: TextView
    private lateinit var rebuildButton: Button
    private lateinit var bankStatus: TextView
    private lateinit var styleStatus: TextView
    private lateinit var contactsBox: LinearLayout

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    /** Light or dark colours, following the phone's dark-mode setting. */
    /** OneZeroLabs brand colours: Vakya's main screen is always the white theme. */
    private val c = Palette.BRAND

    /** Instrument Serif, the OneZeroLabs website's heading font. */
    private val serif: Typeface by lazy { resources.getFont(R.font.instrument_serif) }

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
            }
            val logoView = ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                setPadding(px(4), px(4), px(4), px(4))
                background = GradientDrawable().apply {
                    cornerRadius = px(16).toFloat()
                    setColor(c.inset)
                    setStroke(px(1), c.cardStroke)
                }
                layoutParams = LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(16) }
            }
            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Vakya"
                    textSize = 38f
                    typeface = serif
                    setTextColor(c.text)
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "AI REPLY ASSISTANT · BY ONEZEROLABS"
                    textSize = 10.5f
                    letterSpacing = 0.14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c.badgeText)
                    setPadding(0, px(4), 0, 0)
                })
            }
            headerRow.addView(logoView)
            headerRow.addView(titleCol)
            addView(headerRow)
            addView(TextView(this@MainActivity).apply {
                text = "Reply suggestions in your own style, right inside your chats."
                textSize = 15f
                setTextColor(c.body)
                setLineSpacing(0f, 1.15f)
                setPadding(0, px(14), 0, 0)
            })
        }
        col.addView(headerCard)

        // 1. Assistant Card
        val card1 = glassCard().apply {
            addView(cardHeading("Turn on the assistant", "01 ASSISTANT"))
            serviceStatus = text("", 14f)
            addView(serviceStatus)
            addView(glassButton("Turn on in Accessibility settings", primary = true) { showDisclosure() })
            // Android 13+ greys out Accessibility for apps installed from an APK file
            // ("Restricted setting") until the user allows it in the app's info page.
            restrictedHelp = glassInset().apply {
                addView(text("Switch greyed out, or it says \"Restricted setting\"?", 14f, bold = true))
                addView(text(
                    "Android blocks this for apps installed from a file until you allow it:\n" +
                        "1. Tap \"Open Vakya's app info\" below.\n" +
                        "2. Tap ⋮ (top right) → \"Allow restricted settings\" and confirm with your PIN or fingerprint.\n" +
                        "3. Come back and tap \"Turn on in Accessibility settings\" again.\n\n" +
                        "No \"Allow restricted settings\" in the ⋮ menu? Try switching Vakya on in Accessibility once " +
                        "first (so the \"Restricted setting\" message appears), then look again.",
                    13f, muted = true,
                ))
                addView(glassButton("Open Vakya's app info", primary = false) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                })
            }
            addView(restrictedHelp)
        }
        col.addView(card1)

        // Privacy, right where people decide to switch Vakya on. Every line must stay true:
        // check the server's logging, the accessibility scope and the AI providers before editing.
        val privacyCard = glassCard().apply {
            addView(cardHeading("Your chats stay yours", "PRIVACY"))
            addView(text(
                "• Nobody at Vakya reads your chats. The server writes your replies and forgets the messages " +
                    "straight away: it never saves or logs them. The code is public, so anyone can check: " +
                    "github.com/skandaumesh/vakya\n\n" +
                    "• Vakya only works in the chat apps you tick below (WhatsApp, until you choose). Android " +
                    "doesn't tell it about any other app, and it never reads anything else: not your bank, " +
                    "photos or passwords.\n\n" +
                    "• It reads a chat only when you tap the bubble, and it never sends a message for you. " +
                    "(With \"Learn from messages I send\" on, it also keeps what you send, on this phone only.)\n\n" +
                    "• Your style, saved replies and notes stay on this phone. They aren't backed up anywhere, and " +
                    "uninstalling Vakya deletes them.\n\n" +
                    "• To write replies, the messages on screen go to an AI service: Groq, or Google Gemini for " +
                    "Kannada-English chats and when Groq is busy. Gemini is Google's free tier, so Google may use " +
                    "what it receives to improve its products, and people at Google may review it. Add your own " +
                    "Groq key below and your chats never go to Google.\n\n" +
                    "• Don't use Vakya on chats with OTPs, passwords or bank details.",
                14f,
            ))
        }
        col.addView(privacyCard)

        // Which chat apps Vakya works in. Android is told to send it nothing from the rest.
        val appsCard = glassCard().apply {
            addView(cardHeading("Apps Vakya works in", "YOUR CHOICE"))
            addView(text(
                "Tick only the apps you want help in. Android won't let Vakya see the others at all. " +
                    "Vakya can never be turned on outside these chat apps.",
                13f, muted = true,
            ))
            val chosen = store.enabledApps.toMutableSet()
            CHAT_APPS.forEach { (pkg, name) ->
                addView(glassCheckBox(name, pkg in chosen) { checked ->
                    if (checked) chosen += pkg else chosen -= pkg
                    store.enabledApps = chosen.toSet()
                    ChatReaderService.running?.applyAppChoice()
                })
            }
        }
        col.addView(appsCard)

        // How the reply panel looks: a swatch per background, drawn in its own colours.
        val lookCard = glassCard().apply {
            addView(cardHeading("Panel background", "LOOK"))
            addView(text("How the reply panel looks in your chats. Auto follows your phone's dark mode.", 13f, muted = true))
            val swatches = mutableListOf<Pair<String, TextView>>()
            fun mark() = swatches.forEach { (value, view) ->
                val t = PanelTheme.resolve(value, night = false)
                val picked = value == store.panelTheme
                view.background = GradientDrawable().apply {
                    cornerRadius = px(14).toFloat()
                    setColor(t.bg)
                    setStroke(px(if (picked) 3 else 1), if (picked) c.primary else t.border)
                }
                view.text = if (picked) "✓ ${view.tag}" else view.tag as String
            }
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            PanelTheme.CHOICES.forEach { (value, label) ->
                val swatch = TextView(this@MainActivity).apply {
                    tag = label
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(PanelTheme.resolve(value, night = false).fg)
                    gravity = Gravity.CENTER
                    setPadding(px(16), px(18), px(16), px(18))
                    setOnClickListener {
                        store.panelTheme = value
                        mark()
                        toast("Panel background: $label")
                    }
                }
                swatches += value to swatch
                row.addView(swatch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = px(8) })
            }
            mark()
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6) })
        }
        col.addView(lookCard)

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
                "Use AI for new messages (off = fully offline)",
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
        val on = serviceEnabled()
        serviceStatus.text = if (on) {
            "✅ On. Open a chat in WhatsApp and tap the round Vakya bubble above the message box."
        } else {
            "Off. Vakya needs the Accessibility permission to read the chat on screen and type your reply."
        }
        restrictedHelp.visibility = if (!on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
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
                true -> c.success
                false -> c.error
                null -> c.muted
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

    /** White card: generous radius, hairline border, soft navy-tinted shadow. */
    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(20), px(20), px(20))
        background = GradientDrawable().apply {
            cornerRadius = px(24).toFloat()
            setColor(c.card)
            setStroke(px(1), c.cardStroke)
        }
        elevation = px(2).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = 0x330E1A33
            outlineAmbientShadowColor = 0x1A0E1A33
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = px(16)
        }
    }

    private fun glassInset() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(14), px(14), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
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
        textSize = 15f
        isSingleLine = true
        if (isPassword) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT  // Android switches password fields to monospace
        } else if (isUri) {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(14).toFloat()
            setColor(c.input)
            setStroke(px(1), c.inputStroke)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(8)
            bottomMargin = px(4)
        }
    }

    /** Navy pill (primary) or navy-outlined pill (secondary), flat like the website's buttons. */
    private fun glassButton(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else c.secondaryText)
        stateListAnimator = null
        setPadding(px(18), px(14), px(18), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(999).toFloat()
            if (primary) {
                setColor(c.primary)
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

    /** Gold badge ("01 ASSISTANT") above a serif title, as on onezerolabs.in. */
    private fun cardHeading(title: String, badgeText: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, px(10))
        if (badgeText != null) {
            addView(TextView(this@MainActivity).apply {
                text = badgeText.uppercase()
                textSize = 10.5f
                letterSpacing = 0.14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(c.badgeText)
                setPadding(px(10), px(4), px(10), px(4))
                background = GradientDrawable().apply {
                    cornerRadius = px(999).toFloat()
                    setColor(c.badge)
                }
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    bottomMargin = px(8)
                }
            })
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 27f
            typeface = serif
            setTextColor(c.text)
        })
    }

    private fun glassCheckBox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 14f
        setTextColor(c.body)
        buttonTintList = android.content.res.ColorStateList.valueOf(c.primary)
        isChecked = checked
        setPadding(px(8), px(8), px(8), px(8))
        setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(4)
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

/** OneZeroLabs brand colours (from onezerolabs.in): white, deep navy, slate, gold, blue. */
private class Palette(
    val bgTop: Int, val bgBottom: Int,
    val text: Int, val body: Int, val muted: Int, val hint: Int,
    val accent: Int, val link: Int, val badge: Int, val badgeText: Int,
    val card: Int, val cardStroke: Int, val inset: Int, val insetStroke: Int,
    val input: Int, val inputStroke: Int,
    val primary: Int, val secondary: Int, val secondaryStroke: Int, val secondaryText: Int,
    val success: Int, val error: Int,
) {
    companion object {
        val BRAND = Palette(
            bgTop = 0xFFFFFFFF.toInt(), bgBottom = 0xFFF8FAFC.toInt(),
            text = 0xFF0E1A33.toInt(), body = 0xFF33415C.toInt(), muted = 0xFF6E809F.toInt(), hint = 0xFF94A3B8.toInt(),
            accent = 0xFF0E1A33.toInt(), link = 0xFF2563EB.toInt(),
            badge = 0xFFFDF4DD.toInt(), badgeText = 0xFF8A6420.toInt(),
            card = 0xFFFFFFFF.toInt(), cardStroke = 0xFFECEFF4.toInt(),
            inset = 0xFFF8FAFC.toInt(), insetStroke = 0xFFECEFF4.toInt(),
            input = 0xFFFFFFFF.toInt(), inputStroke = 0xFFE2E8F0.toInt(),
            primary = 0xFF0E1A33.toInt(), secondary = 0xFFFFFFFF.toInt(),
            secondaryStroke = 0xFF0E1A33.toInt(), secondaryText = 0xFF0E1A33.toInt(),
            success = 0xFF1B9E57.toInt(), error = 0xFFE5334A.toInt(),
        )
    }
}
