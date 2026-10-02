package com.openauto.dash

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import java.util.Base64

/**
 * The second screen's picture, made here: a private virtual display composed
 * straight into a hardware H.264 encoder, each access unit handed to [onPacket]
 * for DisplayLink to send. Whatever is put on the display (the cluster's
 * Presentation, an app's stack moved over, see SecondScreenController) is what
 * the Raspberry Pi shows.
 *
 * The display is the launcher's own, like HiddenDisplay: private, so only the
 * launcher (and the shell's `am display move-stack`) can put windows on it.
 * Unlike HiddenDisplay its windows survive it: when it is released, Android
 * moves an app still on it back onto the screen rather than closing it.
 */
internal class StreamDisplay private constructor(
    private val codec: MediaCodec,
    private val thread: HandlerThread,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    private lateinit var display: VirtualDisplay
    val displayId: Int get() = display.display.displayId
    val androidDisplay: android.view.Display get() = display.display

    @Volatile private var config: VideoConfig? = null
    @Volatile private var released = false

    /** The stream's parameter sets, once the encoder has produced them. */
    val videoConfig: VideoConfig? get() = config

    fun requestKeyFrame() {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(kbps: Int) {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) }) }
    }

    fun release() {
        if (released) return
        released = true
        if (::display.isInitialized) runCatching { display.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "StreamDisplay"
        private const val NAME = "Dashwheel second screen"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        /** A key frame at least this often, so a display that lost one recovers even if the request is lost. */
        private const val KEY_FRAME_SECONDS = 2
        /** An unchanging picture is still sent this often: the display's decoder never waits long. */
        private const val REPEAT_AFTER_US = 250_000L

        /**
         * A display of [width] × [height] at [dpi] streaming at [fps] and about
         * [bitrateKbps]; null when the encoder can't be had (the dash cam or the
         * 360° cameras may hold the only one). [onPacket] runs on the encoder's
         * thread; false means the frame could not be sent, and nothing more is
         * sent until the next key frame, which is asked for at once.
         */
        fun create(
            context: Context,
            width: Int,
            height: Int,
            dpi: Int,
            fps: Int,
            bitrateKbps: Int,
            onConfig: (VideoConfig) -> Unit,
            onPacket: (VideoPacket) -> Boolean,
            onFailed: (Throwable) -> Unit
        ): StreamDisplay? {
            val thread = HandlerThread("stream-display").apply { start() }
            var made: MediaCodec? = null
            try {
                val codec = MediaCodec.createEncoderByType(MIME).also { made = it }
                val stream = StreamDisplay(codec, thread, width, height, fps)
                // Asynchronous mode: the callback goes in before configure().
                val callbacks = stream.Callbacks(onConfig, onPacket, onFailed)
                configure(codec, width, height, fps, bitrateKbps) { codec.setCallback(callbacks, Handler(thread.looper)) }
                val surface = codec.createInputSurface()
                val dm = context.applicationContext.getSystemService(DisplayManager::class.java) ?: error("no display manager")
                val virtual = dm.createVirtualDisplay(
                    NAME, width, height, dpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                ) ?: error("virtual display refused")
                stream.display = virtual
                codec.start()
                Log.i(TAG, "streaming display ${virtual.display.displayId}: ${width}x$height @ $dpi dpi, $fps fps, $bitrateKbps kbit/s")
                return stream
            } catch (e: Exception) {
                Log.w(TAG, "no stream display", e)
                runCatching { made?.release() }
                thread.quitSafely()
                return null
            }
        }

        private fun configure(codec: MediaCodec, width: Int, height: Int, fps: Int, bitrateKbps: Int, setCallback: () -> Unit) {
            fun format(main: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
                // Real time, not throughput.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (main) {
                    // Main profile compresses better than Baseline, and the Pi's decoder takes it.
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
            }
            // Some encoders refuse a profile they would happily pick themselves.
            try {
                setCallback()
                codec.configure(format(main = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.i(TAG, "encoder refused Main profile, letting it choose: ${e.message}")
                codec.reset()
                setCallback()
                codec.configure(format(main = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }

    private inner class Callbacks(
        private val onConfig: (VideoConfig) -> Unit,
        private val onPacket: (VideoPacket) -> Boolean,
        private val onFailed: (Throwable) -> Unit
    ) : MediaCodec.Callback() {
        /** A frame could not be sent: the display can't decode what follows until a key frame. */
        private var waitingForKey = false

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            val buffer = try {
                codec.getOutputBuffer(index)
            } catch (e: IllegalStateException) {
                return
            }
            val bytes = if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                ByteArray(info.size).also { buffer.get(it) }
            } else {
                null
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
            if (bytes == null) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                publish(VideoConfig(width = width, height = height, fps = fps, csd = Base64.getEncoder().encodeToString(bytes)))
                return
            }
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (waitingForKey && !key) return
            waitingForKey = !onPacket(VideoPacket(key, info.presentationTimeUs, bytes))
            if (waitingForKey) requestKeyFrame()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Some encoders give the parameter sets here rather than in a config buffer.
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1")
            val bytes = ByteArray(sps.remaining() + (pps?.remaining() ?: 0))
            sps.duplicate().get(bytes, 0, sps.remaining())
            pps?.duplicate()?.get(bytes, sps.remaining(), pps.remaining())
            publish(VideoConfig(width = width, height = height, fps = fps, csd = Base64.getEncoder().encodeToString(bytes)))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "encoder failed", e)
            if (!released) onFailed(e)
        }

        private fun publish(config: VideoConfig) {
            this@StreamDisplay.config = config
            onConfig(config)
        }
    }
}
