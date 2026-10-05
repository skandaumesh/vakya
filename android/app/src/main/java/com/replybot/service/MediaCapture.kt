package com.replybot.service

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/** Screenshot the chat once, then cut out each photo/sticker as a small JPEG. */
object MediaCapture {

    private const val MAX_SIDE = 384
    private const val JPEG_QUALITY = 75

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Calls [done] on [executor] with one base64 JPEG (or null) per rect. */
    @TargetApi(Build.VERSION_CODES.R)
    fun capture(service: AccessibilityService, rects: List<Rect>, executor: Executor, done: (List<String?>) -> Unit) {
        val none = rects.map { null }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    buffer.close()
                    if (shot == null) return done(none)
                    val images = rects.map { r -> runCatching { cropToJpeg(shot, r) }.getOrNull() }
                    shot.recycle()
                    done(images)
                }

                // Secure screens (view-once media), too-frequent screenshots, etc.
                override fun onFailure(errorCode: Int) = done(none)
            })
        } catch (e: SecurityException) {
            done(none)
        }
    }

    private fun cropToJpeg(shot: Bitmap, r: Rect): String? {
        val clipped = Rect(r)
        if (!clipped.intersect(0, 0, shot.width, shot.height) || clipped.width() < 8 || clipped.height() < 8) return null
        val crop = Bitmap.createBitmap(shot, clipped.left, clipped.top, clipped.width(), clipped.height())
        val scale = MAX_SIDE.toFloat() / max(crop.width, crop.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(crop, (crop.width * scale).roundToInt(), (crop.height * scale).roundToInt(), true)
                .also { crop.recycle() }
        } else {
            crop
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        small.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
