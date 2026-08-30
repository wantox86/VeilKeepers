package com.veilkeepers.app.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Clipboard protection (spec.md §23, spec-1.md §B.9): copied secrets are
 * cleared automatically 60 s later — but ONLY while our copy is still the
 * primary clip (never clobber something the user copied afterwards).
 */

/** Injectable delay scheduler so the timer core is testable on the plain JVM. */
interface DelayedTaskScheduler {
    /** Runs [task] after [delayMillis]; the handle cancels a pending run. */
    fun scheduleDelayed(delayMillis: Long, task: () -> Unit): TaskHandle

    fun interface TaskHandle {
        fun cancel()
    }
}

/**
 * Pure core of the clipboard timer: arm on every copy, re-arm resets the
 * countdown, and the expiry callback receives the OWNED clip label so the
 * platform side can gate the clear on ownership. The timer holds NO secret
 * bytes — labels only; the platform layer re-reads the clip at clear time.
 */
class ClipboardTimer(
    private val scheduler: DelayedTaskScheduler,
    private val clearDelayMillis: Long = CLIPBOARD_CLEAR_SECONDS * 1000L,
    private val onExpire: (ownedLabel: String) -> Unit,
) {

    private var pending: DelayedTaskScheduler.TaskHandle? = null
    private var armedLabel: String? = null

    /** The clip label currently armed for clearing (null when nothing is armed). */
    val armedLabelOrNull: String?
        get() = armedLabel

    /** Called on every copy: cancels any pending clear and restarts the countdown. */
    fun arm(label: String) {
        pending?.cancel()
        armedLabel = label
        pending = scheduler.scheduleDelayed(clearDelayMillis) {
            val owned = armedLabel
            armedLabel = null
            pending = null
            if (owned != null) onExpire(owned)
        }
    }

    /**
     * Ownership gate for the clear step: the platform's current primary clip
     * label must equal the label we armed, otherwise a later user copy owns
     * the clipboard and must be left untouched.
     */
    fun ownsLabel(currentLabel: String?): Boolean =
        currentLabel != null && armedLabel == currentLabel

    companion object {
        /** Auto-clear window in seconds (spec-1.md §B.9: default 60, configurable later). */
        const val CLIPBOARD_CLEAR_SECONDS = 60
    }
}

/**
 * Production Android clipboard guard. [copy] writes the secret with a marked
 * label (marker + timestamp, so every copy is uniquely identifiable), flags
 * it sensitive on API 31+ via the EXTRA_IS_SENSITIVE clip extra, and arms
 * the 60 s timer. At expiry the clip is re-read and cleared ONLY if our
 * marker-label is still primary.
 */
class ClipboardGuard(
    context: Context,
    private val timer: ClipboardTimer = ClipboardTimer(
        scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
        onExpire = { ownedLabel ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val currentLabel = clipboard.primaryClipDescription?.label?.toString()
            if (currentLabel == ownedLabel) {
                if (Build.VERSION.SDK_INT >= 28) {
                    clipboard.clearPrimaryClip()
                } else {
                    // API 26–27: no clear API — replace with an empty clip.
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        },
    ),
) {

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Copies [secret] to the clipboard and arms the 60 s auto-clear timer. */
    fun copy(secret: String) {
        val label = LABEL_MARKER + ":" + System.currentTimeMillis()
        val clip = ClipData.newPlainText(label, secret)
        if (Build.VERSION.SDK_INT >= 31) {
            // ClipDescription has NO isSensitive/setSensitive methods on any
            // SDK — the official mechanism is the EXTRA_IS_SENSITIVE extra
            // (constant added in API 31) carried via setExtras (API 11+).
            val extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clip.description.setExtras(extras)
        }
        clipboardManager.setPrimaryClip(clip)
        timer.arm(label)
    }

    companion object {
        /** Prefix marking clips we own; the timestamp suffix makes each copy unique. */
        const val LABEL_MARKER = "com.veilkeepers.secret"
    }
}

/** [DelayedTaskScheduler] over an Android [Handler] (production wiring). */
class HandlerScheduler(private val handler: Handler) : DelayedTaskScheduler {
    override fun scheduleDelayed(delayMillis: Long, task: () -> Unit): DelayedTaskScheduler.TaskHandle {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMillis)
        return DelayedTaskScheduler.TaskHandle { handler.removeCallbacks(runnable) }
    }
}
