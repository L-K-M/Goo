package ch.lkmc.goo.engine.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import ch.lkmc.goo.engine.core.MovieSpec
import java.io.File

/**
 * One H.264/MP4 encode session: MediaCodec in surface-input mode plus
 * MediaMuxer, driven synchronously by whoever renders into [inputSurface]
 * (the GL thread — PLAN.md §5: frames render straight into the encoder
 * surface, no pixel copies).
 *
 * Lifecycle: create → render/swap per frame with [drain] false → [finish]
 * → [release]. Sync-mode MediaCodec has no Looper requirement, so a
 * single foreign thread owning every call is legal and keeps the whole
 * session GL-thread-confined.
 */
class MovieEncoder(width: Int, height: Int, outputFile: File) {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    val inputSurface: Surface

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var muxerStopped = false

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, MovieSpec.BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, MovieSpec.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, MovieSpec.I_FRAME_INTERVAL_SECONDS)
        }
        // Locals until every step succeeds: if the muxer ctor throws (bad
        // path, disk full) after codec.start(), the half-built instance is
        // dropped and release() is unreachable — leaking a HARDWARE codec,
        // a scarce system resource that can pin the encoder until reboot.
        var newCodec: MediaCodec? = null
        var newSurface: Surface? = null
        try {
            newCodec = MediaCodec.createEncoderByType(MIME)
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newSurface = newCodec.createInputSurface()
            newCodec.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            runCatching { newSurface?.release() }
            runCatching { newCodec?.stop() }
            runCatching { newCodec?.release() }
            throw e
        }
        codec = newCodec
        inputSurface = checkNotNull(newSurface)
    }

    /**
     * Move whatever the codec has produced into the muxer. With
     * [endOfStream] the call blocks until the codec confirms EOS —
     * always after signalEndOfInputStream (see [finish]).
     */
    fun drain(endOfStream: Boolean) {
        val timeout = if (endOfStream) DRAIN_TIMEOUT_US else 0L
        var eosWaits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // EOS requested but not yet seen: keep waiting — but
                    // bounded. A hung codec must fail the export, not park
                    // the GL thread forever (pause would then block main).
                    check(++eosWaits <= MAX_EOS_WAITS) { "encoder never signalled EOS" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer == null) {
                        // Contract says non-null for a valid index; if a
                        // driver disagrees, give the buffer back rather
                        // than draining the output pool.
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The muxer takes csd from the format; raw config
                        // buffers must not be written as samples.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        check(muxerStarted) { "sample before output format" }
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    /**
     * Signal EOS, drain the tail, and finalize the MP4 container. A successful
     * return means MediaMuxer.stop() also succeeded; callers must not report a
     * playable result before that final index/metadata write completes.
     */
    fun finish() {
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
        check(muxerStarted) { "encoder produced no output format" }
        muxer.stop()
        muxerStopped = true
    }

    /** Release everything; safe after failures (best-effort teardown). */
    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        // Failure/abort path only. finish() performs the throwing stop used
        // to decide success; teardown must not mask the original exception.
        runCatching { if (muxerStarted && !muxerStopped) muxer.stop() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DRAIN_TIMEOUT_US = 10_000L

        /** ~5s of 10ms waits before declaring the encoder hung. */
        const val MAX_EOS_WAITS = 500
    }
}
