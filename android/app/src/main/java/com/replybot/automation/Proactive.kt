package com.replybot.automation

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.replybot.R
import com.replybot.data.Store
import com.replybot.jarvis.JarvisService
import com.replybot.memory.Memory
import com.replybot.ui.CommandCenterActivity

/**
 * Friday speaking up on her own, only as the user set it in the Command Center: a morning
 * briefing and an evening summary at the times they chose. (Reminders, upcoming events and low
 * battery are checked by the listening service each minute.) If Friday isn't listening when a
 * briefing is due, a notification offers it instead.
 */
object Proactive {

    const val CHANNEL = "friday_briefings"
    private const val KIND = "kind"

    /** Sets (or clears) the next morning and evening alarms from the user's settings. */
    fun schedule(context: Context) {
        val store = Store.get(context)
        set(context, "morning", store.morningBriefing, store.morningAt, 41)
        set(context, "evening", store.eveningSummary, store.eveningAt, 42)
    }

    private fun set(context: Context, kind: String, on: Boolean, minutes: Int, code: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, code, Intent(context, BriefingReceiver::class.java).putExtra(KIND, kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(pi)
        if (!on) return
        val at = Memory.nextAt(minutes / 60, minutes % 60)
        // Inexact (within a few minutes) is fine for a briefing, and needs no special permission.
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun notify(context: Context, channel: String, title: String, text: String, id: Int, tap: PendingIntent? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(NotificationChannel(channel, "Friday", NotificationManager.IMPORTANCE_HIGH))
        }
        val onTap = tap ?: PendingIntent.getActivity(
            context, id, Intent(context, CommandCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(onTap)
            .build()
        nm.notify(id, n)
    }

    class BriefingReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = intent.getStringExtra(KIND) ?: "day"
            schedule(context)  // tomorrow's
            if (JarvisService.briefing(kind)) return
            // Friday isn't listening: offer it. Tapping it starts her and she says it (card at the top).
            val title = if (kind == "morning") "Good morning, boss" else "Your evening summary"
            val hear = PendingIntent.getForegroundService(
                context, 40 + kind.length,
                Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_BRIEFING).putExtra(JarvisService.EXTRA_KIND, kind),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            notify(context, CHANNEL, title, "Tap to hear what's ahead.", id = 40 + kind.length, tap = hear)
        }
    }

    /** Alarms are forgotten when the phone restarts: set them again. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) schedule(context)
        }
    }
}
