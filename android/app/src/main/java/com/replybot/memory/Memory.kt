package com.replybot.memory

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.replybot.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Friday's long-term memory, on this phone only (a private file; not backed up, gone on uninstall).
 *
 * Holds what doesn't already live in [Store]: tasks and reminders with when they're due, routines
 * ("going to sleep: do not disturb on..."), and a short log of past conversations with Friday.
 * [searchable] gathers everything she may look things up in (these, plus what [Store] keeps about
 * the user and each chat); the server picks the few items that match a request, so the AI never
 * sees the rest. Everything here can be listed, searched, exported and deleted from Vakya.
 */
class Memory private constructor(private val context: Context, private val file: File) {

    data class Task(val id: String, val text: String, val due: Long, val done: Boolean, val created: Long, val announced: Boolean = false)
    data class Routine(val name: String, val steps: String)
    data class Exchange(val time: Long, val heard: String, val said: String)

    private val tasks = ArrayList<Task>()
    private val routines = ArrayList<Routine>()
    private val talk = ArrayList<Exchange>()

    init {
        load()
        if (!file.exists()) {
            // A start, so the Command Center shows what routines are; the user edits or deletes them.
            routines += Routine("going to sleep", "turn on do not disturb, brightness down to 10 percent, pause the music, and an alarm at my usual wake-up time (ask me if you don't know it)")
            routines += Routine("driving", "turn on Bluetooth, volume to 70 percent, and open Google Maps")
            routines += Routine("focus time", "turn on do not disturb and pause the music")
            save()
        }
    }

    // ---------- Tasks and reminders ----------

    @Synchronized
    fun openTasks(): List<Task> = tasks.filter { !it.done }.sortedWith(compareBy({ it.due == 0L }, { it.due }, { it.created }))

    @Synchronized
    fun addTask(text: String, due: Long = 0L): Task? {
        val clean = text.trim().take(200)
        if (clean.isEmpty()) return null
        tasks.firstOrNull { !it.done && it.text.equals(clean, ignoreCase = true) }?.let { old ->
            val updated = old.copy(due = if (due > 0) due else old.due, announced = false)
            tasks[tasks.indexOf(old)] = updated
            save()
            return updated
        }
        val t = Task(UUID.randomUUID().toString(), clean, due, false, System.currentTimeMillis())
        tasks += t
        trimTasks()
        save()
        return t
    }

    /** Marks the open task that best matches [said] done. Returns it, or null if none matches. */
    @Synchronized
    fun completeTask(said: String): Task? {
        val best = bestMatch(said, openTasks()) { it.text } ?: return null
        val done = best.copy(done = true)
        tasks[tasks.indexOfFirst { it.id == best.id }] = done
        save()
        return done
    }

    @Synchronized
    fun deleteTask(id: String) {
        tasks.removeAll { it.id == id }
        save()
    }

    /** Reminders due by [now] that Friday hasn't said yet; marks them said. */
    @Synchronized
    fun takeDue(now: Long = System.currentTimeMillis()): List<Task> {
        val due = tasks.filter { !it.done && !it.announced && it.due in 1..now && now - it.due < 6 * HOUR }
        if (due.isEmpty()) return due
        due.forEach { t -> tasks[tasks.indexOf(t)] = t.copy(announced = true) }
        save()
        return due
    }

    private fun trimTasks() {
        // Keep every open task and the 50 most recent done ones.
        val done = tasks.filter { it.done }.sortedByDescending { it.created }
        if (done.size > 50) tasks.removeAll(done.drop(50).toSet())
    }

    // ---------- Routines ----------

    @Synchronized
    fun routines(): List<Routine> = routines.toList()

    @Synchronized
    fun saveRoutine(name: String, steps: String) {
        val n = name.trim().lowercase().take(60)
        if (n.isEmpty() || steps.isBlank()) return
        routines.removeAll { it.name.equals(n, ignoreCase = true) }
        routines += Routine(n, steps.trim().take(500))
        save()
    }

    @Synchronized
    fun deleteRoutine(name: String) {
        routines.removeAll { it.name.equals(name, ignoreCase = true) }
        save()
    }

    // ---------- Conversations ----------

    @Synchronized
    fun logExchange(heard: String, said: String) {
        if (heard.isBlank() && said.isBlank()) return
        talk += Exchange(System.currentTimeMillis(), heard.trim().take(300), said.trim().take(300))
        while (talk.size > MAX_TALK) talk.removeAt(0)
        save()
    }

    @Synchronized
    fun conversations(): List<Exchange> = talk.toList()

    @Synchronized
    fun clearConversations() {
        talk.clear()
        save()
    }

    // ---------- Search, export, delete ----------

    /**
     * Everything Friday may look things up in, one line each. Sent with a request so the server
     * can pick the matches; what she always knows about the user (the profile) is sent separately.
     */
    fun searchable(): List<String> {
        val store = Store.get(context)
        val out = ArrayList<String>()
        store.contacts().forEach { (key, c) ->
            val who = "${Store.title(key)} (${Store.appName(key)})"
            c.relationship?.let { out += "$who is my $it" }
            c.memory.forEach { out += "$who: $it" }
        }
        synchronized(this) {
            tasks.filter { it.done }.takeLast(20).forEach { out += "Done: ${it.text}" }
            talk.takeLast(40).forEach { e ->
                out += "${stamp(e.time)} · I asked: ${e.heard} · Friday said: ${e.said}"
            }
        }
        return out.takeLast(300)
    }

    /** Lines matching [query] across everything Friday keeps, for the search box in Vakya. */
    fun search(query: String): List<String> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        val store = Store.get(context)
        val all = store.profile.map { "About you: $it" } +
            store.notes.map { "Note: $it" } +
            openTasks().map { "Task: ${it.text}" + if (it.due > 0) " (due ${stamp(it.due)})" else "" } +
            routines().map { "Routine \"${it.name}\": ${it.steps}" } +
            searchable()
        return all.map { line -> line to words.count { line.lowercase().contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(40)
    }

    /** Counts for the Command Center. */
    fun overview(): Map<String, Int> {
        val store = Store.get(context)
        return linkedMapOf(
            "About you" to store.profile.size,
            "People notes" to store.contacts().sumOf { it.second.memory.size },
            "Tasks" to openTasks().size,
            "Routines" to routines().size,
            "Conversations" to conversations().size,
        )
    }

    /** Everything Friday and Vakya keep about the user, as JSON (no keys or settings). */
    fun exportJson(): String {
        val store = Store.get(context)
        val j = JSONObject()
            .put("exported", stamp(System.currentTimeMillis()))
            .put("about_you", JSONArray(store.profile))
            .put("notes", JSONArray(store.notes))
            .put("contacts", JSONArray(store.contacts().map { (key, c) ->
                JSONObject().put("chat", Store.title(key)).put("app", Store.appName(key))
                    .put("relationship", c.relationship ?: c.guessedRelationship)
                    .put("notes", JSONArray(c.memory))
            }))
        synchronized(this) {
            j.put("tasks", JSONArray(tasks.map { t ->
                JSONObject().put("text", t.text).put("due", if (t.due > 0) stamp(t.due) else JSONObject.NULL).put("done", t.done)
            }))
            j.put("routines", JSONArray(routines.map { JSONObject().put("name", it.name).put("steps", it.steps) }))
            j.put("conversations", JSONArray(talk.map {
                JSONObject().put("time", stamp(it.time)).put("i_asked", it.heard).put("friday_said", it.said)
            }))
        }
        return j.toString(2)
    }

    /** Saves [exportJson] to Downloads. Returns where it went, or null. */
    fun exportToDownloads(): String? = runCatching {
        val name = "Friday-memory-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ENGLISH).format(Date()) + ".json"
        val bytes = exportJson().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            "Downloads/$name"
        } else {
            val f = File(context.getExternalFilesDir(null), name)
            f.writeBytes(bytes)
            f.absolutePath
        }
    }.getOrNull()

    /** Deletes everything Friday remembers: about you, notes, tasks, routines, conversations, chat notes. */
    @Synchronized
    fun deleteAll() {
        tasks.clear()
        routines.clear()
        talk.clear()
        save()
        val store = Store.get(context)
        store.updateProfile(emptyList(), store.profile)
        store.clearNotes()
        store.contacts().forEach { (key, c) -> c.memory.toList().forEach { store.removeMemory(key, it) } }
    }

    // ---------- Storage ----------

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val j = JSONObject(file.readText())
            j.optJSONArray("tasks")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    tasks += Task(optString("id"), optString("text"), optLong("due"), optBoolean("done"), optLong("created"), optBoolean("announced"))
                }
            }
            j.optJSONArray("routines")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run { routines += Routine(optString("name"), optString("steps")) }
            }
            j.optJSONArray("talk")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run { talk += Exchange(optLong("time"), optString("heard"), optString("said")) }
            }
        }
    }

    private fun save() {
        val j = JSONObject()
            .put("tasks", JSONArray(tasks.map {
                JSONObject().put("id", it.id).put("text", it.text).put("due", it.due).put("done", it.done)
                    .put("created", it.created).put("announced", it.announced)
            }))
            .put("routines", JSONArray(routines.map { JSONObject().put("name", it.name).put("steps", it.steps) }))
            .put("talk", JSONArray(talk.map { JSONObject().put("time", it.time).put("heard", it.heard).put("said", it.said) }))
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(j.toString())
            tmp.renameTo(file) || run { file.writeText(j.toString()); true }
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val MAX_TALK = 60

        @Volatile private var instance: Memory? = null

        fun get(context: Context): Memory = instance ?: synchronized(this) {
            instance ?: Memory(context.applicationContext, File(context.applicationContext.filesDir, "friday_memory.json")).also { instance = it }
        }

        fun stamp(time: Long): String = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(time))
            .replace("AM", "am").replace("PM", "pm")

        /** "2026-11-05 08:00" (as the server writes times) to millis, or 0. */
        fun parseTime(s: String): Long = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(s.trim())?.time ?: 0L
        }.getOrDefault(0L)

        /** The next time it's [hour]:[minute] (today if still ahead, else tomorrow). */
        fun nextAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }

        /** The item whose words best cover [said] (at least half of them), or null. */
        fun <T> bestMatch(said: String, items: List<T>, text: (T) -> String): T? {
            val want = said.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            if (want.isEmpty()) return items.firstOrNull { text(it).equals(said.trim(), ignoreCase = true) }
            return items.map { it to text(it).lowercase().split(Regex("\\W+")).toSet().intersect(want).size }
                .filter { it.second * 2 >= want.size && it.second > 0 }
                .maxByOrNull { it.second }?.first
        }
    }
}
