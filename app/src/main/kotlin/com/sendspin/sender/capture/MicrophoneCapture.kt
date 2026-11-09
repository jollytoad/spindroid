package com.sendspin.sender.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
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
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            listener.onError("The microphone capture did not start")
            return
        }
        record = audioRecord
        running = true
        audioRecord.startRecording()
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
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            buffer.clear()
            val read = audioRecord.read(buffer, bufferSize, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (!running) break
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    listener.onError("The microphone capture failed")
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

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_FRAME = 2 * CHANNEL_COUNT
        const val THREAD_JOIN_MILLIS = 1000L
    }
}
