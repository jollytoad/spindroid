package app.gubbins.sendspin.psk

/**
 * A store of pre-shared keys that keeps them in memory only.
 *
 * The Android app keeps its keys in encrypted storage, so it does not use
 * this class directly. The class serves the server mode, and it gives the
 * tests a store with the eviction rules of the specification.
 *
 * The rules come from the pairing record section of the specification:
 *
 * - The store holds at least five records.
 * - When a record is added and the store is full, one existing record is
 *   evicted so that the new record persists. A pairing never fails for
 *   lack of storage.
 * - A record that backs an open connection is never evicted.
 *
 * The specification leaves the choice of which record to evict to the
 * implementation. This class evicts the least recently used one.
 */
public class InMemoryPskStore(
    /** The number of records the store holds. */
    private val capacity: Int = DEFAULT_CAPACITY,
) : PskStore {
    init {
        require(capacity >= MINIMUM_CAPACITY) {
            "The store holds at least $MINIMUM_CAPACITY records"
        }
    }

    /**
     * The records, in the order they were last used.
     *
     * The first entry is the least recently used one.
     */
    private val records = LinkedHashMap<String, PskRecord>()

    /** The servers whose records back an open connection. */
    private val inUse = mutableSetOf<String>()

    /** The number of records the store holds. */
    public val size: Int get() = records.size

    override fun find(pskId: String, category: PskCategory): PskRecord? {
        val record = records.values.firstOrNull {
            it.pskId == pskId && it.category == category
        } ?: return null
        // The record was just used, so it moves to the end of the order.
        records.remove(record.serverId)
        records[record.serverId] = record
        return record
    }

    override fun put(record: PskRecord) {
        // A new record replaces any record the store already holds for the
        // same server.
        records.remove(record.serverId)
        records[record.serverId] = record
        evict()
    }

    override fun remove(serverId: String) {
        records.remove(serverId)
        inUse.remove(serverId)
    }

    override fun markInUse(serverId: String) {
        if (records.containsKey(serverId)) inUse.add(serverId)
    }

    override fun markUnused(serverId: String) {
        inUse.remove(serverId)
    }

    /**
     * The records, from the least recently used to the most recently used.
     */
    public fun records(): List<PskRecord> = records.values.toList()

    /**
     * Evicts records until the store fits.
     *
     * A record that backs an open connection stays, even when the store is
     * over capacity. The specification caps the number of open paired
     * connections below the record capacity, so this case does not arise in
     * a correct client.
     */
    private fun evict() {
        while (records.size > capacity) {
            val victim = records.keys.firstOrNull { it !in inUse } ?: return
            records.remove(victim)
        }
    }

    public companion object {
        /** The number of records the store holds by default. */
        public const val DEFAULT_CAPACITY: Int = 5

        /** The fewest records the specification requires a client to hold. */
        public const val MINIMUM_CAPACITY: Int = 5
    }
}
