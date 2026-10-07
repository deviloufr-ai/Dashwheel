package com.openauto.dash.display

/**
 * The GStreamer chains, as `gst-launch-1.0` command lines reading stdin. Only
 * one runs at a time: on the Pi, whichever holds the screen (DRM master) is
 * the only one that can show anything.
 */
object Pipelines {

    const val LAUNCH = "gst-launch-1.0"

    /** DRM's plane rotation for a half turn (DRM_MODE_ROTATE_180). */
    private const val ROTATE_180 = 4

    /**
     * H.264 Annex-B on stdin to the screen. On the Pi, `v4l2h264dec` is the
     * VideoCore's hardware decoder (bcm2835-codec), whose NV12/I420 output
     * kmssink puts on a plane as it is, scaled by the display hardware.
     */
    fun video(config: DisplayConfig, upsideDown: Boolean = false, pixelShape: Pair<Int, Int> = 1 to 1): List<String> {
        config.videoPipeline?.let { return listOf(LAUNCH, "-q") + it.trim().split(Regex("\\s+")) }
        // A monitor mounted upside down. On the screen itself the display plane turns the
        // picture, for nothing; turning each frame in software (videoflip) took a whole
        // core of the Pi 3 at 15 frames a second, and the picture fell seconds behind.
        val turn = if (upsideDown) "videoflip video-direction=180 ! " else ""
        val planeTurn = if (upsideDown) " plane-properties=s,rotation=(int)$ROTATE_180" else ""
        // The pixel shape kmssink takes the monitor to have, given to the picture too: they cancel out ([PixelShape]).
        val shape = if (pixelShape == (1 to 1)) "" else "capssetter caps=video/x-raw,pixel-aspect-ratio=(fraction)${pixelShape.first}/${pixelShape.second} ! "
        val chain = when (config.sink) {
            DisplayConfig.Sink.KMS -> "v4l2h264dec ! $shape${config.sink.element}$planeTurn"
            DisplayConfig.Sink.AUTO -> "avdec_h264 ! videoconvert ! $turn${config.sink.element}"
        }
        // The caps go after h264parse: fdsrc gives none, and GStreamer 1.26 (Trixie)
        // refuses a filter there whose caps it can't fix.
        return launch("fdsrc fd=0 ! h264parse ! video/x-h264,stream-format=byte-stream,alignment=au ! $chain")
    }

    /**
     * Raw BGRx frames of [width] × [height] on stdin to the screen: the display's own pictures.
     * [pixelShape] as for [video]: without it, on a monitor reporting a made-up size, the
     * pictures came out narrowed and their right edge cut off.
     */
    fun frames(config: DisplayConfig, width: Int, height: Int, pixelShape: Pair<Int, Int> = 1 to 1): List<String> {
        val shape = if (pixelShape == (1 to 1) || config.sink != DisplayConfig.Sink.KMS) "" else " pixel-aspect-ratio=${pixelShape.first}/${pixelShape.second}"
        return launch(
            "fdsrc fd=0 blocksize=${width * height * 4} " +
                "! rawvideoparse width=$width height=$height format=bgrx framerate=5/1$shape " +
                "! videoconvert ! ${config.sink.element}"
        )
    }

    private fun launch(chain: String) = listOf(LAUNCH, "-q") + chain.split(' ').filter { it.isNotEmpty() }
}
