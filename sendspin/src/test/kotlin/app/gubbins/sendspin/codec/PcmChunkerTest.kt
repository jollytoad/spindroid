package app.gubbins.sendspin.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The splitting of the captured audio into chunks.
 */
class PcmChunkerTest {
    @Test
    fun `a chunk holds the requested number of frames`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        assertEquals(960, chunker.framesPerChunk)
    }

    @Test
    fun `the chunk length stays inside the bounds the specification gives`() {
        for (millis in listOf(5, 20, 50, 100, 150)) {
            val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = millis)
            val frames = chunker.framesPerChunk
            val length = frames.toDouble() / 48000.0 * 1000.0
            assertTrue(length >= 5.0, "A chunk of $length ms is too short")
            assertTrue(length <= 150.0, "A chunk of $length ms is too long")
        }
    }

    @Test
    fun `a full block becomes one chunk`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        val block = ShortArray(960 * 2) { it.toShort() }
        val chunks = chunker.offer(block, 1_000L)
        assertEquals(1, chunks.size)
        assertEquals(960 * 2, chunks[0].samples.size)
        assertEquals(1_000L, chunks[0].timestampMicros)
    }

    @Test
    fun `a small block waits for more`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        val chunks = chunker.offer(ShortArray(100), 1_000L)
        assertTrue(chunks.isEmpty())
        assertEquals(100, chunker.pendingSamples)
    }

    @Test
    fun `several blocks fill a chunk`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        val block = ShortArray(960 * 2) { it.toShort() }
        assertTrue(chunker.offer(ShortArray(500), 1_000L).isEmpty())
        val chunks = chunker.offer(block, 2_000L)
        assertEquals(1, chunks.size)
        // The chunk carries the time of the first block that filled it.
        assertEquals(1_000L, chunks[0].timestampMicros)
    }

    @Test
    fun `a large block becomes several chunks`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        val block = ShortArray(960 * 2 * 3) { it.toShort() }
        val chunks = chunker.offer(block, 1_000L)
        assertEquals(3, chunks.size)
        for (chunk in chunks) {
            assertEquals(960 * 2, chunk.samples.size)
        }
    }

    @Test
    fun `the chunks of one block carry their own time`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        val block = ShortArray(960 * 2 * 3) { it.toShort() }
        val chunks = chunker.offer(block, 1_000L)
        assertEquals(3, chunks.size)
        // Every chunk holds twenty milliseconds, so every chunk starts twenty
        // milliseconds after the one before. A server that plays the chunks
        // at their time then hears a smooth sound.
        assertEquals(1_000L, chunks[0].timestampMicros)
        assertEquals(21_000L, chunks[1].timestampMicros)
        assertEquals(41_000L, chunks[2].timestampMicros)
    }

    @Test
    fun `the samples keep their order`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 1, chunkMillis = 20)
        val block = ShortArray(960) { (it % 100).toShort() }
        val chunks = chunker.offer(block, 1_000L)
        assertEquals(1, chunks.size)
        for (index in 0 until 960) {
            assertEquals((index % 100).toShort(), chunks[0].samples[index])
        }
    }

    @Test
    fun `the leftover is returned at the end`() {
        val chunker = PcmChunker(sampleRate = 48000, channels = 2, chunkMillis = 20)
        chunker.offer(ShortArray(100), 1_000L)
        val chunks = chunker.flush()
        assertEquals(1, chunks.size)
        assertEquals(100, chunks[0].samples.size)
        assertEquals(1_000L, chunks[0].timestampMicros)
        // The buffer is empty again.
        assertTrue(chunker.flush().isEmpty())
    }

    @Test
    fun `a chunk of a different length still holds the frames`() {
        val chunker = PcmChunker(sampleRate = 44100, channels = 2, chunkMillis = 20)
        assertEquals(882, chunker.framesPerChunk)
        val chunks = chunker.offer(ShortArray(882 * 2), 1_000L)
        assertEquals(1, chunks.size)
        assertEquals(882 * 2, chunks[0].samples.size)
    }
}
