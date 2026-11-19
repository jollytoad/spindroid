package app.gubbins.spindroid.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that turns the loudness of the capture into the share of
 * the row that the fill covers.
 *
 * The music an ordinary phone plays peaks far below the full scale of
 * a sample, so the curve bends to lift the quiet range: an ordinary
 * loudness must still move the edge of the fill, silence must fill
 * nothing, and a louder window must always fill more than a quieter
 * one.
 */
class LevelFillTest {

    @Test
    fun `silence fills nothing`() {
        assertEquals(0f, fillLevelOf(0))
    }

    @Test
    fun `the full scale fills the row`() {
        assertEquals(1f, fillLevelOf(32767))
    }

    @Test
    fun `an ordinary loudness fills half the row`() {
        // A quarter of the full scale is loud for music, and the curve
        // lifts it to half the row.
        assertEquals(0.5f, fillLevelOf(32767 / 4), 0.001f)
    }

    @Test
    fun `a louder window fills more than a quieter one`() {
        assertTrue(fillLevelOf(1000) < fillLevelOf(10000))
    }

    @Test
    fun `a sample beyond the scale fills no more than the row`() {
        assertEquals(1f, fillLevelOf(999999))
    }
}
