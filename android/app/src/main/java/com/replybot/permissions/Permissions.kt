package com.replybot.permissions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.replybot.device.CalendarReader
import com.replybot.jarvis.Habits
import com.replybot.jarvis.NotificationWatcher
import com.replybot.service.ChatReaderService

/**
 * Everything Friday can be allowed to do, why, and how to allow it. Each one is optional and
 * switched on by the user; Friday says what's missing when she needs it.
 */
object Permissions {

    class Item(
        val name: String,
        val why: String,
        val granted: (Activity) -> Boolean,
        val request: (Activity) -> Unit,
    )

    const val REQUEST_MIC = 2
    const val REQUEST_CONTACTS = 3
    const val REQUEST_CALENDAR = 4
    const val REQUEST_NOTIFY = 5

    fun accessibilityOn(a: Activity): Boolean {
        val enabled = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(a, ChatReaderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun has(a: Activity, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun open(a: Activity, intent: Intent) {
        runCatching { a.startActivity(intent) }.onFailure {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
        }
    }

    val ALL = listOf(
        Item("Microphone", "To hear \"Hey Friday\" (listened for on this phone only).",
            { has(it, Manifest.permission.RECORD_AUDIO) },
            { it.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }),
        Item("Accessibility", "To see and tap the screen when you ask, flip Quick Settings switches, and type replies.",
            ::accessibilityOn,
            { open(it, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
        Item("Notifications", "So Friday's reminders and briefings can reach you.",
            { Build.VERSION.SDK_INT < 33 || has(it, Manifest.permission.POST_NOTIFICATIONS) },
            { if (Build.VERSION.SDK_INT >= 33) it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY) }),
        Item("Notification access", "For \"any new messages?\" and summaries (never OTPs or codes).",
            { NotificationWatcher.allowed(it) },
            { a ->
                val me = ComponentName(a, NotificationWatcher::class.java).flattenToString()
                val direct = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me) else null
                if (direct == null || runCatching { a.startActivity(direct) }.isFailure) open(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }),
        Item("Contacts and calls", "For \"call Amma\" and \"message Rahul\" (looked up on this phone).",
            { has(it, Manifest.permission.READ_CONTACTS) },
            { it.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQUEST_CONTACTS) }),
        Item("Calendar", "For \"what's on today?\", briefings and \"meeting in ten minutes\".",
            { CalendarReader.allowed(it) },
            { it.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR) }),
        Item("Do not disturb", "To switch Do not disturb on and off directly.",
            { it.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
            { open(it, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }),
        Item("Modify system settings", "To set brightness and auto-rotate directly.",
            { Settings.System.canWrite(it) },
            { a -> open(a, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}"))) }),
        Item("App usage", "For \"what's my screen time?\" and switching back to the last app.",
            { Habits.allowed(it) },
            { open(it, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
        Item("Display over other apps", "So Friday can open apps while you're in another one.",
            { Settings.canDrawOverlays(it) },
            { a -> open(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }),
        Item("Run when locked", "Keeps Friday listening with the screen off (battery optimisation off).",
            { it.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(it.packageName) == true },
            { a ->
                @Suppress("BatteryLife")
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
                open(a, ask)
            }),
    )
}
