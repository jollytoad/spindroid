package app.gubbins.sendspin.codec

/**
 * Converts captured audio into the format the client announces.
 *
 * The device may capture at any rate and with any number of channels. The
 * server resamples and transcodes whatever the client sends, so the client
 * only has to be consistent. It announces one format, and this class
 * produces it.
 *
 * The resampling is a linear interpolation between the neighbouring
 * samples. It is not a high quality filter, but the server resamples the
 * audio again anyway, and a plain interpolation keeps the signal free of
 * the ringing that a sharp filter would leave behind.
 */
object PcmResampler {
    /**
     * Converts a block of audio.
     *
     * @param samples the captured samples, interleaved by channel
     * @param channels the number of channels in the samples
     * @param sourceRate the sample rate of the samples
     * @param targetRate the sample rate of the result
     * @param targetChannels the number of channels of the result
     * @return the converted samples, interleaved by channel
     */
    public fun convert(
        samples: ShortArray,
        channels: Int,
        sourceRate: Int,
        targetRate: Int,
        targetChannels: Int,
    ): ShortArray {
        if (channels <= 0 || targetChannels <= 0 || samples.isEmpty()) return ShortArray(0)
        val frames = samples.size / channels
        if (frames == 0) return ShortArray(0)

        // Too many channels means the extra ones mix down into the first
        // ones. Too few means the ones that are missing copy the last.
        val reduced = if (channels > targetChannels) {
            mixDown(samples, frames, channels, targetChannels)
        } else {
            samples
        }
        val reducedChannels = if (channels > targetChannels) targetChannels else channels

        val targetFrames = if (sourceRate == targetRate) {
            frames
        } else {
            ((frames.toLong() * targetRate) / sourceRate).toInt()
        }
        if (targetFrames <= 0) return ShortArray(0)

        val out = ShortArray(targetFrames * targetChannels)
        for (channel in 0 until reducedChannels) {
            val resampled = resampleChannel(reduced, frames, reducedChannels, channel, sourceRate, targetRate, targetFrames)
            for (frame in 0 until targetFrames) {
                out[frame * targetChannels + channel] = resampled[frame]
            }
        }
        // The channels that are missing copy the first one.
        for (channel in reducedChannels until targetChannels) {
            for (frame in 0 until targetFrames) {
                out[frame * targetChannels + channel] = out[frame * targetChannels]
            }
        }
        return out
    }

    /**
     * Mixes the channels down into the first ones.
     *
     * The first channels keep their own signal, so a stereo pair stays
     * distinct. The last channel takes the average of itself and the
     * channels that do not fit, so no part of the signal is lost.
     */
    private fun mixDown(
        samples: ShortArray,
        frames: Int,
        channels: Int,
        targetChannels: Int,
    ): ShortArray {
        val out = ShortArray(frames * targetChannels)
        // The last output channel takes the average of the source channels
        // from its own position onwards.
        val lastSource = targetChannels - 1
        for (frame in 0 until frames) {
            for (target in 0 until targetChannels) {
                out[frame * targetChannels + target] =
                    samples[frame * channels + target]
            }
            var sum = 0
            for (channel in lastSource until channels) {
                sum += samples[frame * channels + channel]
            }
            out[frame * targetChannels + lastSource] = (sum / (channels - lastSource)).toShort()
        }
        return out
    }

    /**
     * Resamples one channel to another rate.
     *
     * The output takes the nearest input sample, and interpolates between
     * the two neighbours for the samples in between.
     */
    private fun resampleChannel(
        samples: ShortArray,
        frames: Int,
        channels: Int,
        channel: Int,
        sourceRate: Int,
        targetRate: Int,
        targetFrames: Int,
    ): ShortArray {
        val out = ShortArray(targetFrames)
        if (sourceRate == targetRate) {
            for (frame in 0 until targetFrames) {
                val index = minOf(frame, frames - 1)
                out[frame] = samples[index * channels + channel]
            }
            return out
        }
        // The position in the source, as a fixed point number, so the
        // interpolation does not drift over a long block.
        val step = (sourceRate.toLong() shl 16) / targetRate
        var position = 0L
        for (index in 0 until targetFrames) {
            val whole = (position shr 16).toInt()
            if (whole >= frames - 1) {
                out[index] = samples[(frames - 1) * channels + channel]
                position += step
                continue
            }
            val fraction = (position and 0xFFFF).toInt()
            val low = samples[whole * channels + channel].toInt()
            val high = samples[(whole + 1) * channels + channel].toInt()
            out[index] = (low + ((high - low) * fraction shr 16)).toShort()
            position += step
        }
        return out
    }

    /**
     * The number of frames a block of the given length holds.
     *
     * @param bytes the length of the block, in bytes
     * @param channels the number of channels
     */
    public fun framesFor(bytes: Int, channels: Int): Int =
        if (channels <= 0) 0 else bytes / (2 * channels)

    /**
     * The length of a block of the given number of frames, in bytes.
     */
    public fun bytesFor(frames: Int, channels: Int): Int =
        if (channels <= 0) 0 else frames * 2 * channels

    /**
     * The length of a block of a given duration, in bytes.
     *
     * @param millis the duration of the block
     * @param sampleRate the sample rate
     * @param channels the number of channels
     */
    public fun bytesForDuration(millis: Int, sampleRate: Int, channels: Int): Int =
        bytesFor(sampleRate * millis / 1000, channels)
}
