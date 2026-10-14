package com.replybot.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Blocking client for the Vakya backend. Call off the main thread. */
class ReplyApi(
    baseUrl: String,
    private val appKey: String?,
    private val deviceId: String? = null,
    private val ownGroqKey: String? = null,
) {

    private val base = baseUrl.trim().trimEnd('/')

    class HttpError(val code: Int, val detail: String) : IOException("HTTP $code: $detail")

    fun health(): Boolean = request("GET", "/health", null).optBoolean("ok")

    fun suggest(body: JSONObject): SuggestResult = parseResult(request("POST", "/suggest", body))

    fun compose(body: JSONObject): ComposeResult = parseCompose(request("POST", "/compose", body))

    /** Speech to text, then the AI and maybe a compose or suggest call: allow more time. */
    fun jarvis(body: JSONObject): JarvisResult = parseJarvis(request("POST", "/jarvis", body, readTimeoutMs = 60_000))

    /** Returns the style card JSON to store as-is and send back with each suggestion. */
    fun styleCard(myMessages: List<String>): String =
        request("POST", "/style-card", JSONObject().put("my_messages", JSONArray(myMessages))).toString()

    private fun request(method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 30_000): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            appKey?.let { conn.setRequestProperty("X-Vakya-Key", it) }
            deviceId?.let { conn.setRequestProperty("X-Vakya-Device", it) }
            ownGroqKey?.let { conn.setRequestProperty("X-Groq-Key", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw HttpError(code, detail?.takeIf { it.isNotEmpty() } ?: text.take(200))
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun suggestBody(
            app: String,
            title: String,
            messages: List<ChatMsg>,
            relationship: String?,
            style: String,
            styleCardJson: String?,
            examples: List<String>,
            memory: List<String>,
            draft: String,
            similar: List<Pair<String, String>> = emptyList(),
            language: String? = null,
        ): JSONObject = JSONObject()
            .put("language", language ?: "auto")
            .put("app", app)
            .put("chat_title", title)
            .put("messages", JSONArray(messages.takeLast(40).map {
                JSONObject()
                    .put("sender", if (it.fromMe) "me" else "them")
                    .put("text", it.text.take(4000))
                    .put("media", it.media)
                    .put("image", it.image)
            }))
            .put("relationship", relationship)
            .put("style", style)
            .put("style_card", styleCardJson?.let { JSONObject(it) })
            .put("examples", JSONArray(examples.takeLast(20)))
            .put("memory", JSONArray(memory.takeLast(30)))
            .put("draft", draft.take(2000))
            .put("similar", JSONArray(similar.take(8).map { (them, me) ->
                JSONObject().put("them", them.take(600)).put("me", me.take(400))
            }))

        /** [intent]: what the user typed in the box, e.g. "ask him if he's coming tomorrow". */
        fun composeBody(
            app: String,
            title: String,
            messages: List<ChatMsg>,
            relationship: String?,
            styleCardJson: String?,
            examples: List<String>,
            memory: List<String>,
            intent: String,
            language: String? = null,
        ): JSONObject = JSONObject()
            .put("language", language ?: "auto")
            .put("app", app)
            .put("chat_title", title)
            // Context and language only: no images needed to write my own message.
            .put("messages", JSONArray(messages.takeLast(15).map {
                JSONObject()
                    .put("sender", if (it.fromMe) "me" else "them")
                    .put("text", it.text.take(4000))
                    .put("media", it.media)
            }))
            .put("relationship", relationship)
            .put("style_card", styleCardJson?.let { JSONObject(it) })
            .put("examples", JSONArray(examples.takeLast(20)))
            .put("memory", JSONArray(memory.takeLast(30)))
            .put("intent", intent.take(1000))

        /** [audio]: a WAV of the command; [context]: what's on screen. */
        fun jarvisBody(
            audio: ByteArray,
            context: JarvisContext,
            relationship: String?,
            styleCardJson: String?,
            style: String,
            examples: List<String>,
            memory: List<String>,
            language: String?,
            localTime: String,
            history: List<Pair<String, String>> = emptyList(),
            notes: List<String> = emptyList(),
            profile: List<String> = emptyList(),
            notificationsAllowed: Boolean = false,
            notifications: List<com.replybot.jarvis.NotificationLog.Item> = emptyList(),
            wake: Boolean = false,
        ): JSONObject = JSONObject()
            .put("wake", wake)
            .put("profile", JSONArray(profile.takeLast(80)))
            .put("notifications_allowed", notificationsAllowed)
            .put("notifications", JSONArray(notifications.take(30).map {
                JSONObject()
                    .put("app", it.app.take(40))
                    .put("who", it.who.take(100))
                    .put("text", it.text.take(400))
                    .put("when", com.replybot.jarvis.NotificationLog.whenSaid(it.time))
            }))
            .put("history", JSONArray(history.takeLast(6).map { (heard, say) ->
                JSONObject().put("heard", heard.take(500)).put("say", say.take(500))
            }))
            .put("notes", JSONArray(notes.takeLast(30)))
            .put("audio", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP))
            .put("app", context.app)
            .put("in_chat", context.inChat)
            .put("chat_title", context.chatTitle)
            .put("messages", JSONArray(context.messages.takeLast(15).map {
                JSONObject()
                    .put("sender", if (it.fromMe) "me" else "them")
                    .put("text", it.text.take(4000))
                    .put("media", it.media)
            }))
            .put("relationship", relationship)
            .put("style", style)
            .put("style_card", styleCardJson?.let { JSONObject(it) })
            .put("examples", JSONArray(examples.takeLast(20)))
            .put("memory", JSONArray(memory.takeLast(30)))
            .put("language", language ?: "auto")
            .put("local_time", localTime)

        fun parseJarvis(j: JSONObject) = JarvisResult(
            heard = j.optString("heard"),
            action = j.optString("action", "answer"),
            say = j.optString("say"),
            compose = j.optJSONObject("compose")?.let(::parseCompose),
            suggest = j.optJSONObject("suggest")?.let(::parseResult),
            task = j.optJSONObject("task")?.let {
                DeviceTask(
                    kind = it.optString("kind", "none"),
                    time = it.optString("time"),
                    seconds = it.optInt("seconds"),
                    label = it.optString("label"),
                    app = it.optString("app"),
                    contact = it.optString("contact"),
                    text = it.optString("text"),
                    on = it.optBoolean("on", true),
                    target = it.optString("target"),
                )
            },
            remember = j.optJSONArray("remember")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            forget = j.optJSONArray("forget")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
        )

        fun parseCompose(j: JSONObject): ComposeResult {
            val ideas = j.optString("kind") == "ideas"
            val variants = if (ideas) {
                val arr = j.optJSONArray("ideas") ?: JSONArray()
                (0 until arr.length()).map {
                    val v = arr.getJSONObject(it)
                    Suggestion(v.optString("label", "Idea"), v.getString("text"))
                }
            } else {
                val labels = STYLES.toMap()
                val arr = j.getJSONArray("variants")
                (0 until arr.length()).mapNotNull {
                    val v = arr.getJSONObject(it)
                    val label = labels[v.optString("style")] ?: return@mapNotNull null
                    Suggestion(label, v.getString("text"))
                }
            }
            return ComposeResult(
                meaning = j.optString("meaning"),
                ideas = ideas,
                language = j.optString("language"),
                variants = variants,
                latencyMs = j.optInt("latency_ms"),
            )
        }

        fun parseResult(j: JSONObject): SuggestResult {
            val suggestions = j.getJSONArray("suggestions").let { arr ->
                (0 until arr.length()).map {
                    val s = arr.getJSONObject(it)
                    Suggestion(s.getString("label"), s.getString("text"))
                }
            }
            return SuggestResult(
                meaning = j.optString("meaning"),
                intent = j.optString("intent"),
                relationshipGuess = j.optString("relationship_guess", "unknown"),
                language = j.optString("language"),
                suggestions = suggestions,
                memoryAdd = j.optJSONArray("memory_add")?.strings().orEmpty(),
                memoryResolve = j.optJSONArray("memory_resolve")?.strings().orEmpty(),
                latencyMs = j.optInt("latency_ms"),
            )
        }

        fun describe(t: Throwable): String = when (t) {
            is HttpError -> when (t.code) {
                401 -> "Wrong app key. Check it in Vakya settings."
                429 -> t.detail.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                    ?: "Free AI limit reached. Try again in a minute."
                503 -> "Server setup problem: ${t.detail}"
                else -> "Server error ${t.code}: ${t.detail}"
            }
            is SocketTimeoutException -> "That took too long, try again."
            is IOException -> "Can't reach the Vakya server. Check the address in settings."
            else -> "Something went wrong (${t.javaClass.simpleName})."
        }
    }
}
