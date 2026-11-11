package com.sendspin.sender.capture

import android.util.Log
import com.sendspin.protocol.session.SourceAudioFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes the captured audio to a file, so it can be studied offline.
 *
 * The file holds one record per chunk. Each record carries the local time of
 * the first sample, the time the server will play it, and the samples
 * themselves. That is exactly what the server receives, so a study of the
 * file shows what the server sees.
 *
 * The capture thread only builds a record and puts it on a queue. A writer
 * thread does the file work, so a slow disk cannot disturb the capture.
 */
class CaptureDebugRecorder(
    private val directory: File,
    private val format: SourceAudioFormat,
) {
    companion object {
        private const val TAG = "SendSpin"
        private const val FILE_NAME = "capture-debug.bin"

        /** The marker that starts the file. */
        private const val MAGIC = 0x42445353

        /** The version of the file layout. */
        private const val VERSION = 1

        /** The record type of an audio chunk. */
        private const val RECORD_AUDIO = 1

        /** The size of the file header. */
        private const val HEADER_BYTES = 16

        /** The size of the header of one record. */
        private const val RECORD_HEADER_BYTES = 39

        /** The queue holds this many records before records are dropped. */
        private const val QUEUE_DEPTH = 512

        /** The file stops growing at this size. */
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024

        /** How often the writer prints a summary. */
        private const val SUMMARY_MILLIS = 1000L
    }

    /** The file the records go to. */
    val file: File get() = File(directory, FILE_NAME)

    /** The records that are waiting for the writer. */
    private val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_DEPTH)

    /** The records the queue could not take. */
    private val dropped = AtomicLong()

    /** The records the writer has written. */
    private val written = AtomicLong()

    /** The writer thread. */
    private var writer: Thread? = null

    /** Whether the writer should keep going. */
    @Volatile
    private var running = false

    /**
     * Opens the file and starts the writer.
     *
     * The file starts empty, so every run gives a clean record.
     */
    fun start() {
        if (running) return
        directory.mkdirs()
        val output = FileOutputStream(file)
        output.write(buildHeader())
        running = true
        writer = Thread({ writeLoop(output) }, "sendspin-capture-dump").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
        Log.i(TAG, "The capture dump goes to ${file.absolutePath}")
    }

    /**
     * Stops the writer and closes the file.
     *
     * The records that are still on the queue are written first, so the file
     * holds every chunk that was accepted.
     */
    fun stop() {
        if (!running) return
        running = false
        writer?.join(2000)
        writer = null
        Log.i(TAG, "The capture dump holds ${written.get()} records")
    }

    /**
     * Adds a chunk to the dump.
     *
     * The call comes from the capture thread, so it only builds the record
     * and puts it on the queue.
     *
     * @param pcm the samples of the chunk
     * @param captureTimestampMicros the local time of the first sample
     * @param serverTimestampMicros the time the server will play the chunk
     * @param sendTimestampMicros the local time the chunk was handed over
     */
    fun record(
        pcm: ByteArray,
        captureTimestampMicros: Long,
        serverTimestampMicros: Long,
        sendTimestampMicros: Long,
    ) {
        if (!running) return
        val frames = pcm.size / format.bytesPerFrame
        val record = buildRecord(pcm, frames, captureTimestampMicros, serverTimestampMicros, sendTimestampMicros)
        if (!queue.offer(record)) {
            dropped.incrementAndGet()
        }
    }

    /** Builds the header that starts the file. */
    private fun buildHeader(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(format.channels)
        buffer.putInt(format.sampleRate)
        return buffer.array()
    }

    /** Builds the record of one chunk. */
    private fun buildRecord(
        pcm: ByteArray,
        frames: Int,
        captureTimestampMicros: Long,
        serverTimestampMicros: Long,
        sendTimestampMicros: Long,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(RECORD_HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RECORD_HEADER_BYTES + pcm.size)
        buffer.put(RECORD_AUDIO.toByte())
        buffer.putLong(captureTimestampMicros)
        buffer.putLong(serverTimestampMicros)
        buffer.putLong(sendTimestampMicros)
        buffer.putInt(frames)
        buffer.putShort(format.channels.toShort())
        buffer.putInt(format.sampleRate)
        buffer.put(pcm)
        return buffer.array()
    }

    /** Writes the records until the recorder stops. */
    private fun writeLoop(output: FileOutputStream) {
        val stream = BufferedOutputStream(output, 64 * 1024)
        var chunks = 0L
        var bytes = 0L
        var minDelta = Long.MAX_VALUE
        var maxDelta = Long.MIN_VALUE
        var sumDelta = 0L
        var lastServerTimestamp: Long? = null
        var lastSummary = System.currentTimeMillis()
        try {
            while (running || queue.isNotEmpty()) {
                val record = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                stream.write(record)
                bytes += record.size
                chunks++
                // The timestamp of the record sits at a known place, so the
                // writer can measure the spacing of the chunks without
                // asking the capture thread for it.
                val serverTimestamp = record.toServerTimestamp()
                val previous = lastServerTimestamp
                if (previous != null) {
                    val delta = serverTimestamp - previous
                    if (delta < minDelta) minDelta = delta
                    if (delta > maxDelta) maxDelta = delta
                    sumDelta += delta
                }
                lastServerTimestamp = serverTimestamp
                if (bytes >= MAX_FILE_BYTES) {
                    Log.w(TAG, "The capture dump reached its limit and stops")
                    running = false
                }
                val now = System.currentTimeMillis()
                if (now - lastSummary >= SUMMARY_MILLIS) {
                    val mean = if (chunks > 1) sumDelta / (chunks - 1) else 0
                    Log.i(
                        TAG,
                        "The capture dump holds $chunks chunks, the timestamp step is " +
                            "$minDelta to $maxDelta microseconds around $mean, " +
                            "${dropped.get()} records were dropped",
                    )
                    lastSummary = now
                }
            }
            stream.flush()
        } catch (error: Exception) {
            Log.e(TAG, "The capture dump could not be written", error)
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Reads the server timestamp out of a record.
     *
     * The timestamp sits at byte 13, and every field in the file is
     * little-endian.
     */
    private fun ByteArray.toServerTimestamp(): Long {
        if (size < RECORD_HEADER_BYTES) return 0
        val buffer = ByteBuffer.wrap(this, 13, 8).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.long
    }}
