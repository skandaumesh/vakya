package com.replybot.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * "What's on my screen?": one screenshot, taken only when the user asks Friday about the screen,
 * through the accessibility service (Android 11+; no screen-recording prompt). Scaled down and
 * sent with the question, never saved. Apps that protect their screens (banking, passwords) come
 * out black, as Android intends.
 */
object ScreenLook {

    /** The screen as a base64 JPEG about [width] px wide, or null (older Android, or it failed). */
    fun capture(service: AccessibilityService, executor: Executor, width: Int = 720, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return done(null)
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val jpeg = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        shot?.let { encode(it, width) }
                    }.getOrNull()
                    done(jpeg)
                }

                override fun onFailure(errorCode: Int) = done(null)
            })
        } catch (e: Exception) {
            done(null)
        }
    }

    private fun encode(shot: Bitmap, width: Int): String {
        val scale = minOf(1f, width.toFloat() / shot.width)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 72, out)
        if (small !== shot) small.recycle()
        shot.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
