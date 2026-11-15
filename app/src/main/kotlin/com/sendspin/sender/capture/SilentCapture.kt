package com.sendspin.sender.capture

/**
 * A capture that produces no audio.
 *
 * The pairing runs before the user allows the screen capture. Music
 * Assistant only needs the connection then, so this capture keeps the
 * listener's contract without touching the microphone or the media
 * projection.
 */
class SilentCapture : AudioCapture {
    @Volatile
    private var running = false

    override fun start(listener: CaptureListener) {
        running = true
    }

    override fun stop() {
        running = false
    }

    override val isRunning: Boolean get() = running
}
