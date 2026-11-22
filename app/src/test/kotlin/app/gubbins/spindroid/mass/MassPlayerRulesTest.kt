package app.gubbins.spindroid.mass

import app.gubbins.mass.MassPlayer
import org.junit.jupiter.api.Test

/**
 * The rules the Destination card follows with the drops and with the
 * players that read unavailable.
 *
 * The refresh control is the one view of every player the server
 * offers, so a swipe hides a row only until its next run: each run
 * forgets the drops and shows the whole answer of the server again.
 * A player that reads unavailable leaves the same way, and a player
 * that turns unavailable after the load keeps its row, so the card
 * never drops what the user is looking at.
 */
class MassPlayerRulesTest {

    @Test
    fun `a drop hides the row and the refresh forgets it`() {
        val players = listOf(player("kitchen"), player("loft"))
        check(MassPlayerRules.visible(players, setOf("kitchen")).map { it.playerId } == listOf("loft")) {
            "The swipe must hide the row, which gives ${MassPlayerRules.visible(players, setOf("kitchen")).map { it.playerId }}."
        }
        check(MassPlayerRules.visible(players, emptySet()).size == 2) {
            "The refresh must forget the drop and show every player again."
        }
    }

    @Test
    fun `a drop reaches the players that the rules keep out`() {
        val players = listOf(player("kitchen"), player("phone", type = MassPlayer.TYPE_SOURCE))
        check(MassPlayerRules.visible(players, setOf("kitchen")).isEmpty()) {
            "The row of the kitchen player must hide, and the phone itself never shows."
        }
    }

    @Test
    fun `a load skips the players that read unavailable`() {
        val players = listOf(player("kitchen"), player("loft", available = false))
        val hidden = MassPlayerRules.unavailableAtLoad(players)
        check(hidden == setOf("loft")) { "The load must skip the loft player, which gives $hidden." }
        check(
            MassPlayerRules.visible(players, emptySet(), hidden).map { it.playerId } == listOf("kitchen"),
        ) {
            "The load must hide the row of the unavailable player."
        }
    }

    @Test
    fun `a player that turns unavailable after the load keeps its row`() {
        val load = listOf(player("kitchen"), player("loft"))
        val hidden = MassPlayerRules.unavailableAtLoad(load)
        check(hidden.isEmpty()) { "A load of available players must skip none." }
        // The watch reads the list again, and the loft player reads
        // unavailable now, but the watch keeps the rows the load made.
        val read = listOf(player("kitchen"), player("loft", available = false))
        check(
            MassPlayerRules.visible(read, emptySet(), hidden).map { it.playerId } ==
                listOf("kitchen", "loft"),
        ) {
            "A player that turns unavailable after the load must keep its row."
        }
    }

    @Test
    fun `the next load reads the list again`() {
        val load = listOf(player("kitchen"), player("loft", available = false))
        val hidden = MassPlayerRules.unavailableAtLoad(load)
        check(MassPlayerRules.visible(load, emptySet(), hidden).size == 1) {
            "The loft player must hide at the load."
        }
        // The next load reads the players again, and the loft player
        // reads available now, so it returns.
        val read = listOf(player("kitchen"), player("loft"))
        val readHidden = MassPlayerRules.unavailableAtLoad(read)
        check(MassPlayerRules.visible(read, emptySet(), readHidden).size == 2) {
            "The next load must read every player again."
        }
    }

    /** One player that the server could send, named by its id. */
    private fun player(playerId: String, type: String = MassPlayer.TYPE_PLAYER, available: Boolean = true) =
        MassPlayer(playerId = playerId, name = playerId, type = type, available = available)
}
