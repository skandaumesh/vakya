package com.replybot.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.replybot.data.DeviceTask
import com.replybot.data.Store
import com.replybot.service.ChatReaderService

/**
 * Jarvis's hands: does a phone task the server picked out of what you said (an alarm, a call,
 * opening an app...). Everything here runs on the phone; contacts are looked up here and never
 * sent anywhere.
 *
 * [run] returns what Jarvis should say instead of the server's line when the phone knows better
 * (it failed, or it measured something like the battery), else null.
 */
class DeviceActions(private val context: Context) {

    private val store = Store.get(context)

    fun run(task: DeviceTask): String? = try {
        when (task.kind) {
            "alarm" -> alarm(task)
            "timer" -> timer(task)
            "open_app" -> openApp(task.app)
            "call" -> call(task.contact)
            "message" -> message(task)
            "play" -> play(task)
            "navigate" -> navigate(task.text)
            "search" -> search(task.text)
            "flashlight" -> flashlight(task.on)
            "battery" -> battery()
            "phone" -> phone(task.target)
            "settings" -> settings(task.target)
            "volume" -> volume(task)
            "note_add" -> { store.addNote(task.text); null }
            "notes_clear" -> { store.clearNotes(); null }
            "send" -> if (ChatReaderService.running?.jarvisSend() == true) null else "I couldn't find the send button, boss. Tap it for me?"
            else -> null
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "jarvis task ${task.kind}: ${e.javaClass.simpleName}")
        "Android didn't let me do that, boss."
    }

    // ---------- Clock ----------

    private fun alarm(task: DeviceTask): String? {
        val (h, m) = task.time.split(":").mapNotNull { it.trim().toIntOrNull() }.let {
            if (it.size == 2 && it[0] in 0..23 && it[1] in 0..59) it[0] to it[1] else return "What time, boss?"
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (task.label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, task.label)
        return if (launch(intent)) null else "I couldn't reach your clock app, boss."
    }

    private fun timer(task: DeviceTask): String? {
        if (task.seconds !in 1..86_400) return "For how long, boss?"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, task.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (task.label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, task.label)
        return if (launch(intent)) null else "I couldn't reach your clock app, boss."
    }

    // ---------- Apps ----------

    private fun openApp(name: String): String? {
        val wanted = norm(name)
        val special = when (wanted) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "phone", "dialer", "dialpad" -> Intent(Intent.ACTION_DIAL)
            else -> null
        }
        if (special != null) return if (launch(special)) null else "I couldn't open $name, boss."
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.minByOrNull { app ->
            val label = norm(app.loadLabel(pm).toString())
            when {
                label == wanted -> 0
                label.startsWith(wanted) || wanted.startsWith(label) -> 1
                wanted in label -> 2
                else -> 3 + editDistance(label, wanted)
            }
        }
        val label = best?.let { norm(it.loadLabel(pm).toString()) }
        if (best == null || label == null || (wanted !in label && !label.startsWith(wanted) && editDistance(label, wanted) > 2)) {
            return "I couldn't find $name on your phone, boss."
        }
        val launchIntent = pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return "I couldn't open $name, boss."
        return if (launch(launchIntent)) null else "I couldn't open $name, boss."
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val cur = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return d[b.length]
    }

    // ---------- People ----------

    private data class Person(val name: String, val number: String)

    /** A number as said, or the best match in the phone's contacts. */
    private fun person(said: String): Person? {
        val digits = said.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return Person(said, digits)
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(said))
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            var found: Person? = null
            while (c.moveToNext()) {
                val p = Person(c.getString(0), c.getString(1))
                if (found == null || c.getInt(2) == 1) found = p
            }
            return found
        }
        return null
    }

    private fun noContact(said: String): String =
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            "I need your contacts for that, boss. Tap Allow contacts in Vakya."
        } else {
            "I couldn't find $said in your contacts, boss."
        }

    private fun call(said: String): String? {
        val p = person(said) ?: return noContact(said)
        val direct = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number)))
        if (!launch(intent)) return "I couldn't start the call, boss."
        return if (direct) "Calling ${p.name}, boss." else "Dialing ${p.name}, boss. Just tap call."
    }

    private fun message(task: DeviceTask): String? {
        val p = person(task.contact) ?: return noContact(task.contact)
        if (task.app.equals("sms", ignoreCase = true)) {
            val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", task.text)
            return if (launch(sms)) "Your message to ${p.name} is ready, boss. Shall I send it?" else "I couldn't open Messages, boss."
        }
        // WhatsApp wants the number with its country code and no "+": Indian numbers by default.
        var number = p.number.filter { it.isDigit() }
        if (number.startsWith("0")) number = number.trimStart('0')
        if (number.length == 10) number = "91$number"
        val url = Uri.parse("https://wa.me/$number?text=" + Uri.encode(task.text))
        val opened = listOf("com.whatsapp", "com.whatsapp.w4b", null).any { pkg ->
            launch(Intent(Intent.ACTION_VIEW, url).also { if (pkg != null) it.setPackage(pkg) })
        }
        return if (opened) "Your message to ${p.name} is ready, boss. Shall I send it?" else "I couldn't open WhatsApp, boss."
    }

    // ---------- Media, maps, search ----------

    private fun play(task: DeviceTask): String? {
        val q = task.text.ifBlank { "music" }
        val youtube = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        // "Play from search" makes Spotify / YouTube Music start playing the best match (it's what
        // Google Assistant uses); a plain search link would only open the search screen.
        fun playIn(pkg: String?) = launch(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, q)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .also { if (pkg != null) it.setPackage(pkg) },
        )
        val ok = when (task.app.lowercase()) {
            "spotify" -> playIn(SPOTIFY) || launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))))
            "youtube music" -> playIn(YT_MUSIC) || launch(youtube)
            "youtube" -> launch(Intent(youtube).setPackage("com.google.android.youtube")) || launch(youtube)
            else -> playIn(SPOTIFY) || playIn(YT_MUSIC) || playIn(null) || launch(youtube)
        }
        return if (ok) null else "I couldn't find a music app for that, boss."
    }

    private fun navigate(place: String): String? {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return if (ok) null else "I couldn't open maps, boss."
    }

    private fun search(query: String): String? {
        val ok = launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        return if (ok) null else "I couldn't open a browser, boss."
    }

    // ---------- The phone itself ----------

    private fun flashlight(on: Boolean): String? {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone's flashlight isn't available to me, boss."
        cameras.setTorchMode(id, on)
        return null
    }

    private fun battery(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (bm.isCharging) "You're at $level percent, boss, and charging." else "You're at $level percent, boss."
    }

    private fun phone(target: String): String? {
        val a11y = ChatReaderService.running ?: return "Switch Vakya on in Accessibility first, boss."
        val action = when (target) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return "This phone is too old for that, boss."
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return "This phone is too old for that, boss."
            else -> return "I'm not sure how to do that, boss."
        }
        return if (a11y.performGlobalAction(action)) null else "That didn't work, boss."
    }

    private fun settings(target: String): String? {
        val intent = when (target) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME) else Intent(Settings.ACTION_SOUND_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return if (launch(intent) || launch(Intent(Settings.ACTION_SETTINGS))) null else "I couldn't open settings, boss."
    }

    private fun volume(task: DeviceTask): String? {
        val audio = context.getSystemService(AudioManager::class.java)
        val direction = when {
            task.target == "mute" -> AudioManager.ADJUST_MUTE
            task.on -> AudioManager.ADJUST_RAISE
            else -> AudioManager.ADJUST_LOWER
        }
        repeat(if (direction == AudioManager.ADJUST_MUTE) 1 else 2) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        return null
    }

    /**
     * Opens [intent] from the accessibility service when it's on: Android lets apps with one start
     * screens from the background, which a plain service can't.
     */
    private fun launch(intent: Intent): Boolean {
        val from: Context = ChatReaderService.running ?: context
        return try {
            from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val TAG = "Vakya"
        const val SPOTIFY = "com.spotify.music"
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
    }
}
