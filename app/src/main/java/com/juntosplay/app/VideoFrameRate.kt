package com.juntosplay.app

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

internal class VideoFrameRate(private val onChanged: (VideoFrameRate) -> Unit) : VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    private var sampledFormat: Format? = null
    private var previousTimestampUs: Long? = null
    private var sampleCount = 0
    private var totalIntervalUs = 0L
    private var publishedRate = 0f
    private var measuredRate = 0f

    fun rate(format: Format?): Float = format?.frameRate?.takeIf(::validRate) ?: measuredRate

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    ) {
        if (sampledFormat !== format) {
            sampledFormat = format
            previousTimestampUs = null
            sampleCount = 0
            totalIntervalUs = 0L
            publish(format.frameRate.takeIf(::validRate) ?: 0f)
        }
        if (publishedRate > 0f) return

        // Some MPEG-TS streams omit FPS. Use a stable run of media timestamps,
        // independent of the refresh rate or how fast the decoder outputs frames.
        val previous = previousTimestampUs
        previousTimestampUs = presentationTimeUs
        if (previous == null) return
        val intervalUs = presentationTimeUs - previous
        if (intervalUs !in 4_166L..100_000L) {
            sampleCount = 0
            totalIntervalUs = 0L
            return
        }
        if (sampleCount > 0 && abs(intervalUs - totalIntervalUs / sampleCount) > 1_000L) {
            sampleCount = 0
            totalIntervalUs = 0L
        }
        totalIntervalUs += intervalUs
        sampleCount++
        if (sampleCount == 16) publish(1_000_000f * sampleCount / totalIntervalUs)
    }

    private fun publish(fps: Float) {
        if (publishedRate == fps) return
        publishedRate = fps
        main.post {
            measuredRate = fps
            onChanged(this)
        }
    }

    private fun validRate(fps: Float): Boolean = fps in 10f..240f
}
