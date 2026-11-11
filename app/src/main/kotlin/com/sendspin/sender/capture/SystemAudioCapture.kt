package com.sendspin.sender.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The system audio, through a media projection.
 *
 * The capture reads the audio that the system is playing, so it can carry
 * music, a video, or a call to the server.
 */
class SystemAudioCapture(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : AudioCapture {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override val isRunning: Boolean get() = running

    /**
     * Starts the capture.
     *
     * The projection callback stops the capture when the user ends the
     * projection, which happens when the screen is locked or the projection
     * is revoked.
     */
    @SuppressLint("MissingPermission")
    override fun start(listener: CaptureListener) {
        if (running) return
        // Android 14 asks for the callback before the record is built. A
        // record that was made without one fails at the first read with
        // the code -2, which means an invalid operation.
        mediaProjection.registerCallback(callback, handler)
        callbackRegistered = true
        val configuration = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "The device does not support the system audio capture")
            releaseCallback()
            listener.onError("The device does not support the system audio capture")
            return
        }
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            // The playback capture configuration tells the record to take
            // the audio that the system is playing. Android ignores the audio
            // source of a record that has this configuration, so the builder
            // must not name one. The record reads the mixed output.
            .setAudioPlaybackCaptureConfig(configuration)
            .build()
        Log.i(TAG, "The system audio record has the state ${audioRecord.state}")
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            Log.e(TAG, "The system audio capture did not start")
            releaseCallback()
            listener.onError("The system audio capture did not start")
            return
        }
        record = audioRecord
        running = true
        audioRecord.startRecording()
        Log.i(
            TAG,
            "The system audio capture is recording: the state is ${audioRecord.recordingState}, " +
                "the buffer holds ${audioRecord.bufferSizeInFrames} frames",
        )
        thread = Thread({ readLoop(audioRecord, listener) }, "sendspin-capture").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    override fun stop() {
        if (!running) {
            releaseCallback()
            return
        }
        running = false
        releaseCallback()
        thread?.join(THREAD_JOIN_MILLIS)
        thread = null
        record?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
                // The recorder was not recording.
            }
            recorder.release()
        }
        record = null
    }

    /** Reads the audio until the capture stops. */
    private fun readLoop(audioRecord: AudioRecord, listener: CaptureListener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val bufferSize = audioRecord.bufferSizeInFrames * BYTES_PER_FRAME
        // The buffer must be a direct one. A buffer in the heap makes the
        // read fail at once with the code -2.
        val buffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        var blocks = 0
        var smallestRead = Int.MAX_VALUE
        var largestRead = 0
        var windowStart = System.nanoTime() / 1000
        var first = true
        while (running) {
            buffer.clear()
            val read = audioRecord.read(buffer, bufferSize, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (!running) break
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "The system audio capture failed with the code $read")
                    listener.onError("The system audio capture failed")
                    break
                }
                continue
            }
            if (first) {
                first = false
                Log.i(TAG, "The system audio capture read its first block of $read bytes")
            }
            val frames = read / BYTES_PER_FRAME
            val now = System.nanoTime() / 1000
            // The read hands over the frames that the buffer holds, so the
            // first of them is one block old. The time of the first sample
            // is what the protocol carries.
            val captureMicros = now - (frames.toLong() * 1_000_000L) / sampleRate
            // A frame holds one sample for each channel, so the block needs
            // two shorts for every frame. A shorter array would take only
            // the first half of the read and drop the rest without a word.
            val samples = ShortArray(frames * CHANNEL_COUNT)
            buffer.position(0)
            buffer.limit(read)
            val shorts = buffer.asShortBuffer()
            if (shorts.remaining() < samples.size) {
                // The read gave fewer samples than the block needs. The
                // audio would be short, so the capture stops rather than
                // sending a hole.
                Log.e(TAG, "The system audio capture read $read bytes, which is too few")
                listener.onError("The system audio capture read a short block")
                break
            }
            shorts.get(samples)
            blocks++
            if (read < smallestRead) smallestRead = read
            if (read > largestRead) largestRead = read
            if (now - windowStart >= WINDOW_MICROS) {
                Log.i(
                    TAG,
                    "The system audio capture read $blocks blocks of $smallestRead to $largestRead bytes " +
                        "in the last second",
                )
                blocks = 0
                smallestRead = Int.MAX_VALUE
                largestRead = 0
                windowStart = now
            }
            listener.onBlock(
                PcmBlock(
                    samples = samples,
                    channels = CHANNEL_COUNT,
                    sampleRate = sampleRate,
                    captureTimestampMicros = captureMicros,
                ),
            )
        }
    }

    /** Ends the capture when the projection ends. */
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "The media projection stopped")
            running = false
        }
    }

    /** Whether the callback sits with the projection. */
    @Volatile
    private var callbackRegistered = false

    /** Gives the callback back to the projection. */
    private fun releaseCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        try {
            mediaProjection.unregisterCallback(callback)
        } catch (_: IllegalStateException) {
            // The projection was already gone.
        }
    }

    /**
     * The thread the projection calls back on.
     *
     * The callback arrives on the thread that registered it, so the
     * handler keeps it off the capture thread.
     */
    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_FRAME = 2 * CHANNEL_COUNT
        const val THREAD_JOIN_MILLIS = 1000L
        const val WINDOW_MICROS = 1_000_000L
        const val TAG = "SendSpin"
    }
}
