package com.openauto.dash.display

/**
 * The GStreamer chains, as `gst-launch-1.0` command lines reading stdin. Only
 * one runs at a time: on the Pi, whichever holds the screen (DRM master) is
 * the only one that can show anything.
 */
object Pipelines {

    const val LAUNCH = "gst-launch-1.0"

    /**
     * H.264 Annex-B on stdin to the screen. On the Pi, `v4l2h264dec` is the
     * VideoCore's hardware decoder (bcm2835-codec), whose NV12/I420 output
     * kmssink puts on a plane as it is, scaled by the display hardware.
     */
    fun video(config: DisplayConfig): List<String> {
        config.videoPipeline?.let { return listOf(LAUNCH, "-q") + it.trim().split(Regex("\\s+")) }
        val chain = when (config.sink) {
            DisplayConfig.Sink.KMS -> "h264parse ! v4l2h264dec ! ${config.sink.element}"
            DisplayConfig.Sink.AUTO -> "h264parse ! avdec_h264 ! videoconvert ! ${config.sink.element}"
        }
        return launch("fdsrc fd=0 ! video/x-h264,stream-format=byte-stream,alignment=au ! $chain")
    }

    /** Raw BGRx frames of [width] × [height] on stdin to the screen: the display's own pictures. */
    fun frames(config: DisplayConfig, width: Int, height: Int): List<String> =
        launch(
            "fdsrc fd=0 blocksize=${width * height * 4} " +
                "! rawvideoparse width=$width height=$height format=bgrx framerate=5/1 " +
                "! videoconvert ! ${config.sink.element}"
        )

    private fun launch(chain: String) = listOf(LAUNCH, "-q") + chain.split(' ').filter { it.isNotEmpty() }
}
