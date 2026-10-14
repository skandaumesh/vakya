package com.replybot.jarvis

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * "Let Friday see notifications": Android hands each new notification to this service, which
 * keeps it in [NotificationLog] on this phone (so "any new messages?" works) and tells Friday about
 * new personal messages, for "Message from Rahul. Shall I read it?" when that's switched on.
 * Only on while the user allows Vakya in Settings → Notification access.
 */
class NotificationWatcher : NotificationListenerService() {

    private val labels = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.packageName in SYSTEM || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in QUIET_CATEGORIES) return
        val extras = n.extras ?: return
        val app = label(sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || !conversation.isNullOrBlank()
        val log = NotificationLog.get(this)
        val fresh = mutableListOf<NotificationLog.Item>()

        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty()
        if (messages.isNotEmpty()) {
            // A chat: each message with its sender. No sender means the user wrote it.
            for (m in messages.takeLast(6)) {
                val b = m as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                val sender = senderOf(b) ?: continue
                if (text.isEmpty()) continue
                val who = if (isGroup && conversation != null) "$conversation: $sender" else sender
                val item = NotificationLog.Item(sbn.packageName, app, who, text.take(400), b.getLong("time", sbn.postTime), true, isGroup)
                if (log.add(item)) fresh += item
            }
        } else {
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val item = NotificationLog.Item(
                sbn.packageName, app, title.take(100), text.take(400), sbn.postTime,
                n.category == Notification.CATEGORY_MESSAGE, isGroup,
            )
            if (log.add(item)) fresh += item
        }
        // New one-to-one messages: Friday may say so.
        val personal = fresh.filter { it.isMessage && !it.isGroup }
        if (personal.isNotEmpty()) JarvisService.announce(personal)
    }

    private fun senderOf(b: Bundle): String? {
        b.getCharSequence("sender")?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        @Suppress("DEPRECATION")
        val person = b.getParcelable<Parcelable>("sender_person") as? android.app.Person
        return person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    companion object {
        private val SYSTEM = setOf("android", "com.android.systemui", "com.android.vending", "com.google.android.gms")
        private val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS,
        )

        /** The user has given Vakya notification access. */
        fun allowed(context: Context): Boolean {
            val me = ComponentName(context, NotificationWatcher::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
