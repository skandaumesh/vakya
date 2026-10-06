package com.replybot.data

import com.replybot.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device state: settings in SharedPreferences, everything about the user's
 * chats (style card, per-contact memory and examples) in one private JSON file.
 * Shared by the activity and the accessibility service (same process).
 */
class Store private constructor(private val file: File, private val prefs: SharedPreferences) {

    private val contacts = LinkedHashMap<String, ContactRecord>()

    /** The user's own messages from imports and sends, for (re)building the style card. */
    private val pool = ArrayList<String>()

    var styleCardJson: String? = null
        @Synchronized get
        private set

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    /** The user's own key, else the one built into release builds (so friends needn't type it). */
    var appKey: String?
        get() = prefs.getString("app_key", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.APP_KEY.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("app_key", v?.trim()).apply()

    /** Optional: the user's own free Groq key, for unlimited AI replies on their own quota. */
    var ownGroqKey: String?
        get() = prefs.getString("own_groq_key", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("own_groq_key", v?.trim()).apply()

    /** Random id for this phone, so the server can share the free AI fairly between phones. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun api() = ReplyApi(serverUrl, appKey, deviceId, ownGroqKey)

    /**
     * The chat apps Vakya works in, picked by the user (package names). Android is told to
     * send Vakya nothing from the others. Default: WhatsApp only.
     */
    var enabledApps: Set<String>
        get() = prefs.getStringSet("enabled_apps", null)?.toSet() ?: DEFAULT_APPS
        set(v) = prefs.edit().putStringSet("enabled_apps", v).apply()

    var style: String
        get() = prefs.getString("style", "mine")?.takeIf { s -> STYLES.any { it.first == s } } ?: "mine"  // e.g. old "friendly"
        set(v) = prefs.edit().putString("style", v).apply()

    var learnFromSent: Boolean
        get() = prefs.getBoolean("learn_from_sent", true)
        set(v) = prefs.edit().putBoolean("learn_from_sent", v).apply()

    /** Off = fully offline: reply bank + built-in quick replies, no server, nothing leaves the phone. */
    var useAi: Boolean
        get() = prefs.getBoolean("use_ai", true)
        set(v) = prefs.edit().putBoolean("use_ai", v).apply()

    /** Send a small crop of the newest photo/sticker so the AI can see what it shows. */
    var seeMedia: Boolean
        get() = prefs.getBoolean("see_media", true)
        set(v) = prefs.edit().putBoolean("see_media", v).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(v) = prefs.edit().putBoolean("dark_mode", v).apply()

    @Synchronized
    fun contact(key: String): ContactRecord = contacts.getOrPut(key) { ContactRecord() }

    @Synchronized
    fun contacts(): List<Pair<String, ContactRecord>> = contacts.map { it.key to it.value }

    @Synchronized
    fun poolSize(): Int = pool.size

    @Synchronized
    fun poolSnapshot(): List<String> = pool.toList()

    @Synchronized
    fun recordSent(key: String, text: String) {
        contact(key).examples.addCapped(text, MAX_EXAMPLES)
        pool.addCapped(text, MAX_POOL)
        save()
    }

    @Synchronized
    fun addImported(key: String, mine: List<String>) {
        val c = contact(key)
        mine.filter { it.split(' ').size >= 2 }.takeLast(MAX_EXAMPLES).forEach { c.examples.addCapped(it, MAX_EXAMPLES) }
        mine.forEach { pool.addCapped(it, MAX_POOL) }
        save()
    }

    @Synchronized
    fun applyResult(key: String, r: SuggestResult) {
        val c = contact(key)
        c.guessedRelationship = r.relationshipGuess.takeIf { it != "unknown" }
        c.memory.removeAll(r.memoryResolve.toSet())
        r.memoryAdd.filter { it !in c.memory }.forEach { c.memory.addCapped(it, MAX_MEMORY) }
        save()
    }

    @Synchronized
    fun setRelationship(key: String, relationship: String?) {
        contact(key).relationship = relationship
        save()
    }

    @Synchronized
    fun removeMemory(key: String, item: String) {
        contact(key).memory.remove(item)
        save()
    }

    @Synchronized
    fun forget(key: String) {
        contacts.remove(key)
        save()
    }

    @Synchronized
    fun setStyleCard(json: String) {
        styleCardJson = json
        save()
    }

    private fun MutableList<String>.addCapped(item: String, cap: Int) {
        add(item)
        while (size > cap) removeAt(0)
    }

    private fun save() {
        val json = JSONObject()
            .put("style_card", styleCardJson?.let { JSONObject(it) })
            .put("pool", JSONArray(pool))
            .put("contacts", JSONObject().also { all ->
                contacts.forEach { (key, c) ->
                    all.put(key, JSONObject()
                        .put("relationship", c.relationship)
                        .put("guessed_relationship", c.guessedRelationship)
                        .put("memory", JSONArray(c.memory))
                        .put("examples", JSONArray(c.examples)))
                }
            })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun load() {
        if (!file.exists()) return
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        styleCardJson = json.optJSONObject("style_card")?.toString()
        json.optJSONArray("pool")?.let { pool.addAll(it.strings()) }
        val all = json.optJSONObject("contacts") ?: return
        all.keys().forEach { key ->
            val c = all.getJSONObject(key)
            contacts[key] = ContactRecord(
                relationship = c.optStringOrNull("relationship"),
                guessedRelationship = c.optStringOrNull("guessed_relationship"),
                memory = c.optJSONArray("memory")?.strings()?.toMutableList() ?: mutableListOf(),
                examples = c.optJSONArray("examples")?.strings()?.toMutableList() ?: mutableListOf(),
            )
        }
    }

    companion object {
        /** Release builds: the hosted server; debug builds: this laptop over USB (adb reverse). */
        val DEFAULT_URL: String = BuildConfig.DEFAULT_SERVER_URL
        private const val MAX_EXAMPLES = 20
        private const val MAX_POOL = 500
        private const val MAX_MEMORY = 30

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(
                File(context.applicationContext.filesDir, "replybot.json"),
                context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE),
            ).also {
                it.load()
                instance = it
            }
        }

        fun key(pkg: String, title: String) = "$pkg|$title"

        fun appName(key: String): String = when (key.substringBefore('|')) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "org.telegram.messenger" -> "Telegram"
            "com.instagram.android" -> "Instagram"
            "com.google.android.apps.messaging" -> "Messages"
            else -> key.substringBefore('|')
        }

        fun title(key: String): String = key.substringAfter('|')
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
