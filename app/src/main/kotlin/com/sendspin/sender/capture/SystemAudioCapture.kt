package com.sendspin.sender.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
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
            listener.onError("The device does not support the system audio capture")
            return
        }
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            // The playback capture configuration tells the record to take
            // the audio that the system is playing.
            .setAudioPlaybackCaptureConfig(configuration)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            listener.onError("The system audio capture did not start")
            return
        }
        record = audioRecord
        running = true
        mediaProjection.registerCallback(callback, handler)
        audioRecord.startRecording()
        thread = Thread({ readLoop(audioRecord, listener) }, "sendspin-capture").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }
    override fun stop() {
        if (!running) return
        running = false
        try {
            mediaProjection.unregisterCallback(callback)
        } catch (_: IllegalStateException) {
            // The projection was already gone.
        }
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
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            buffer.clear()
            val read = audioRecord.read(buffer, bufferSize, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (!running) break
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    listener.onError("The system audio capture failed")
                    break
                }
                continue
            }
            val samples = ShortArray(read / BYTES_PER_FRAME)
            buffer.position(0)
            buffer.limit(read)
            buffer.asShortBuffer().get(samples)
            listener.onBlock(
                PcmBlock(
                    samples = samples,
                    channels = CHANNEL_COUNT,
                    sampleRate = sampleRate,
                    captureTimestampMicros = System.nanoTime() / 1000,
                ),
            )
        }
    }

    /** Ends the capture when the projection ends. */
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            running = false
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
    }
}
