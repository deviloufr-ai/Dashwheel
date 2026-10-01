package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Dashwheel's own Gemini: a spoken conversation over the Gemini Live API
 * (one WebSocket: the microphone goes up as 16 kHz PCM, Gemini's voice comes
 * back as 24 kHz PCM) that knows the car. Gemini is given everything
 * Dashwheel knows (CarFacts) when the conversation opens and whenever it
 * asks again, and can do whatever a steering-wheel button can, switch
 * dashboard, open an app, start guidance anywhere and search the web.
 *
 * Shown by GeminiLive's alert, in its chosen design. The car's speakers are
 * next to its microphone: the microphone is not sent while Gemini speaks, so
 * it never hears itself; the driver speaks once it has finished (or ends it).
 */
internal object DashAssistant {
    private const val TAG = "DashAssistant"
    private const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="

    /** Best first; one that refuses the setup gives way to the next. */
    private val MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")

    private const val IN_RATE = 16_000
    private const val OUT_RATE = 24_000
    /** The microphone goes up in pieces of this long. */
    private const val CHUNK_MS = 100
    /** After Gemini's last word, the car's echo dies down before the microphone is sent again. */
    private const val ECHO_TAIL_MS = 400L
    private const val SETUP_WAIT_MS = 10_000L

    enum class Phase { CONNECTING, LISTENING, THINKING, SPEAKING, FAILED, CLOSED }

    /** One line of the conversation, as heard: the driver's, or Gemini's. */
    data class Line(val driver: Boolean, val text: String)

    private val _phase = MutableStateFlow(Phase.CLOSED)
    val phase: StateFlow<Phase> = _phase

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    /** The conversation so far, newest last (the last few). */
    val lines: StateFlow<List<Line>> = _lines

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null

    @Volatile private var socket: WebSocket? = null
    @Volatile private var speakingUntil = 0L
    /** The line being heard is still growing (transcripts come in pieces). */
    @Volatile private var lineOpen = false
    private var track: AudioTrack? = null
    private val playback = Channel<ByteArray>(Channel.UNLIMITED)
    private var focus: AudioFocusRequest? = null

    private val client: OkHttpClient = Http.client.newBuilder()
        .dns(Ipv4First)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Whether Dashwheel's Gemini can talk now: a Gemini key, and the microphone allowed. */
    fun ready(context: Context): Boolean =
        AiSettings.load(context).apiKey.isNotBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Opens the conversation. Main thread. */
    fun start(context: Context) {
        if (session?.isActive == true) return
        val app = context.applicationContext
        val config = AiSettings.load(app)
        _lines.value = emptyList()
        _phase.value = Phase.CONNECTING
        session = scope.launch {
            var opened = false
            for (model in MODELS) {
                if (!isActive) return@launch
                opened = converse(app, config, model)
                if (opened) break
            }
            if (!opened && isActive) {
                _phase.value = Phase.FAILED
                withContext(Dispatchers.Main) { HandsFree.say(app, R.string.ai_live_failed) }
                delay(4_000)
                withContext(Dispatchers.Main) { GeminiLive.end(app) }
            }
        }
    }

    /** Ends the conversation. */
    fun stop(context: Context) {
        session?.cancel()
        session = null
        socket?.close(1000, "ended")
        socket = null
        releaseAudio(context)
        _phase.value = Phase.CLOSED
    }

    /**
     * One session with [model]: true once Gemini accepted it (whatever
     * happened after), false when it refused before that and the next model
     * may be tried.
     */
    private suspend fun converse(context: Context, config: AiConfig, model: String): Boolean {
        val events = Channel<JSONObject?>(Channel.UNLIMITED)
        val ws = client.newWebSocket(Request.Builder().url(URL + config.apiKey.trim()).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { events.trySend(JSONObject(text)) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { events.trySend(JSONObject(bytes.utf8())) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "$model closed: $code $reason")
                webSocket.close(1000, null)
                events.trySend(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "$model failed: ${response?.code} ${t.message}")
                events.trySend(null)
            }
        })
        socket = ws
        ws.send(setup(context, config, model).toString())
        val first = withTimeoutOrNull(SETUP_WAIT_MS) { events.receive() }
        if (first == null || !first.has("setupComplete")) {
            Log.w(TAG, "$model refused the setup: $first")
            ws.cancel()
            return false
        }
        Log.i(TAG, "talking with $model")
        openAudio(context)
        _phase.value = Phase.LISTENING
        val mic = scope.launch { listen(ws) }
        val player = scope.launch { play() }
        try {
            while (true) {
                val message = events.receive() ?: break
                handle(context, ws, message)
            }
        } finally {
            mic.cancel()
            player.cancel()
            ws.cancel()
        }
        // Closed by Google or the network: the alert goes too.
        if (_phase.value != Phase.CLOSED) withContext(Dispatchers.Main) { GeminiLive.end(context) }
        return true
    }

    private fun setup(context: Context, config: AiConfig, model: String): JSONObject {
        val facts = runCatching { CarFacts.snapshot(context).toString() }.getOrDefault("{}")
        val instruction = """
            You are Dashwheel, the voice assistant built into the head unit of the driver's car. You talk with the
            driver while they drive: answer in ${config.language.promptName}, in one or two short spoken sentences,
            never lists, markdown or symbols. Use the units in "units". You know everything the dashboard knows:
            here it is as the conversation opens. Call get_everything for fresh values (they change while driving)
            before answering about the car, the trip, the guidance, the music or the phone. Act with the tools when
            asked: do_action for media, volume, calls, messages and the screen; show_dashboard; open_app;
            navigate_to. For anything else, answer like a helpful assistant, using Google Search when it helps.
            If something isn't known, say so plainly. When the driver says goodbye or is done, call end_conversation.
            Dashboard now: $facts
        """.trimIndent()
        return JSONObject().put(
            "setup",
            JSONObject()
                .put("model", "models/$model")
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
                .put("tools", AssistantTools.declarations())
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
        )
    }

    private suspend fun handle(context: Context, ws: WebSocket, message: JSONObject) {
        message.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (data.isNotEmpty()) speak(Base64.decode(data, Base64.DEFAULT))
                }
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                hear(driver = true, it)
                if (_phase.value == Phase.LISTENING) _phase.value = Phase.THINKING
            }
            content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { hear(driver = false, it) }
            if (content.optBoolean("interrupted")) hush()
            if (content.optBoolean("turnComplete")) lineOpen = false
        }
        message.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val replies = JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                Log.i(TAG, "tool $name $args")
                val result = runCatching {
                    withContext(Dispatchers.Main) { AssistantTools.run(context, name, args) }
                }.getOrElse { JSONObject().put("error", it.message ?: it.toString()) }
                replies.put(JSONObject().put("id", call.optString("id")).put("name", name).put("response", JSONObject().put("result", result)))
            }
            ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", replies)).toString())
        }
        message.optJSONObject("goAway")?.let { Log.i(TAG, "Google will close the session soon: $it") }
    }

    /** Adds [text] to the conversation: to the line being heard, or as a new one. */
    private fun hear(driver: Boolean, text: String) {
        val all = _lines.value
        val last = all.lastOrNull()
        _lines.value = if (last != null && last.driver == driver && lineOpen) {
            all.dropLast(1) + last.copy(text = (last.text + text).replace(Regex("\\s+"), " "))
        } else {
            (all + Line(driver, text.trim())).takeLast(8)
        }
        lineOpen = true
    }

    /** Sends the microphone up, except while Gemini speaks and just after. */
    private suspend fun listen(ws: WebSocket) {
        val bytes = IN_RATE * 2 * CHUNK_MS / 1000
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, bytes * 4))
        }.getOrNull() ?: return
        try {
            record.startRecording()
            val buffer = ByteArray(bytes)
            while (scope.isActive && session?.isActive == true) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                if (now < speakingUntil + ECHO_TAIL_MS) continue
                if (_phase.value == Phase.SPEAKING) _phase.value = Phase.LISTENING
                val audio = JSONObject()
                    .put("data", Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=$IN_RATE")
                ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Queues Gemini's voice; the microphone waits until it has been played. */
    private fun speak(pcm: ByteArray) {
        val ms = pcm.size * 1000L / (OUT_RATE * 2)
        val now = SystemClock.elapsedRealtime()
        speakingUntil = maxOf(speakingUntil, now) + ms
        _phase.value = Phase.SPEAKING
        playback.trySend(pcm)
    }

    /** The driver spoke over Gemini: what it still had to say goes. */
    private fun hush() {
        while (playback.tryReceive().isSuccess) Unit
        track?.let {
            runCatching {
                it.pause()
                it.flush()
                it.play()
            }
        }
        speakingUntil = SystemClock.elapsedRealtime()
    }

    private suspend fun play() {
        for (pcm in playback) {
            track?.write(pcm, 0, pcm.size)
        }
    }

    private fun openAudio(context: Context) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // The music pauses while the driver talks with Gemini, and comes back after.
        val am = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build()
        if (am?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(min, OUT_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun releaseAudio(context: Context) {
        while (playback.tryReceive().isSuccess) Unit
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        focus?.let { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        focus = null
        speakingUntil = 0L
    }

    /** Ends the conversation once Gemini has finished saying goodbye. */
    internal fun endAfterSpeaking(context: Context) {
        scope.launch {
            delay(300)
            while (SystemClock.elapsedRealtime() < speakingUntil + ECHO_TAIL_MS) delay(200)
            withContext(Dispatchers.Main) { GeminiLive.end(context) }
        }
    }
}

/** What Dashwheel's Gemini may read and do ([DashAssistant]): the declarations Gemini sees, and running them. */
internal object AssistantTools {
    /** The wheel-button actions Gemini may run; the voice ones would fight it for the microphone. */
    private val ACTIONS = SteeringWheelAction.entries.filter {
        it != SteeringWheelAction.GEMINI_LIVE && it != SteeringWheelAction.ASK_MECHANIC && it != SteeringWheelAction.VOICE_ASSISTANT
    }

    fun declarations(): JSONArray {
        fun fn(name: String, description: String, params: JSONObject? = null) =
            JSONObject().put("name", name).put("description", description).apply { params?.let { put("parameters", it) } }
        fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = props.map { it.first }) =
            JSONObject().put("type", "OBJECT").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required))
        fun str(description: String, values: List<String>? = null) =
            JSONObject().put("type", "STRING").put("description", description).apply { values?.let { put("enum", JSONArray(it)) } }
        fun int(description: String) = JSONObject().put("type", "INTEGER").put("description", description)

        val functions = JSONArray()
            .put(fn("get_everything", "Everything the dashboard knows right now: car readings, fuel and range, doors, tyres, climate, battery, fault codes, servicing, trip, drives, guidance (next turn, remaining time and distance, arrival), weather, nearby fuel prices, music, volume, phone, calls, notifications, agenda, dashboards."))
            .put(fn("do_action", "Runs one of the dashboard's actions, like a steering wheel button.", obj("action" to str("The action.", ACTIONS.map { it.name }))))
            .put(fn("show_dashboard", "Shows one of the dashboards by its name (see dashboards in get_everything).", obj("name" to str("The dashboard's name."))))
            .put(fn("open_app", "Opens an installed app by its name.", obj("name" to str("The app's name, as on its icon."))))
            .put(fn("navigate_to", "Starts guidance to an address, a place or a business name.", obj("destination" to str("Where to go."))))
            .put(fn("set_volume", "Sets the music volume to a level (see volume in get_everything for the maximum).", obj("level" to int("The new level."))))
            .put(fn("end_conversation", "Ends the conversation, after your last words."))
        return JSONArray()
            .put(JSONObject().put("functionDeclarations", functions))
            .put(JSONObject().put("googleSearch", JSONObject()))
    }

    /** Runs [name] with [args]; what it returns goes back to Gemini. Main thread. */
    suspend fun run(context: Context, name: String, args: JSONObject): Any = when (name) {
        "get_everything" -> withContext(Dispatchers.Default) { CarFacts.snapshot(context) }
        "do_action" -> {
            val action = ACTIONS.firstOrNull { it.name == args.optString("action") }
            if (action == null) "unknown action" else {
                action.run(context)
                "done"
            }
        }
        "show_dashboard" -> showDashboard(context, args.optString("name"))
        "open_app" -> openApp(context, args.optString("name"))
        "navigate_to" -> if (NavHandoff.startQuery(context, args.optString("destination"))) "guidance started" else "no navigation app could take it"
        "set_volume" -> {
            MediaVolume.set(context, args.optInt("level"))
            "volume set"
        }
        "end_conversation" -> {
            DashAssistant.endAfterSpeaking(context)
            "ending after your reply"
        }
        else -> "unknown tool"
    }

    private fun showDashboard(context: Context, name: String): String {
        val wanted = simple(name)
        val found = LiveFacts.dashboards.firstOrNull { simple(it.second) == wanted }
            ?: LiveFacts.dashboards.firstOrNull { simple(it.second).contains(wanted) || wanted.contains(simple(it.second)) }
            ?: return "no dashboard called $name; there are: ${LiveFacts.dashboards.joinToString { it.second }}"
        MainActivity.dashboardGoTo.value = System.currentTimeMillis() to found.first
        if (!FreeformBar.dashboardInFront) {
            context.launchSafely(
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_KEEP_PAGE, true)
            )
        }
        return "showing ${found.second}"
    }

    private suspend fun openApp(context: Context, name: String): String {
        val wanted = simple(name)
        val apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }
        val app = apps.firstOrNull { simple(it.label) == wanted } ?: apps.firstOrNull { simple(it.label).contains(wanted) }
            ?: return "no app called $name"
        return if (AppLauncher.launch(context, app.packageName)) "opened ${app.label}" else "${app.label} would not open"
    }

    /** Lower case, no accents or spaces: "Écran voiture" matches "ecranvoiture". */
    private fun simple(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+|\\s+"), "").lowercase()
}
