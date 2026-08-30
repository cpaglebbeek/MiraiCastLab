package nl.icthorse.miraicastlab.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A surface-input AVC encoder used for one short local test clip.
 *
 * Scope: this measures what the phone's encoder does with frames that MediaProjection produced.
 * It is deliberately not a streamer - there is no network permission in this app and the
 * elementary stream is written to app-private storage for `adb pull` and nothing else.
 *
 * Synchronous MediaCodec is used rather than the async callback API because the interesting
 * numbers here are throughput and frame size, and a single dedicated drain thread makes the
 * measurement window unambiguous. The thread is bounded twice over (EOS flag and a hard wall
 * clock cap) so a codec that never emits EOS cannot hang the experiment.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val keyFrameIntervalSec: Int,
    private val outFile: File?,
) {

    /** Everything the report is allowed to say about this encode. */
    data class Result(
        val codecName: String,
        val hardwareAccelerated: Boolean?,
        val width: Int,
        val height: Int,
        val configuredBitRate: Int,
        val configuredFrameRate: Int,
        val configuredKeyFrameIntervalSec: Int,
        val negotiatedFormat: String?,
        val frames: Long,
        val keyFrames: Long,
        val codecConfigBytes: Long,
        val totalBytes: Long,
        val ptsSpanMs: Long,
        val wallClockMs: Long,
        val outputPath: String?,
        val writeError: String?,
        val error: String?,
    ) {
        /** Frames per second derived from the encoder's own presentation timestamps. */
        val encodeFps: Double
            get() = if (ptsSpanMs <= 0 || frames <= 1) 0.0 else (frames - 1) * 1000.0 / ptsSpanMs

        /** Bits per second actually written, against [configuredBitRate] which was only a request. */
        val measuredBitRate: Long
            get() = if (ptsSpanMs <= 0) 0L else totalBytes * 8L * 1000L / ptsSpanMs

        val averageFrameBytes: Long get() = if (frames <= 0) 0L else totalBytes / frames

        fun toLogDetails(): Map<String, String> = buildMap {
            put("codec", codecName)
            put("hardwareAccelerated", hardwareAccelerated?.toString() ?: "unknown")
            put("size", width.toString() + "x" + height)
            put("configuredBitRate", configuredBitRate.toString())
            put("measuredBitRate", measuredBitRate.toString())
            put("configuredFrameRate", configuredFrameRate.toString())
            put("keyFrameIntervalSec", configuredKeyFrameIntervalSec.toString())
            put("frames", frames.toString())
            put("keyFrames", keyFrames.toString())
            put("avgFrameBytes", averageFrameBytes.toString())
            put("totalBytes", totalBytes.toString())
            put("codecConfigBytes", codecConfigBytes.toString())
            put("encodeFps", String.format("%.2f", encodeFps))
            put("ptsSpanMs", ptsSpanMs.toString())
            put("wallClockMs", wallClockMs.toString())
            negotiatedFormat?.let { put("negotiatedFormat", it) }
            outputPath?.let { put("outputPath", it) }
            writeError?.let { put("writeError", it) }
            error?.let { put("error", it) }
        }

        /** Human-readable block for the on-screen evidence panel. */
        fun toReport(): String = buildString {
            appendLine("encoder            " + codecName)
            appendLine("hardware accel     " + (hardwareAccelerated?.toString() ?: "unknown"))
            appendLine("size               " + width + "x" + height)
            appendLine("bitrate requested  " + configuredBitRate + " bps")
            appendLine("bitrate measured   " + measuredBitRate + " bps")
            appendLine("framerate request  " + configuredFrameRate + " fps")
            appendLine("keyframe interval  " + configuredKeyFrameIntervalSec + " s")
            appendLine("encoded frames     " + frames + " (" + keyFrames + " key)")
            appendLine("avg frame size     " + averageFrameBytes + " bytes")
            appendLine("csd / SPS+PPS      " + codecConfigBytes + " bytes")
            appendLine("encode fps         " + String.format("%.2f", encodeFps))
            appendLine("pts span           " + ptsSpanMs + " ms")
            appendLine("wall clock         " + wallClockMs + " ms")
            appendLine("negotiated format  " + (negotiatedFormat ?: "not reported"))
            appendLine("elementary stream  " + (outputPath ?: "not written"))
            writeError?.let { appendLine("write error        " + it) }
            error?.let { appendLine("error              " + it) }
        }
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private var out: OutputStream? = null

    @Volatile private var eosSignalledAt = 0L
    @Volatile private var finished = false

    private var startedAt = 0L
    private var frames = 0L
    private var keyFrames = 0L
    private var totalBytes = 0L
    private var codecConfigBytes = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var negotiatedFormat: String? = null
    private var writeError: String? = null
    private var fatal: String? = null

    /**
     * Configures the encoder and returns the surface a VirtualDisplay should render into.
     * Throws on configuration failure: the caller reports that as ERROR, not as UNSUPPORTED,
     * unless the platform itself said the codec is absent.
     */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
        }

        val c = MediaCodec.createEncoderByType(MIME)
        codec = c
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        inputSurface = surface

        out = outFile?.let {
            try {
                BufferedOutputStream(FileOutputStream(it), 1 shl 16)
            } catch (t: Throwable) {
                writeError = t::class.java.simpleName + ": " + (t.message ?: "no message")
                null
            }
        }

        c.start()
        startedAt = SystemClock.elapsedRealtime()
        drainThread = Thread({ drain(c) }, "miraicast-h264-drain").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return surface
    }

    /** Tells the encoder no further frames will arrive on the input surface. */
    fun signalEndOfStream() {
        eosSignalledAt = SystemClock.elapsedRealtime()
        try {
            codec?.signalEndOfInputStream()
        } catch (t: Throwable) {
            Log.w(TAG, "signalEndOfInputStream failed", t)
        }
    }

    /**
     * Signals EOS, waits for the drain thread, releases everything and returns the measurement.
     * Safe to call twice; the second call returns the same numbers.
     */
    fun stopAndCollect(): Result {
        if (eosSignalledAt == 0L) signalEndOfStream()
        try {
            drainThread?.join(JOIN_TIMEOUT_MS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        finished = true
        val wallClock = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

        try {
            out?.flush()
        } catch (t: Throwable) {
            writeError = (writeError ?: "") + " flush: " + t::class.java.simpleName
        }
        closeQuietly()

        val name = try {
            codec?.name ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }
        val hw = try {
            codec?.codecInfo?.isHardwareAccelerated
        } catch (t: Throwable) {
            null
        }
        releaseCodec()

        val span = if (firstPtsUs >= 0 && lastPtsUs > firstPtsUs) (lastPtsUs - firstPtsUs) / 1000 else 0L
        return Result(
            codecName = name,
            hardwareAccelerated = hw,
            width = width,
            height = height,
            configuredBitRate = bitRate,
            configuredFrameRate = frameRate,
            configuredKeyFrameIntervalSec = keyFrameIntervalSec,
            negotiatedFormat = negotiatedFormat,
            frames = frames,
            keyFrames = keyFrames,
            codecConfigBytes = codecConfigBytes,
            totalBytes = totalBytes,
            ptsSpanMs = span,
            wallClockMs = wallClock,
            outputPath = if (totalBytes > 0) outFile?.absolutePath else null,
            writeError = writeError,
            error = fatal,
        )
    }

    /** Emergency teardown for the dispose path; never throws. */
    fun abandon() {
        finished = true
        try {
            drainThread?.join(500)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly()
        releaseCodec()
    }

    // ------------------------------------------------------------------ drain

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val hardStop = SystemClock.elapsedRealtime() + HARD_CAP_MS
        try {
            while (!finished) {
                if (SystemClock.elapsedRealtime() > hardStop) {
                    fatal = "drain exceeded " + HARD_CAP_MS + " ms without EOS"
                    break
                }
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        val eos = eosSignalledAt
                        // The encoder owes us a flush after EOS; if it stays silent, stop waiting
                        // and report what we have rather than blocking the tester.
                        if (eos != 0L && SystemClock.elapsedRealtime() - eos > EOS_GRACE_MS) break
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        negotiatedFormat = try {
                            c.outputFormat.toString()
                        } catch (t: Throwable) {
                            null
                        }
                    }

                    index >= 0 -> {
                        val buffer = try {
                            c.getOutputBuffer(index)
                        } catch (t: Throwable) {
                            null
                        }
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            write(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // SPS/PPS. Part of the elementary stream but not a frame.
                                codecConfigBytes += info.size
                            } else {
                                frames++
                                totalBytes += info.size
                                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyFrames++
                                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                                lastPtsUs = info.presentationTimeUs
                            }
                        }
                        try {
                            c.releaseOutputBuffer(index, false)
                        } catch (t: Throwable) {
                            Log.w(TAG, "releaseOutputBuffer failed", t)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            fatal = t::class.java.simpleName + ": " + (t.message ?: "no message")
            Log.w(TAG, "encoder drain failed", t)
        }
    }

    private fun write(bytes: ByteArray) {
        val stream = out ?: return
        try {
            stream.write(bytes)
        } catch (t: Throwable) {
            writeError = t::class.java.simpleName + ": " + (t.message ?: "no message")
            out = null
            try {
                stream.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun closeQuietly() {
        try {
            out?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing elementary stream failed", t)
        }
        out = null
    }

    private fun releaseCodec() {
        val c = codec ?: return
        try {
            c.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "codec stop failed", t)
        }
        try {
            c.release()
        } catch (t: Throwable) {
            Log.w(TAG, "codec release failed", t)
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "input surface release failed", t)
        }
        inputSurface = null
    }

    companion object {
        val MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TAG = "MiraiCastLab"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val EOS_GRACE_MS = 2_000L
        private const val HARD_CAP_MS = 30_000L
        private const val JOIN_TIMEOUT_MS = 4_000L
    }
}
