package app.gubbins.sendspin.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The conversion of captured audio into the announced format.
 */
class PcmResamplerTest {
    @Test
    fun `a block that already matches is left alone`() {
        val samples = ShortArray(8) { it.toShort() }
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 2,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 2,
        )
        assertArrayEquals(samples, converted)
    }

    @Test
    fun `a mono block is copied into every channel`() {
        val samples = shortArrayOf(100, 200, 300)
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 1,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 2,
        )
        assertArrayEquals(shortArrayOf(100, 100, 200, 200, 300, 300), converted)
    }

    @Test
    fun `two channels mix down to one`() {
        val samples = shortArrayOf(100, 300, 200, 400)
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 2,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 1,
        )
        assertArrayEquals(shortArrayOf(200, 300), converted)
    }

    @Test
    fun `stereo stays stereo`() {
        // The two channels hold different signals, so a mix down would lose
        // the difference between them.
        val samples = shortArrayOf(100, 900, 200, 800)
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 2,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 2,
        )
        assertArrayEquals(samples, converted)
    }

    @Test
    fun `three channels mix down to two`() {
        val samples = shortArrayOf(100, 200, 300, 400, 500, 600)
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 3,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 2,
        )
        // The first channel keeps its own value, and the second one takes
        // the average of the two it replaced.
        assertArrayEquals(shortArrayOf(100, 250, 400, 550), converted)
    }

    @Test
    fun `a higher rate keeps the length`() {
        val samples = ShortArray(100) { (it * 100).toShort() }
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 1,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 1,
        )
        assertEquals(100, converted.size)
    }

    @Test
    fun `a lower rate makes the block shorter`() {
        val samples = ShortArray(1000) { it.toShort() }
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 1,
            sourceRate = 48000,
            targetRate = 24000,
            targetChannels = 1,
        )
        assertEquals(500, converted.size)
    }

    @Test
    fun `a higher rate makes the block longer`() {
        val samples = ShortArray(1000) { it.toShort() }
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 1,
            sourceRate = 24000,
            targetRate = 48000,
            targetChannels = 1,
        )
        assertEquals(2000, converted.size)
    }

    @Test
    fun `the resampled signal stays between the neighbouring samples`() {
        // A ramp from 0 to 1000 over 101 samples.
        val samples = ShortArray(101) { (it * 10).toShort() }
        val converted = PcmResampler.convert(
            samples = samples,
            channels = 1,
            sourceRate = 100,
            targetRate = 200,
            targetChannels = 1,
        )
        assertEquals(202, converted.size)
        // The first sample stays the same, and the last one lands on the
        // last source sample.
        assertEquals(0, converted[0].toInt())
        assertEquals(1000, converted[converted.size - 1].toInt())
        // The signal never leaves the range of the source.
        for (value in converted) {
            assertTrue(value.toInt() in 0..1000, "The sample $value left the range")
        }
    }

    @Test
    fun `an empty block stays empty`() {
        val converted = PcmResampler.convert(
            samples = ShortArray(0),
            channels = 2,
            sourceRate = 48000,
            targetRate = 48000,
            targetChannels = 2,
        )
        assertEquals(0, converted.size)
    }

    @Test
    fun `the block length follows the frame count`() {
        assertEquals(100, PcmResampler.framesFor(400, 2))
        assertEquals(400, PcmResampler.bytesFor(100, 2))
    }

    @Test
    fun `the block length follows the duration`() {
        // A tenth of a second at 48 kHz, in stereo, is 19200 bytes.
        assertEquals(19200, PcmResampler.bytesForDuration(100, 48000, 2))
    }
}
