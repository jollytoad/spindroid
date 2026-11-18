package app.gubbins.spindroid.session

import app.gubbins.sendspin.session.SessionClock

/**
 * The clock the session reads.
 *
 * The clock has to be monotonic, because the time filter measures the
 * drift between this clock and the server's clock. The system clock is
 * monotonic, and it counts in nanoseconds, so it gives the microseconds
 * the protocol wants.
 */
object AndroidSessionClock : SessionClock {
    override fun nowMicros(): Long = System.nanoTime() / 1000
}
