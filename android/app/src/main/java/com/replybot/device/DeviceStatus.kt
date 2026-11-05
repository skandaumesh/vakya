package com.replybot.device

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings

/**
 * The phone's state right now, read with Android's own APIs (no special permissions): for the
 * Command Center, for Friday's answers ("is my Bluetooth on?"), and to check a switch she flipped
 * really changed. Anything Android won't tell us is null, never a guess.
 */
class DeviceStatus(context: Context) {

    private val ctx = context.applicationContext

    data class Battery(val percent: Int, val charging: Boolean, val tempC: Float?, val health: String?)

    fun battery(): Battery {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = runCatching {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val health = when (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "worn out"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            else -> null
        }
        return Battery(level, bm?.isCharging == true, temp, health)
    }

    /** Whether [target] (a toggle target, see the server's TOGGLE_TARGETS) is on; null if Android won't say. */
    fun isOn(target: String): Boolean? = runCatching {
        when (target) {
            "wifi" -> ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled
            "bluetooth" -> ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
            "dnd" -> ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            "battery_saver" -> ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode
            "rotation" -> Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "airplane" -> Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "location" -> if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled else null
            "dark_mode" -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ||
                ctx.getSystemService(UiModeManager::class.java)?.nightMode == UiModeManager.MODE_NIGHT_YES
            "nfc" -> NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
            "mobile_data" -> runCatching { Settings.Global.getInt(ctx.contentResolver, "mobile_data") == 1 }.getOrNull()
            else -> null
        }
    }.getOrNull()

    /** Screen brightness, 0-100 as the slider shows it, or null. */
    fun brightnessPercent(): Int? = runCatching {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        SystemControls.rawToPercent(raw, SystemControls.maxBrightness())
    }.getOrNull()

    fun volumePercent(): Int? = runCatching {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }.getOrNull()

    fun ringer(): String = when (ctx.getSystemService(AudioManager::class.java)?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    /** "Wi-Fi", "mobile data", or "offline". */
    fun network(): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }
    }.getOrDefault("unknown")

    /** Headphones, speakers and car audio connected now, by name. */
    fun connectedAudio(): List<String> = runCatching {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return emptyList()
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type in setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                ) || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            .map { d ->
                d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
                    ?: if (d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) "Wired headphones" else "Bluetooth audio"
            }
            .distinct()
    }.getOrDefault(emptyList())

    /** Free space on the phone, in GB. */
    fun freeStorageGb(): Float? = runCatching {
        val s = StatFs(Environment.getDataDirectory().path)
        s.availableBytes / 1_000_000_000f
    }.getOrNull()

    /** One line for Friday: "battery 54%, not charging; Wi-Fi on (connected); Bluetooth off; ...". */
    fun summary(): String {
        val b = battery()
        val parts = mutableListOf("battery ${b.percent}%" + if (b.charging) ", charging" else ", not charging")
        fun onOff(name: String, target: String) = isOn(target)?.let { parts += "$name ${if (it) "on" else "off"}" }
        onOff("Wi-Fi", "wifi")
        onOff("Bluetooth", "bluetooth")
        onOff("do not disturb", "dnd")
        onOff("battery saver", "battery_saver")
        onOff("auto-rotate", "rotation")
        onOff("airplane mode", "airplane")
        onOff("location", "location")
        parts += "ringer ${ringer()}"
        parts += "network ${network()}"
        volumePercent()?.let { parts += "media volume $it%" }
        brightnessPercent()?.let { parts += "brightness $it%" }
        connectedAudio().takeIf { it.isNotEmpty() }?.let { parts += "connected: ${it.joinToString()}" }
        freeStorageGb()?.let { parts += "free storage %.0f GB".format(it) }
        return parts.joinToString("; ")
    }
}
