package app.gubbins.sendspin.session

/**
 * The clock a session reads.
 *
 * The protocol works in microseconds. The clock must be monotonic, because
 * the time filter measures the drift between this clock and the server's
 * clock. A clock that jumps backwards would make the filter forget what it
 * learned.
 */
public interface SessionClock {
    /** The current time, in microseconds. */
    public fun nowMicros(): Long
}

/** The clock that reads the system clock. */
public object SystemSessionClock : SessionClock {
    override fun nowMicros(): Long = System.nanoTime() / 1000
}
