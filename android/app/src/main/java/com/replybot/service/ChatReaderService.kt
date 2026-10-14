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
import com.replybot.data.ComposeResult
import com.replybot.data.JarvisContext
import com.replybot.data.SuggestResult
import com.replybot.data.ContactRecord
import com.replybot.data.NON_FRIEND
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.data.Suggestion
import com.replybot.jarvis.ResultPicker
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
    /** The panel shows write-it-for-me results (else replies). */
    private var composing = false

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
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            trackTyping(event, pkg)
            if (overlay.isPanelShown) main.post { updateTypedHint() }
        }
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
            overlay.setLanguage(store.contact(s.key).language)
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
        composing = false

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
            language = contact.language,
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
        composing = true
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
            language = contact.language,
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

    // ---------- Jarvis ----------

    /**
     * The chat's message box, for Jarvis: the focused box, or WhatsApp's box even when the
     * keyboard is closed. Only in apps the user ticked.
     */
    private fun chatBox(): Pair<String, AccessibilityNodeInfo>? {
        chatInput()?.let { return it }
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg !in SUPPORTED || pkg !in store.enabledApps || !pkg.startsWith("com.whatsapp")) return null
        return root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()?.let { pkg to it }
    }

    /** What's on screen for Jarvis: the app, and the open chat (title and messages) if there is one. */
    fun jarvisContext(): JarvisContext {
        val root = rootInActiveWindow ?: return JarvisContext(app = "phone")
        val pkg = root.packageName?.toString().orEmpty()
        val (chatPkg, box) = chatBox() ?: return JarvisContext(app = SUPPORTED[pkg] ?: "phone")
        val snap = ChatReader.read(root, box, chatPkg, ChatReader.bounds(root))
        val title = snap.title ?: "Unknown chat"
        return JarvisContext(SUPPORTED.getValue(chatPkg), title, Store.key(chatPkg, title), snap.messages)
    }

    /** Put [text] in the open chat's message box. Never presses send. */
    fun jarvisWrite(text: String): Boolean {
        val box = chatBox()?.second ?: return false
        val ok = box.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        if (ok) lastInserted = text
        return ok
    }

    /**
     * Tap the send button of the chat on screen (WhatsApp's "send", or a button labelled Send).
     * Only called after the user said yes to sending a message Friday prepared.
     */
    fun jarvisSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser }
        val button = byId ?: findSendButton(root) ?: return false
        var node: AccessibilityNodeInfo? = button
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 800) {
            val n = stack.removeAt(stack.lastIndex)
            seen++
            val label = n.contentDescription?.toString()?.trim().orEmpty()
            if (n.isVisibleToUser && Regex("^send( message| sms| mms)?$", RegexOption.IGNORE_CASE).matches(label)) return n
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** The app now in front (its package name). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * "Play X": once [pkg] shows its search results, tap the row that matches [query]: a whole
     * result row, never a row's small buttons or the player bar at the bottom (that one plays
     * whatever was on before). Waits until the same row is best on two looks in a row, so the
     * results have finished loading. Tries for ~10 s.
     */
    fun jarvisTapResult(query: String, pkg: String, tries: Int = 0, last: String? = null) {
        val root = rootInActiveWindow
        var seen: String? = null
        if (root != null && root.packageName?.toString() == pkg) {
            val rows = resultRows(root)
            val pick = ResultPicker.pick(rows.map { it.first }, query, waited = tries >= 6)
            seen = pick?.text
            if (pick != null && seen == last && click(rows.first { it.first === pick }.second)) {
                Log.i("Vakya", "friday: played a result in $pkg")
                main.postDelayed({ pressPlayIfQuiet(pkg) }, 2500)
                return
            }
        }
        if (tries < 20) main.postDelayed({ jarvisTapResult(query, pkg, tries + 1, seen) }, 500)
        else Log.w("Vakya", "friday: found nothing to play in $pkg")
    }

    /** The results list: wide, tappable rows between the search box and the player bar, with their words. */
    private fun resultRows(root: AccessibilityNodeInfo): List<Pair<ResultPicker.Row, AccessibilityNodeInfo>> {
        val screen = ChatReader.bounds(root)
        val top = screen.top + screen.height() * 0.12
        val bottom = screen.top + screen.height() * 0.85
        val rows = mutableListOf<Pair<ResultPicker.Row, AccessibilityNodeInfo>>()
        walk(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser || n.isEditable) return@walk
            val r = ChatReader.bounds(n)
            if (r.top < top || r.top > bottom || r.width() < screen.width() * 0.6) return@walk
            val text = rowText(n)
            if (text.isNotBlank()) rows += ResultPicker.Row(r.top, text) to n
        }
        return rows
    }

    /** A row's own words: its label and the text inside it, not its buttons' ("Add to library"...). */
    private fun rowText(row: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        row.contentDescription?.let { parts += it.toString() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until row.childCount) row.getChild(i)?.let(queue::addLast)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 60) {
            val n = queue.removeFirst()
            if (n.isClickable) continue
            n.text?.let { parts += it.toString() }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return parts.joinToString("\n")
    }

    /** Tapped a result but nothing plays (an album or artist page opened): press that page's own Play. */
    private fun pressPlayIfQuiet(pkg: String) {
        if (getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val screen = ChatReader.bounds(root)
        val above = screen.top + screen.height() * 0.7  // not the player bar at the bottom
        var play: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (play != null || !n.isVisibleToUser || ChatReader.bounds(n).top > above) return@walk
            val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
            if (Regex("^play\\b").containsMatchIn(label) && !label.startsWith("playlist")) play = n
        }
        play?.let { if (click(it)) Log.i("Vakya", "friday: pressed play in $pkg") }
    }

    /** "Next reel": swipe up like a finger (or scroll the biggest list back/forward). */
    fun jarvisScroll(forward: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (from, to) = if (forward) m.heightPixels * 0.72f to m.heightPixels * 0.28f else m.heightPixels * 0.28f to m.heightPixels * 0.72f
        val path = android.graphics.Path().apply { moveTo(x, from); lineTo(x, to) }
        val swipe = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 220))
            .build()
        if (dispatchGesture(swipe, null, null)) return true
        val root = rootInActiveWindow ?: return false
        var biggest: AccessibilityNodeInfo? = null
        var area = 0
        walk(root) { n ->
            if (n.isScrollable && n.isVisibleToUser) {
                val r = ChatReader.bounds(n)
                if (r.width() * r.height() > area) {
                    area = r.width() * r.height()
                    biggest = n
                }
            }
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return biggest?.performAction(action) == true
    }

    private var autoScroll: Runnable? = null

    /** "Keep scrolling": next every [seconds], until "stop", leaving the app, or 15 minutes. */
    fun jarvisAutoScroll(seconds: Int) {
        stopAutoScroll()
        val app = foregroundPackage()
        val until = android.os.SystemClock.uptimeMillis() + 15 * 60_000L
        val tick = object : Runnable {
            override fun run() {
                if (foregroundPackage() != app || android.os.SystemClock.uptimeMillis() > until) {
                    autoScroll = null
                    return
                }
                jarvisScroll(true)
                main.postDelayed(this, seconds * 1000L)
            }
        }
        autoScroll = tick
        main.postDelayed(tick, seconds * 1000L)
    }

    fun stopAutoScroll() {
        autoScroll?.let(main::removeCallbacks)
        autoScroll = null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 1500) {
            val n = stack.removeAt(stack.lastIndex)
            seen++
            visit(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
    }

    /** Open the panel on the current chat with Friday's reply options or ideas. */
    fun jarvisShow(context: JarvisContext, suggest: SuggestResult? = null, compose: ComposeResult? = null) {
        val (pkg, box) = chatBox() ?: return
        val root = rootInActiveWindow ?: return
        val key = context.chatKey ?: return
        session = Session(pkg, key, context.chatTitle.orEmpty(), context.messages, "")
        overlay.showPanel(ChatReader.bounds(box), ChatReader.bounds(root))
        overlay.setLanguage(store.contact(key).language)
        when {
            suggest != null -> {
                composing = false
                updateHeader()
                overlay.showResult(suggest)
            }
            compose != null -> {
                composing = true
                overlay.setComposeHeader()
                overlay.showCompose(compose)
            }
        }
    }

    fun jarvisHud(state: String, caption: String? = null, thinking: Boolean = false) =
        overlay.showJarvis(state, caption, thinking)

    fun jarvisLevel(level: Float) = overlay.jarvisLevel(level)

    fun jarvisHudHide() = overlay.hideJarvis()

    /** What's in the message box right now ("" for nothing or its grey hint). */
    private fun typedNow(): String = chatInput()?.second?.let { ChatReader.draftText(it).trim() }.orEmpty()

    /**
     * Text typed after the panel opened ("roast him", "ask him if he's coming") isn't in the
     * session yet: offer to write it out. Hidden when it's what the panel already shows.
     */
    private fun updateTypedHint() {
        val s = session ?: return
        val typed = typedNow()
        overlay.showTyped(typed.takeIf { it.isNotEmpty() && !(composing && it == s.draft) })
    }

    override fun onWriteTyped() {
        val s = session ?: return
        val typed = typedNow()
        if (typed.isEmpty()) return
        session = s.copy(draft = typed)
        overlay.showTyped(null)
        requestCompose()
    }

    /** A language picked in the panel: remembered for this chat, and the panel redone in it. */
    override fun onLanguageChange(language: String) {
        val s = session ?: return
        store.setLanguage(s.key, language)
        overlay.setLanguage(store.contact(s.key).language)
        if (composing) requestCompose() else requestSuggestions()
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
        // A language picked for this chat wins over guessing from the words.
        val register = when (contact.language) {
            "english", "hinglish" -> if (formal) QuickReplies.Register.POLITE else QuickReplies.Register.ENGLISH
            "kanglish", "kannada" -> QuickReplies.Register.KANGLISH
            else -> QuickReplies.registerFor(query, contact.examples.takeLast(10), formal)
        }
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
        val input = chatBox()?.second
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
