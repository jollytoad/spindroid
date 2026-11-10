package com.sendspin.sender.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The microphone.
 *
 * The source is the voice source, which leaves the automatic gain control
 * off. The server gets a flat signal, so it does not have to fight the
 * phone's idea of what a quiet room sounds like.
 */
class MicrophoneCapture(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : AudioCapture {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    override fun start(listener: CaptureListener) {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "The device does not support the microphone capture")
            listener.onError("The device does not support the microphone capture")
            return
        }
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        Log.i(TAG, "The microphone record has the state ${audioRecord.state}")
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            Log.e(TAG, "The microphone capture did not start")
            listener.onError("The microphone capture did not start")
            return
        }
        record = audioRecord
        running = true
        audioRecord.startRecording()
        Log.i(
            TAG,
            "The microphone capture is recording: the state is ${audioRecord.recordingState}, " +
                "the buffer holds ${audioRecord.bufferSizeInFrames} frames",
        )
        thread = Thread({ readLoop(audioRecord, listener) }, "sendspin-microphone").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    override fun stop() {
        if (!running) return
        running = false
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
                    Log.e(TAG, "The microphone capture failed with the code $read")
                    listener.onError("The microphone capture failed")
                    break
                }
                continue
            }
            if (first) {
                first = false
                Log.i(TAG, "The microphone capture read its first block of $read bytes")
            }
            val frames = read / BYTES_PER_FRAME
            val now = System.nanoTime() / 1000
            // The read hands over the frames that the buffer holds, so the
            // first of them is one block old. The time of the first sample
            // is what the protocol carries.
            val captureMicros = now - (frames.toLong() * 1_000_000L) / sampleRate
            val samples = ShortArray(frames)
            buffer.position(0)
            buffer.limit(read)
            buffer.asShortBuffer().get(samples)
            blocks++
            if (now - windowStart >= WINDOW_MICROS) {
                Log.i(TAG, "The microphone capture read $blocks blocks in the last second")
                blocks = 0
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

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_FRAME = 2 * CHANNEL_COUNT
        const val THREAD_JOIN_MILLIS = 1000L
        const val WINDOW_MICROS = 1_000_000L
        const val TAG = "SendSpin"
    }
}
