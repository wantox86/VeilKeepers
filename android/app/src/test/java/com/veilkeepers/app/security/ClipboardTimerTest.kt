package com.veilkeepers.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic fake scheduler: tasks sit in a queue until the test advances
 * the virtual clock past their due time. No Android classes anywhere.
 */
private class FakeScheduler : DelayedTaskScheduler {

    private class Entry(val dueAt: Long, val cancelled: BooleanArray, val task: () -> Unit)

    private val queue = mutableListOf<Entry>()
    var now: Long = 0L
        private set

    val pendingCount: Int
        get() = queue.count { !it.cancelled[0] }

    override fun scheduleDelayed(delayMillis: Long, task: () -> Unit): DelayedTaskScheduler.TaskHandle {
        val cancelled = booleanArrayOf(false)
        val entry = Entry(now + delayMillis, cancelled, task)
        queue.add(entry)
        return DelayedTaskScheduler.TaskHandle { cancelled[0] = true }
    }

    /** Advances the clock, running every due non-cancelled task in order. */
    fun advance(millis: Long) {
        val target = now + millis
        while (true) {
            val next = queue
                .filter { !it.cancelled[0] && it.dueAt <= target }
                .minByOrNull { it.dueAt }
                ?: break
            next.cancelled[0] = true
            now = next.dueAt
            next.task()
        }
        now = target
    }
}

/**
 * Clipboard auto-clear core (spec.md §23, spec-1.md §B.9): 60 s window,
 * re-copy resets the timer, and the clear is ownership-gated — a later user
 * copy is never clobbered.
 */
class ClipboardTimerTest {

    private val scheduler = FakeScheduler()
    private val expired = mutableListOf<String>()
    private val timer = ClipboardTimer(scheduler) { expired.add(it) }
    private val clearSeconds = ClipboardTimer.CLIPBOARD_CLEAR_SECONDS.toLong()

    @Test
    fun clearWindowIsSixtySeconds() {
        assertEquals(60, ClipboardTimer.CLIPBOARD_CLEAR_SECONDS)
    }

    @Test
    fun armedCopyExpiresExactlyAfterTheWindow() {
        timer.arm("veil:1")
        assertEquals("veil:1", timer.armedLabelOrNull)

        scheduler.advance(clearSeconds * 1000 - 1)
        assertTrue(expired.isEmpty())

        scheduler.advance(1)
        assertEquals(listOf("veil:1"), expired)
        assertNull(timer.armedLabelOrNull)

        // Nothing else fires afterwards — the timer is one-shot per copy.
        scheduler.advance(clearSeconds * 1000 * 10)
        assertEquals(1, expired.size)
    }

    @Test
    fun reCopyResetsTheTimerAndOnlyTheLastLabelFires() {
        timer.arm("veil:1")
        scheduler.advance(30_000)
        timer.arm("veil:2") // re-copy resets the countdown

        // The original window's end must NOT clear: it was cancelled.
        scheduler.advance(30_000)
        assertTrue(expired.isEmpty())
        assertEquals(1, scheduler.pendingCount)

        scheduler.advance(30_000)
        assertEquals(listOf("veil:2"), expired)
    }

    @Test
    fun ownershipGateKeepsLaterUserCopiesUntouched() {
        timer.arm("veil:1")
        assertTrue(timer.ownsLabel("veil:1"))
        assertFalse(timer.ownsLabel("something-the-user-copied"))
        assertFalse(timer.ownsLabel(null))

        // At expiry the platform re-reads the clip; a different label means
        // the user copied something else → the gate refuses to clear.
        scheduler.advance(clearSeconds * 1000)
        assertEquals(listOf("veil:1"), expired)
        val ownedLabel = expired.single()
        assertFalse(timer.ownsLabel("other-app-label")) // armed is now null
        assertFalse(timer.ownsLabel(ownedLabel)) // and the one-shot cleared
    }

    @Test
    fun timerHoldsLabelsOnlyNeverSecrets() {
        // The core API accepts the label only — there is no secret parameter
        // anywhere on ClipboardTimer; compile-time guarantee by signature.
        timer.arm("veil:42")
        assertEquals("veil:42", timer.armedLabelOrNull)
    }
}
