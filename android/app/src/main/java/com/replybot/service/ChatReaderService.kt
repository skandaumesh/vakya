package com.replybot.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.replybot.bank.ReplyBank
import com.replybot.data.ChatMsg
import com.replybot.data.ContactRecord
import com.replybot.data.NON_FRIEND
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.data.Suggestion
import com.replybot.offline.QuickReplies
import java.util.concurrent.Executors

class ChatReaderService : AccessibilityService(), Overlay.Callbacks {

    companion object {
        /** Package -> app name sent to the backend. Keep in sync with accessibility_config.xml. */
        val SUPPORTED = mapOf(
            "com.whatsapp" to "whatsapp",
            "com.whatsapp.w4b" to "whatsapp",
            "org.telegram.messenger" to "telegram",
            "com.instagram.android" to "instagram",
            "com.google.android.apps.messaging" to "sms",
        )
        private const val REFRESH_DELAY_MS = 200L
        private const val WATCHDOG_MS = 800L

        /** Label on suggestions that are the user's own past replies. */
        const val OWN_REPLY_LABEL = "Your reply"

        /** Time for the chat list to settle after jumping to the newest message. */
        private const val SCROLL_SETTLE_MS = 450L

        /** The running service, so the settings screen can apply a new choice of apps at once. */
        @Volatile
        var running: ChatReaderService? = null
            private set

        private const val FOLLOW_UP_OFFLINE =
            "Your message is the last one, so there's nothing new to reply to. Follow-up ideas need AI."
    }

    /** The chat the panel is showing suggestions for. */
    private data class Session(val pkg: String, val key: String, val title: String, val messages: List<ChatMsg>, val draft: String)

    private lateinit var store: Store
    private lateinit var bank: ReplyBank
    private lateinit var overlay: Overlay
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var requestSeq = 0
    private var lastTyped = ""
    private var lastInserted: String? = null

    private val refresh = Runnable { refreshOverlay() }

    // We only get events from chat apps, so this notices when the user leaves them.
    private val watchdog = object : Runnable {
        override fun run() {
            refreshOverlay()
            if (overlay.isShowing) main.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onServiceConnected() {
        store = Store.get(this)
        bank = ReplyBank.get(this)
        overlay = Overlay(this, this)
        running = this
        applyAppChoice()
    }

    /**
     * Tell Android to send Vakya events only from the apps the user picked, so the others
     * never reach it. Called on start and whenever the choice changes.
     */
    fun applyAppChoice() {
        val info = serviceInfo ?: return
        val chosen = SUPPORTED.keys.filter { it in store.enabledApps }
        // An empty list means "every app" to Android: with nothing picked, listen to Vakya only.
        info.packageNames = chosen.ifEmpty { listOf(packageName) }.toTypedArray()
        serviceInfo = info
        main.post(refresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED || pkg !in store.enabledApps) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) trackTyping(event, pkg)
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, REFRESH_DELAY_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (running === this) running = null
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hideAll()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- Bubble placement ----------

    private fun chatInput(): Pair<String, AccessibilityNodeInfo>? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg !in SUPPORTED || pkg !in store.enabledApps) return null
        val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (ChatReader.isChatInput(input, pkg)) pkg to input else null
    }

    private fun refreshOverlay() {
        val found = chatInput()
        if (found == null) {
            overlay.hideAll()
            session = null
            requestSeq++
            return
        }
        val wasShowing = overlay.isShowing
        val anchor = ChatReader.bounds(found.second)
        overlay.showBubble(anchor)
        overlay.movePanel(anchor)
        if (!wasShowing) {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    // ---------- Suggestions ----------

    override fun onBubbleTap() = readAndOpen(scrolled = false)

    private fun readAndOpen(scrolled: Boolean) {
        val root = rootInActiveWindow ?: return
        val (pkg, input) = chatInput() ?: return
        // Make sure the newest messages are on screen before reading them.
        if (!scrolled && ChatReader.scrollToNewest(root, pkg)) {
            main.postDelayed({ readAndOpen(scrolled = true) }, SCROLL_SETTLE_MS)
            return
        }
        val screen = ChatReader.bounds(root)
        val snap = ChatReader.read(root, input, pkg, screen)
        val title = snap.title ?: "Unknown chat"
        val draft = ChatReader.draftText(input).trim()
        val inputRect = ChatReader.bounds(input)

        val open = { messages: List<ChatMsg> ->
            val s = Session(pkg, Store.key(pkg, title), title, messages, draft)
            session = s
            overlay.showPanel(inputRect, screen)
            when {
                // Text in the box: write what the user means, in every style.
                draft.isNotEmpty() -> requestCompose()
                messages.isEmpty() -> {
                    updateHeader()
                    overlay.showError("Nothing on screen to reply to. Tip: type what you want to say in the box, or ask for something (\"pickup line\", \"bday wish\"), then tap Vakya.")
                }
                else -> {
                    updateHeader()
                    requestSuggestions()
                }
            }
        }

        // The newest photo/sticker they sent, so the AI can see it: anywhere in their
        // latest turn (after my last message), else among the last few items. Captured
        // before the panel opens, because the panel would cover it.
        val lastMine = snap.messages.indexOfLast { it.fromMe }
        val theirMedia = snap.messages.indices.filter { snap.messages[it].media != null && !snap.messages[it].fromMe }
        val targets = (theirMedia.filter { it > lastMine }.ifEmpty { theirMedia.filter { it >= snap.messages.size - 6 } })
            .takeLast(1)
        if (targets.isEmpty() || !store.seeMedia || !MediaCapture.supported) {
            open(snap.messages)
            return
        }
        overlay.hideBubble()
        MediaCapture.capture(this, targets.map { snap.rects[it] }, io) { images ->
            // Counts only, never content: lets `adb logcat -s Vakya` show why a photo wasn't seen.
            Log.i("Vakya", "media on screen=${theirMedia.size} captured=${images.count { it != null }}/${targets.size}")
            val withImages = snap.messages.toMutableList()
            targets.zip(images).forEach { (i, img) -> if (img != null) withImages[i] = withImages[i].copy(image = img) }
            main.post { open(withImages) }
        }
    }

    private fun updateHeader() = overlay.setHeader(store.style)

    private fun requestSuggestions(forceAi: Boolean = false) {
        val s = session ?: return
        val contact = store.contact(s.key)
        val seq = ++requestSeq

        // Their message to answer; null when my message is the last one (follow-up mode:
        // their older messages were already answered, so never match or reply to those).
        val query = turnToAnswer(s.messages)
        val followUp = query == null

        // First the user's own past replies: free, instant, offline, and exactly their voice.
        // Only for "Mine"; the other styles are deliberately not the user's voice.
        val relationship = contact.relationship ?: contact.guessedRelationship
        val own = if (store.style == "mine" && query != null) {
            bank.search(query, s.key, onlyThisChat = relationship in NON_FRIEND)
        } else {
            emptyList()
        }
        val ownCards = own.map { Suggestion(OWN_REPLY_LABEL, it.reply) }

        // AI switched off: everything comes from the phone (no server, nothing sent anywhere).
        if (!store.useAi) {
            if (followUp) overlay.showOffline(emptyList(), FOLLOW_UP_OFFLINE)
            else overlay.showOffline(offlineSuggestions(query, ownCards, contact))
            return
        }
        // A near-identical short message ("gm", "thanks da"): my usual reply, no AI call.
        if (!forceAi && query != null && own.isNotEmpty() && ReplyBank.answersWithoutAi(query, own.first().score)) {
            overlay.showOwnReplies(ownCards)
            return
        }
        // Otherwise the AI answers; a close past reply of mine is pinned on top.
        val pinned = own.filter { it.score >= ReplyBank.strongScoreFor(query.orEmpty()) }
            .take(1).map { Suggestion(OWN_REPLY_LABEL, it.reply) }
        overlay.showLoading(pinned)

        val body = ReplyApi.suggestBody(
            app = SUPPORTED.getValue(s.pkg),
            title = s.title,
            messages = s.messages,
            relationship = contact.relationship,
            style = store.style,
            styleCardJson = store.styleCardJson,
            examples = contact.examples.toList(),
            memory = contact.memory.toList(),
            draft = s.draft,
            // How I answered similar messages before: teaches the AI my exact words and
            // Kannada spellings. A looser match than for direct suggestions, since these
            // are only examples.
            similar = if (store.style == "mine" && query != null) {
                bank.search(query, s.key, onlyThisChat = relationship in NON_FRIEND, limit = 6, minScore = 0.3)
                    .map { it.prompt to it.reply }
            } else {
                emptyList()
            },
        )
        val api = store.api()
        io.execute {
            val result = runCatching { api.suggest(body) }
            main.post {
                if (seq != requestSeq || session?.key != s.key) return@post
                result
                    .onSuccess { r ->
                        store.applyResult(s.key, r)
                        updateHeader()
                        overlay.showResult(r, pinned, followUp)
                    }
                    // AI out of quota, server off, no internet...: still offer something useful,
                    // and say plainly that these are basic replies.
                    .onFailure {
                        val why = ReplyApi.describe(it)
                        if (followUp) overlay.showOffline(emptyList(), "$why $FOLLOW_UP_OFFLINE")
                        else overlay.showOffline(offlineSuggestions(query, ownCards, contact), "$why Showing basic offline replies instead.")
                    }
            }
        }
    }

    /** Write it for me: the user's own words in the box, written out in every style. */
    private fun requestCompose() {
        val s = session ?: return
        val contact = store.contact(s.key)
        val seq = ++requestSeq
        overlay.setComposeHeader()
        if (!store.useAi) {
            overlay.showError("Writing it for you needs AI. Turn on \"Use AI\" in Vakya.")
            return
        }
        overlay.showLoading(text = "Writing it in every style…")
        val body = ReplyApi.composeBody(
            app = SUPPORTED.getValue(s.pkg),
            title = s.title,
            messages = s.messages,
            relationship = contact.relationship ?: contact.guessedRelationship,
            styleCardJson = store.styleCardJson,
            examples = contact.examples.toList(),
            memory = contact.memory.toList(),
            intent = s.draft,
        )
        val api = store.api()
        io.execute {
            val result = runCatching { api.compose(body) }
            main.post {
                if (seq != requestSeq || session?.key != s.key) return@post
                result
                    .onSuccess { overlay.showCompose(it) }
                    .onFailure {
                        // An older Vakya server has no write-it-for-me yet: give replies instead.
                        if (it is ReplyApi.HttpError && it.code == 404) onRepliesInstead()
                        else overlay.showError(ReplyApi.describe(it))
                    }
            }
        }
    }

    /** From write-it-for-me back to replies; what's typed is kept and the replies finish it. */
    override fun onRepliesInstead() {
        updateHeader()
        requestSuggestions()
    }

    /** The user's own past replies first, then built-in quick replies for the message's intent. */
    private fun offlineSuggestions(query: String?, own: List<Suggestion>, contact: ContactRecord): List<Suggestion> {
        if (query == null) return own
        val relationship = contact.relationship ?: contact.guessedRelationship
        val formal = relationship in NON_FRIEND || store.style == "professional"
        val register = QuickReplies.registerFor(query, contact.examples.takeLast(10), formal)
        return (own.take(2) + QuickReplies.suggest(query, register)).distinctBy { it.text.trim().lowercase() }
    }

    override fun onAiRequested() = requestSuggestions(forceAi = true)

    /** Their newest messages, if the last message is theirs (else null: nothing new to answer). */
    private fun turnToAnswer(messages: List<ChatMsg>): String? =
        if (messages.lastOrNull()?.fromMe == false) theirLatestTurn(messages) else null

    /**
     * Their newest messages before my last one, as one line. For learning what I sent:
     * by the time the box clears, my new message may already be on screen.
     */
    private fun theirLatestTurn(messages: List<ChatMsg>): String? {
        val upToTheirs = messages.dropLastWhile { it.fromMe }
        val turn = upToTheirs.takeLastWhile { !it.fromMe }.takeLast(3)
        return turn.joinToString(" ") { m ->
            listOfNotNull(m.media?.let { "[$it]" }, m.text.takeIf { it.isNotBlank() }).joinToString(" ")
        }.takeIf { it.isNotBlank() }
    }

    override fun onSuggestionTap(s: Suggestion) {
        val input = chatInput()?.second
        val typed = input?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.text) },
        ) == true
        if (typed) {
            input?.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s.text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s.text.length)
                },
            )
            lastInserted = s.text
        } else {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Vakya", s.text))
            Toast.makeText(this, "Copied. Paste it into the message box.", Toast.LENGTH_SHORT).show()
        }
        closePanel()
    }

    override fun onStyleChange(style: String) {
        store.style = style
        updateHeader()
        requestSuggestions()
    }

    override fun onPanelClose() = closePanel()

    private fun closePanel() {
        requestSeq++
        session = null
        overlay.hidePanel()
        main.post(refresh)
    }

    // ---------- Learning from what the user sends ----------

    /**
     * Sending clears the whole message box in one step (backspacing removes one
     * character at a time), so a full clear of 2+ characters is treated as a sent
     * message. Inserted suggestions sent unchanged are skipped so the style card
     * keeps learning from the user's own writing, not the model's.
     */
    private fun trackTyping(event: AccessibilityEvent, pkg: String) {
        if (event.isPassword) return
        val before = event.beforeText?.toString() ?: lastTyped
        val clearedAll = event.addedCount == 0 && before.length >= 2 && event.removedCount >= before.length
        if (clearedAll && store.learnFromSent && before != lastInserted) {
            val root = rootInActiveWindow
            val title = root?.let { ChatReader.title(it, pkg, ChatReader.bounds(it)) }
            if (title != null) {
                val key = Store.key(pkg, title)
                store.recordSent(key, before.trim())
                // Remember what this was a reply to, for the reply bank.
                val prompt = chatInput()?.let { (p, input) ->
                    theirLatestTurn(ChatReader.read(root, input, p, ChatReader.bounds(root)).messages)
                }
                if (prompt != null) bank.add(listOf(ReplyBank.Entry(prompt, before.trim(), key)))
            }
        }
        if (clearedAll) lastInserted = null
        lastTyped = if (clearedAll) "" else event.text.joinToString("")
    }
}
