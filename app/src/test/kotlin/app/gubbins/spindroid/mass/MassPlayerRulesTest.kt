package app.gubbins.spindroid.mass

import app.gubbins.mass.MassPlayer
import org.junit.jupiter.api.Test

/**
 * The rules the Destination card follows with the drops.
 *
 * The refresh control is the one view of every player the server
 * offers, so a swipe hides a row only until its next run: each run
 * forgets the drops and shows the whole answer of the server again.
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

    /** One player that the server could send, named by its id. */
    private fun player(playerId: String, type: String = MassPlayer.TYPE_PLAYER) =
        MassPlayer(playerId = playerId, name = playerId, type = type)
}
