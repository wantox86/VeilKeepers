package com.veilkeepers.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mutable fake clock for the state machine tests. */
private class FakeClock(var now: Long = 0L) : Clock {
    override fun millis(): Long = now
}

/**
 * Soft auto-lock policy matrix (spec.md §24, spec-1.md §B.10) with the
 * recreation-flap grace period, all on the injectable fake clock.
 */
class AutoLockControllerTest {

    private val clock = FakeClock()
    private val grace = AutoLockController.GRACE_MILLIS

    private fun unlocked(controller: AutoLockController) {
        assertFalse(controller.onEvent(AutoLockController.Event.UNLOCKED))
        assertFalse(controller.isLocked)
    }

    @Test
    fun freshControllerStartsLockedUntilUnlocked() {
        val controller = AutoLockController(clock)
        assertTrue(controller.isLocked)
        // Events while locked never trigger another lock decision.
        assertFalse(controller.onEvent(AutoLockController.Event.BACKGROUND))
        assertFalse(controller.onEvent(AutoLockController.Event.TIMEOUT))
        unlocked(controller)
    }

    @Test
    fun immediatelyPolicyLocksAfterGraceButNeverInsideIt() {
        val controller = AutoLockController(clock, AutoLockPolicy.IMMEDIATELY)
        unlocked(controller)

        // Recreation flap: stop/start within the grace window must NOT lock.
        controller.onEvent(AutoLockController.Event.BACKGROUND)
        clock.now = grace - 1
        assertFalse(controller.onEvent(AutoLockController.Event.FOREGROUND))
        assertFalse(controller.isLocked)

        // Same policy, now genuinely backgrounded past the deadline.
        controller.onEvent(AutoLockController.Event.BACKGROUND)
        clock.now += grace
        assertTrue(controller.onEvent(AutoLockController.Event.TIMEOUT))
        assertTrue(controller.isLocked)
    }

    @Test
    fun oneMinutePolicyHoldsAcrossTheTimeoutBoundary() {
        val controller = AutoLockController(clock, AutoLockPolicy.ONE_MINUTE)
        unlocked(controller)

        controller.onEvent(AutoLockController.Event.BACKGROUND)
        // Just before timeout+grace: no lock.
        clock.now = AutoLockPolicy.ONE_MINUTE.timeoutMillis + grace - 1
        assertFalse(controller.onEvent(AutoLockController.Event.TIMEOUT))
        assertFalse(controller.isLocked)
        // Exactly at the boundary: lock.
        clock.now += 1
        assertTrue(controller.onEvent(AutoLockController.Event.TIMEOUT))
        assertTrue(controller.isLocked)
    }

    @Test
    fun everyPolicyCarriesTheRightDelay() {
        assertEquals(0L, AutoLockPolicy.IMMEDIATELY.timeoutMillis)
        assertEquals(60_000L, AutoLockPolicy.ONE_MINUTE.timeoutMillis)
        assertEquals(300_000L, AutoLockPolicy.FIVE_MINUTES.timeoutMillis)
        assertEquals(900_000L, AutoLockPolicy.FIFTEEN_MINUTES.timeoutMillis)

        for (policy in AutoLockPolicy.entries) {
            val controller = AutoLockController(clock, policy)
            assertEquals(policy.timeoutMillis + grace, controller.lockDelayMillis())
        }
    }

    @Test
    fun policyTokensRoundTripAndUnknownFallsBackToImmediately() {
        for (policy in AutoLockPolicy.entries) {
            assertEquals(policy, AutoLockPolicy.fromToken(policy.token))
        }
        assertEquals(AutoLockPolicy.IMMEDIATELY, AutoLockPolicy.fromToken(""))
        assertEquals(AutoLockPolicy.IMMEDIATELY, AutoLockPolicy.fromToken("BOGUS"))
    }

    @Test
    fun foregroundAfterTheDeadlineStillLocks() {
        val controller = AutoLockController(clock, AutoLockPolicy.ONE_MINUTE)
        unlocked(controller)

        controller.onEvent(AutoLockController.Event.BACKGROUND)
        clock.now = AutoLockPolicy.ONE_MINUTE.timeoutMillis + grace + 10
        // The TIMEOUT runnable never fired, but returning after the deadline
        // must still take the lock decision.
        assertTrue(controller.onEvent(AutoLockController.Event.FOREGROUND))
        assertTrue(controller.isLocked)
    }

    @Test
    fun returningBeforeTheDeadlineKeepsTheVaultOpen() {
        val controller = AutoLockController(clock, AutoLockPolicy.FIVE_MINUTES)
        unlocked(controller)

        controller.onEvent(AutoLockController.Event.BACKGROUND)
        clock.now = AutoLockPolicy.FIVE_MINUTES.timeoutMillis - 1
        assertFalse(controller.onEvent(AutoLockController.Event.FOREGROUND))
        assertFalse(controller.isLocked)
        // And a later TIMEOUT with no active background window does nothing.
        clock.now += AutoLockPolicy.FIVE_MINUTES.timeoutMillis * 2
        assertFalse(controller.onEvent(AutoLockController.Event.TIMEOUT))
        assertFalse(controller.isLocked)
    }

    @Test
    fun userLockAndSessionExpiredAreImmediateAndTerminalUntilUnlock() {
        for (event in listOf(
            AutoLockController.Event.USER_LOCK,
            AutoLockController.Event.SESSION_EXPIRED,
        )) {
            val controller = AutoLockController(clock, AutoLockPolicy.FIFTEEN_MINUTES)
            unlocked(controller)
            assertTrue(controller.onEvent(event))
            assertTrue(controller.isLocked)
            // Silent while locked...
            assertFalse(controller.onEvent(AutoLockController.Event.TIMEOUT))
            // ...until the next unlock re-arms the machine.
            unlocked(controller)
        }
    }

    @Test
    fun unlockClearsAnyStaleBackgroundWindow() {
        val controller = AutoLockController(clock, AutoLockPolicy.IMMEDIATELY)
        unlocked(controller)
        controller.onEvent(AutoLockController.Event.BACKGROUND)
        unlocked(controller) // unlock happens mid-background
        clock.now += grace + 1
        // No lock: the background window was discarded by the unlock.
        assertFalse(controller.onEvent(AutoLockController.Event.TIMEOUT))
        assertFalse(controller.isLocked)
    }
}
