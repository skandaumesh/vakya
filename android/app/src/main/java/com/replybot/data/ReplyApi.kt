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

    /** Returns the style card JSON to store as-is and send back with each suggestion. */
    fun styleCard(myMessages: List<String>): String =
        request("POST", "/style-card", JSONObject().put("my_messages", JSONArray(myMessages))).toString()

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
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
