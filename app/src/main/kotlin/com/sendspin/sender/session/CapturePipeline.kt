package com.sendspin.sender.session

import com.sendspin.protocol.codec.PcmChunker
import com.sendspin.protocol.codec.PcmResampler
import com.sendspin.protocol.session.SourceAudioFormat
import com.sendspin.sender.capture.CaptureListener
import com.sendspin.sender.capture.PcmBlock

/**
 * Turns captured audio into the chunks that the protocol carries.
 *
 * The capture may hand over a different sample rate or channel count than the
 * session announces, so every block is converted first. The samples then wait
 * until a chunk is full.
 *
 * The pipeline also measures the capture. The status panel shows how many
 * blocks arrive and how loud they are, so a silent capture can be told apart
 * from a capture that never started.
 *
 * Both connection directions use this class: the client that dials out, and
 * the server that waits for a server to dial in.
 */
class CapturePipeline(
    private val format: SourceAudioFormat,
    private val onChunk: (pcm: ByteArray, timestampMicros: Long) -> Unit,
    private val onCaptureError: (String) -> Unit,
    private val onMeasurements: (blocksPerSecond: Int, peak: Int) -> Unit = { _, _ -> },
) : CaptureListener {
    private val lock = Any()
    private var chunker = newChunker()

    /** The blocks that arrived in the current window. */
    private var blocksInWindow = 0

    /** The time the current window started, in microseconds. */
    private var windowStartMicros = 0L

    /** The loudest sample of the current window. */
    private var peak = 0

    override fun onBlock(block: PcmBlock) {
        measure(block.samples)
        val converted = PcmResampler.convert(
            samples = block.samples,
            channels = block.channels,
            sourceRate = block.sampleRate,
            targetRate = format.sampleRate,
            targetChannels = format.channels,
        )
        // The reset may run on another thread, so the chunker is used under
        // the lock. The chunks are handed over after the lock is released,
        // because the receiver may take its own lock.
        val chunks = synchronized(lock) {
            chunker.offer(converted, block.captureTimestampMicros)
        }
        for (chunk in chunks) {
            onChunk(toBytes(chunk.samples), chunk.timestampMicros)
        }
    }

    override fun onError(message: String) {
        onCaptureError(message)
    }

    /**
     * Drops the samples that wait for a full chunk.
     *
     * The caller resets the pipeline when the session changes, so a new
     * session does not receive samples that were captured for the old one.
     */
    fun reset() {
        synchronized(lock) {
            chunker = newChunker()
        }
    }

    /**
     * Counts the block and measures the level.
     *
     * Only the capture thread writes these values, so no lock is needed.
     */
    private fun measure(samples: ShortArray) {
        val now = System.nanoTime() / 1000
        if (windowStartMicros == 0L) windowStartMicros = now
        blocksInWindow++
        val level = peakLevel(samples)
        if (level > peak) peak = level
        if (now - windowStartMicros < WINDOW_MICROS) return
        // The window is a quarter of a second, so the count is four times
        // the number of blocks per second.
        val perSecond = blocksInWindow * (1_000_000L / WINDOW_MICROS)
        val loudest = peak
        blocksInWindow = 0
        windowStartMicros = now
        peak = 0
        onMeasurements(perSecond.toInt(), loudest)
    }

    /** The loudest sample of the block. */
    private fun peakLevel(samples: ShortArray): Int {
        var loudest = 0
        for (sample in samples) {
            val value = if (sample < 0) -sample.toInt() else sample.toInt()
            if (value > loudest) loudest = value
        }
        return loudest
    }

    private fun newChunker(): PcmChunker =
        PcmChunker(sampleRate = format.sampleRate, channels = format.channels)

    /** Turns the samples into the little-endian bytes the protocol carries. */
    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var index = 0
        for (sample in samples) {
            out[index] = (sample.toInt() and 0xFF).toByte()
            out[index + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            index += 2
        }
        return out
    }

    private companion object {
        /** The length of the window that the measurements cover. */
        const val WINDOW_MICROS = 250_000L
    }
}
