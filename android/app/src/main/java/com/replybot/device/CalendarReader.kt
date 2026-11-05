package com.replybot.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The user's next events, read from the phone's calendars (only with "Let Friday see my calendar").
 * Titles and times stay on the phone unless Friday is asked about plans; she never writes to a
 * calendar herself: adding an event opens the calendar app filled in, for the user to save.
 */
object CalendarReader {

    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun upcoming(context: Context, hours: Int = 36, max: Int = 25): List<Event> {
        if (!allowed(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 60 * 60_000L)
            android.content.ContentUris.appendId(it, now + hours * 3_600_000L)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        return runCatching {
            context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val out = ArrayList<Event>()
                while (c.moveToNext() && out.size < max) {
                    val e = Event(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Busy" }, c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5).orEmpty())
                    if (e.end >= now) out += e
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** "Thu 5 Nov 3:00 pm-4:00 pm · Design review @ Office" */
    fun line(e: Event): String {
        val day = SimpleDateFormat("EEE d MMM", Locale.ENGLISH)
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        fun t(ms: Long) = time.format(Date(ms)).replace("AM", "am").replace("PM", "pm")
        val whenText = if (e.allDay) "${day.format(Date(e.begin))} all day" else "${day.format(Date(e.begin))} ${t(e.begin)}-${t(e.end)}"
        return "$whenText · ${e.title}" + if (e.location.isNotBlank()) " @ ${e.location}" else ""
    }

    fun lines(context: Context): List<String> = upcoming(context).map(::line)
}
