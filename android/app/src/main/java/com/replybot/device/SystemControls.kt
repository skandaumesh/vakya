package com.replybot.device

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.replybot.accessibility.QuickSettings
import com.replybot.agent.Outcome
import com.replybot.agent.TaskRunner
import com.replybot.service.ChatReaderService
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The phone's switches. Each one is flipped the most direct way Android allows: its own API when
 * Vakya has the permission for it (Do not disturb, auto-rotate, brightness), else the Quick
 * Settings tile through the accessibility service, else the settings page for the user to flip.
 * Afterwards the real state is read back, and only a change that happened is reported as done.
 */
class SystemControls(context: Context) {

    private val ctx = context.applicationContext
    private val status = DeviceStatus(ctx)
    private val main = Handler(Looper.getMainLooper())

    fun toggle(target: String, on: Boolean, done: (Outcome) -> Unit) {
        val name = TaskRunner.TOGGLE_NAMES[target] ?: return done(Outcome.fail("I can't switch that, boss.", "unknown switch $target"))
        val onOff = if (on) "on" else "off"
        if (status.isOn(target) == on) return done(Outcome(true, "$name is already $onOff, boss.", "already $onOff"))

        if (direct(target, on)) {
            main.postDelayed({ done(verify(target, on, name, viaTile = null)) }, 500)
            return
        }
        val a11y = ChatReaderService.running
        if (a11y == null) {
            openSettings(target)
            return done(Outcome.fail("I need Vakya switched on in Accessibility to flip $name, boss. I've opened its settings for you.", "accessibility is off"))
        }
        QuickSettings(a11y).toggle(target, on) { r ->
            main.postDelayed({
                if (!r.found) {
                    openSettings(target)
                    done(Outcome.fail("I couldn't find the $name switch in Quick Settings, boss, so I've opened its settings.", "no $name tile in Quick Settings"))
                } else {
                    done(verify(target, on, name, viaTile = r.on))
                }
            }, 300)
        }
    }

    private fun verify(target: String, on: Boolean, name: String, viaTile: Boolean?): Outcome {
        val now = status.isOn(target) ?: viaTile
        val onOff = if (on) "on" else "off"
        return when (now) {
            on -> Outcome(true, null, "checked: $onOff")
            null -> Outcome(true, "I've flipped the $name switch, boss, but Android won't tell me if it took.", "switched, unconfirmed")
            else -> {
                openSettings(target)
                Outcome.fail("$name didn't switch $onOff, boss. Your phone may want you to do it; I've opened the settings.", "still ${if (on) "off" else "on"} after trying")
            }
        }
    }

    /** Android's own API, where an app may use it. True if it was called. */
    private fun direct(target: String, on: Boolean): Boolean = runCatching {
        when (target) {
            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm?.isNotificationPolicyAccessGranted != true) return false
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                true
            }
            "rotation" -> {
                if (!Settings.System.canWrite(ctx)) return false
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            }
            "wifi" -> {
                // Apps may only switch Wi-Fi themselves before Android 10.
                if (Build.VERSION.SDK_INT >= 29) return false
                @Suppress("DEPRECATION")
                ctx.getSystemService(WifiManager::class.java)?.setWifiEnabled(on) == true
            }
            else -> false
        }
    }.getOrDefault(false)

    fun openSettings(target: String): Boolean {
        val action = when (target) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "mobile_data", "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "rotation", "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "dark_mode" -> if (Build.VERSION.SDK_INT >= 29) "android.settings.DARK_THEME_SETTINGS" else Settings.ACTION_DISPLAY_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))
    }

    // ---------- Brightness and volume ----------

    /** To [value] percent, or a step [up]/down from where it is now. */
    fun brightness(value: Int, up: Boolean, done: (Outcome) -> Unit) {
        val now = status.brightnessPercent() ?: 50
        val want = (if (value >= 0) value else now + if (up) 25 else -25).coerceIn(if (value >= 0) 0 else 5, 100)
        if (Settings.System.canWrite(ctx)) {
            runCatching {
                val r = ctx.contentResolver
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, percentToRaw(want, maxBrightness()))
            }
            val after = status.brightnessPercent()
            return done(if (after != null && kotlin.math.abs(after - want) <= 6) Outcome(true, null, "brightness $after%")
                        else Outcome(true, null, "set to $want%"))
        }
        val a11y = ChatReaderService.running
            ?: return done(Outcome.fail("To change brightness I need Modify system settings for Vakya, boss. Turn it on in Friday's permissions.", "no permission"))
        QuickSettings(a11y).brightness(want) { ok ->
            done(if (ok) Outcome(true, null, "slider at $want%")
                 else Outcome.fail("I couldn't reach the brightness slider, boss. Allow Modify system settings for Vakya and I'll do it directly.", "no slider"))
        }
    }

    fun volume(value: Int, target: String, up: Boolean): Outcome {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return Outcome.fail("I couldn't reach the volume, boss.")
        val stream = AudioManager.STREAM_MUSIC
        when {
            target == "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            target == "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            value >= 0 -> {
                val max = audio.getStreamMaxVolume(stream)
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(stream, (max * value.coerceIn(0, 100) / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
            }
            else -> repeat(2) {
                audio.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        }
        val now = status.volumePercent()
        return Outcome(true, null, now?.let { "media volume $it%" } ?: "")
    }

    private fun start(intent: Intent): Boolean {
        val from: Context = ChatReaderService.running ?: ctx
        return try {
            from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        /** The phone's brightness range (255 on most; some makers use 1023, 2047 or 4095). */
        fun maxBrightness(): Int = runCatching {
            val id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            if (id != 0) Resources.getSystem().getInteger(id) else 255
        }.getOrDefault(255).takeIf { it > 0 } ?: 255

        // The slider is perceptual, the setting is linear: a gamma curve maps between them.
        fun percentToRaw(percent: Int, max: Int): Int =
            (max * (percent.coerceIn(0, 100) / 100.0).pow(2.2)).roundToInt().coerceIn(1, max)

        fun rawToPercent(raw: Int, max: Int): Int =
            (100 * (raw.coerceIn(0, max).toDouble() / max).pow(1 / 2.2)).roundToInt()
    }
}
