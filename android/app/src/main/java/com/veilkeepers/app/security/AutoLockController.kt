package com.veilkeepers.app.security

/**
 * Auto-lock policy (spec-1.md §B.10): default "Immediately"; options
 * immediately / 1 / 5 / 15 minutes. The [token] string form is what gets
 * stored in [com.veilkeepers.app.data.SessionStorage.autoLockPolicy] —
 * SESSION-scoped: sign-out wipes it and the policy resets to the
 * "Immediately" default (review F9, deliberate).
 */
enum class AutoLockPolicy(val token: String, val timeoutMillis: Long) {
    IMMEDIATELY("IMMEDIATELY", 0L),
    ONE_MINUTE("ONE_MINUTE", 60_000L),
    FIVE_MINUTES("FIVE_MINUTES", 5 * 60_000L),
    FIFTEEN_MINUTES("FIFTEEN_MINUTES", 15 * 60_000L);

    companion object {
        /** Parses a persisted [token]; unknown/blank values fall back to the default. */
        fun fromToken(token: String): AutoLockPolicy =
            entries.firstOrNull { it.token == token } ?: IMMEDIATELY
    }
}

/** Injectable time source so the state machine is testable on the plain JVM. */
fun interface Clock {
    /** Monotonic milliseconds (production: android.os.SystemClock.elapsedRealtime()). */
    fun millis(): Long
}

/**
 * Soft auto-lock state machine (spec.md §24, spec-1.md §B.10).
 *
 * Soft-lock semantics (Sprint 6 decision): locking ZEROIZES the in-memory VK
 * and routes to the Unlock screen, but keeps the server session alive — only
 * the explicit "Lock & sign out" revokes the session.
 *
 * The controller is a pure decision engine: the activity feeds lifecycle
 * events in and acts on the `true` returns ("should lock now"). A grace
 * period ([GRACE_MILLIS]) absorbs activity-recreation flap — e.g. a
 * configuration change causes a stop immediately followed by a start, which
 * must NOT lock under the "Immediately" policy.
 *
 * No Android imports in this file (pure JVM core).
 */
class AutoLockController(
    private val clock: Clock,
    policy: AutoLockPolicy = AutoLockPolicy.IMMEDIATELY,
    private val graceMillis: Long = GRACE_MILLIS,
) {

    /** Lifecycle/lock events fed by the host. */
    enum class Event { BACKGROUND, FOREGROUND, TIMEOUT, USER_LOCK, UNLOCKED, SESSION_EXPIRED }

    /** Effective policy; changes apply to the NEXT background transition. */
    var policy: AutoLockPolicy = policy

    /** True once a lock decision has been taken and not yet cleared by [Event.UNLOCKED]. */
    var isLocked: Boolean = true
        private set

    /** Timestamp (clock millis) at which the app went to background; null while foregrounded. */
    private var backgroundAt: Long? = null

    /** Total background delay before locking = policy timeout + grace period. */
    fun lockDelayMillis(): Long = policy.timeoutMillis + graceMillis

    /**
     * Feeds [event] into the state machine.
     *
     * @return true when the caller MUST lock now (zeroize VK, route to the
     * unlock screen). Always false while already locked.
     */
    fun onEvent(event: Event): Boolean {
        if (isLocked && event != Event.UNLOCKED) return false
        return when (event) {
            Event.UNLOCKED -> {
                isLocked = false
                backgroundAt = null
                false
            }

            Event.USER_LOCK, Event.SESSION_EXPIRED -> {
                isLocked = true
                backgroundAt = null
                true
            }

            Event.BACKGROUND -> {
                if (backgroundAt == null) backgroundAt = clock.millis()
                // Never lock synchronously on background: the grace period
                // absorbs recreation flap; the TIMEOUT / FOREGROUND checks
                // below take the decision once the deadline has passed.
                false
            }

            Event.FOREGROUND -> {
                val at = backgroundAt
                backgroundAt = null
                if (at != null && clock.millis() - at >= lockDelayMillis()) {
                    // Came back AFTER the deadline elapsed — the vault must
                    // have been unattended past the policy timeout.
                    isLocked = true
                    true
                } else {
                    false
                }
            }

            Event.TIMEOUT -> {
                val at = backgroundAt
                if (at != null && clock.millis() - at >= lockDelayMillis()) {
                    isLocked = true
                    true
                } else {
                    false
                }
            }
        }
    }

    companion object {
        /**
         * Grace period absorbing activity-recreation flap (config change,
         * dialog activities): background→start pairs inside this window never
         * lock, even under the "Immediately" policy.
         */
        const val GRACE_MILLIS = 2_000L
    }
}
