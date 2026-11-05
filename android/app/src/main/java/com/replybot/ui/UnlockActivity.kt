package com.replybot.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.replybot.jarvis.JarvisService

/**
 * "Play Kesariya" with the phone locked: apps can't open over the lock screen, so this invisible
 * screen asks Android to show the unlock prompt (fingerprint, face or PIN), the way Google
 * Assistant does. Once unlocked, Friday carries on with what was asked. Nothing gets past the lock.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private var asked = 0

    // Asked once the screen is on and this is showing: from a dark, locked phone, asking any
    // earlier fails at once.
    override fun onResume() {
        super.onResume()
        if (asked == 0) ask()
    }

    private fun ask() {
        asked++
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            done(true)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() {
                // The screen was still waking: one more go.
                if (asked < 2) window.decorView.postDelayed({ ask() }, 600) else done(false)
            }
        })
    }

    private fun done(unlocked: Boolean) {
        JarvisService.unlocked(unlocked)
        finish()
    }
}
