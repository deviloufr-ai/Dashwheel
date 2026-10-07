package com.openauto.dash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Speaks short sentences through the head unit's text-to-speech, the way a
 * navigation app gives directions: music ducks while it talks, then returns.
 * Sentences asked for before the engine is up are queued, not lost.
 */
object CarVoice {

    private const val TAG = "CarVoice"
    /** After the engine failed to start, how long before trying again (not once per sentence). */
    private const val RETRY_MS = 60_000L
    /** Nothing heard from the engine this long while talking: it died, so the music comes back. */
    private const val SILENCE_MS = 45_000L

    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    // When the engine last failed to start (uptime), so the next try waits.
    private var failedAt = 0L
    private val queued = mutableListOf<Pair<String, Locale>>()
    private var focus: AudioFocusRequest? = null
    private var nextId = 0
    // What was handed to the engine and isn't finished yet, by utterance id, oldest first (main thread).
    private val pending = LinkedHashMap<String, Said>()
    // What was asked to be said lately, newest last, so one feature doesn't repeat another.
    private val recent = ArrayDeque<Pair<Long, String>>()
    private const val RECENT_MS = 10 * 60_000L

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    // Sentences asked for while the car reverses, the driver is on the phone
    // or talks with Dashwheel's Gemini, said once it's done ([hold],
    // [holdForCall], [holdForLive]). Main thread only.
    private var reversing = false
    private var onCall = false
    private var inLive = false
    private val held = mutableListOf<HeldLine>()

    /**
     * While [on], nothing is said: the driver is reversing and listening for
     * the parking sensors. What was asked meanwhile is said when it ends,
     * except what went stale waiting ([linesToRelease]).
     */
    fun hold(on: Boolean) = setHold { reversing = on }

    /**
     * The same while the driver is on the phone: the voice never talks into a
     * call. Only what can't wait is still said (see [speak]).
     */
    fun holdForCall(on: Boolean) = setHold { onCall = on }

    /**
     * And while the driver talks with Dashwheel's own Gemini ([GeminiLive]):
     * the car's voice would speak over it, and into its open microphone.
     */
    fun holdForLive(on: Boolean) = setHold { inLive = on }

    private fun setHold(change: () -> Unit) {
        main.post {
            val wasReversing = reversing
            val before = reversing || onCall || inLive
            change()
            val now = reversing || onCall || inLive
            if (now && (!before || reversing && !wasReversing)) interrupt()
            if (!before || now) return@post
            val lines = linesToRelease(held.toList(), SystemClock.elapsedRealtime())
            held.clear()
            lines.forEach { speakNow(it.text, it.locale, it.urgent, it.guidance) }
        }
    }

    /**
     * A hold begins: the sentence being said stops there, and is said again,
     * whole, with what waited behind it once the hold ends. A call or a
     * conversation never cuts what can't wait; a reverse does. Main thread.
     */
    private fun interrupt() {
        if (pending.isEmpty() || !reversing && pending.values.any { it.urgent }) return
        val at = SystemClock.elapsedRealtime()
        held.addAll(0, pending.values.filter { !it.guidance }.map { HeldLine(it.text, it.locale, at, it.urgent) })
        tts?.stop()
    }

    /**
     * Stops talking at once: the sentence being said and those waiting behind
     * it (the tour moved on, the driver asked for quiet). What a call, a
     * conversation or a reverse holds back is still said after.
     */
    fun stop() {
        main.post {
            queued.clear()
            tts?.stop()
        }
    }

    private val _quiet = MutableStateFlow(false)
    /** [quiet], for the menu that offers to end it. */
    val quietState: StateFlow<Boolean> = _quiet

    /**
     * Quiet for this drive (the ⋮ menu, a learned wheel button): what the car
     * says by itself ([announce]) is dropped, except what can't wait. Lasts
     * until the driver ends it or the car starts again ([CarPower]).
     */
    var quiet: Boolean
        get() = _quiet.value
        set(on) {
            _quiet.value = on
            // Quiet means now: the sentence being said stops too, unless it can't wait.
            if (on) main.post { if (pending.values.none { it.urgent }) tts?.stop() }
        }

    /**
     * What the car says by itself: an alert, a reminder, the briefing. Like
     * [speak], but not while the driver asked for [quiet], unless [urgent].
     * Every unasked sentence goes through here; [speak] is for answers to
     * what the driver asked (a wheel button, a tap on a read button, a turn).
     */
    fun announce(text: String, locale: Locale, urgent: Boolean = false) {
        if (quiet && !urgent) return
        speak(text, locale, urgent)
    }

    /**
     * Says [text] in [locale]; silently skipped when the unit has no voice for
     * that language. [urgent]: what can't wait (the engine overheating, a
     * door open on the move) is said ahead of anything being said or queued,
     * and during a call too.
     */
    fun speak(text: String, locale: Locale, urgent: Boolean = false) = ask(text, locale, urgent, guidance = false)

    /**
     * A turn instruction, which is only true for a few seconds: said ahead of
     * what is being said or waiting (those waiting are said after it), and
     * dropped rather than said late when a call or a reverse held it back.
     */
    fun guide(text: String, locale: Locale) = ask(text, locale, urgent = false, guidance = true)

    private fun ask(text: String, locale: Locale, urgent: Boolean, guidance: Boolean) {
        // Noted when asked, not when spoken, so a check right after already sees it.
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.addLast(now to text)
            while (recent.first().first < now - RECENT_MS) recent.removeFirst()
        }
        main.post {
            if (text.isBlank()) return@post
            if (reversing || (onCall || inLive) && !urgent) {
                held.add(HeldLine(text, locale, SystemClock.elapsedRealtime(), urgent, guidance))
                return@post
            }
            speakNow(text, locale, urgent, guidance)
        }
    }

    /** Main thread. */
    private fun speakNow(text: String, locale: Locale, urgent: Boolean, guidance: Boolean = false) {
        val engine = engine() ?: return
        if (!ready) {
            // Still starting: what can't wait goes first.
            if (urgent || guidance) queued.add(0, text to locale) else queued.add(text to locale)
            return
        }
        say(engine, text, locale, urgent, guidance)
    }

    /** What was asked to be said since [time] (the last ten minutes at most). */
    fun saidSince(time: Long): List<String> =
        synchronized(recent) { recent.filter { it.first >= time }.map { it.second } }

    /**
     * Whether the unit can speak [locale]: true / false, or null while the
     * engine is still starting. Main thread.
     */
    fun canSpeak(locale: Locale): Boolean? {
        val engine = engine() ?: return false
        if (!ready) return null
        return voiceFor(engine, locale) != null
    }

    /** [locale] itself, else any voice of the same language (a unit may only have en-US or fr-CA). */
    private fun voiceFor(engine: TextToSpeech, locale: Locale): Locale? =
        listOf(locale, Locale(locale.language)).firstOrNull {
            engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
        }

    private fun engine(): TextToSpeech? {
        tts?.let { return it }
        val context = appContext ?: return null
        if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return null
        lateinit var created: TextToSpeech
        created = TextToSpeech(context) { status ->
            main.post {
                if (tts !== created) return@post
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    failedAt = 0L
                    queued.forEach { (t, l) -> say(created, t, l) }
                } else {
                    Log.w(TAG, "Text-to-speech failed to start ($status)")
                    // Shut down, or each failed try leaves its service connection behind.
                    created.shutdown()
                    tts = null
                    failedAt = SystemClock.elapsedRealtime()
                }
                queued.clear()
            }
        }
        created.setAudioAttributes(attributes)
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post {
                    pending[utteranceId]?.started = true
                    stillTalking()
                }
            }
            override fun onDone(utteranceId: String?) = releaseFocusWhenQuiet(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocusWhenQuiet(utteranceId)
            // Flushed or stopped before its end: never gets onDone.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = releaseFocusWhenQuiet(utteranceId)
        })
        tts = created
        return created
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale, urgent: Boolean = false, guidance: Boolean = false) {
        if (voiceFor(engine, locale) == null) {
            Log.w(TAG, "No ${locale.displayLanguage} voice installed; not speaking")
            return
        }
        // Quiet during a call. Otherwise speak even if focus is refused: some
        // head-unit ROMs refuse or delay it for their own radio, and a missed
        // fault alert is worse than talking over the music.
        val focused = requestFocus()
        if (!focused && !urgent && inCall()) {
            Log.i(TAG, "In a call; not speaking")
            return
        }
        // Urgent: what was being said or waiting is dropped (each gets its onStop).
        // A turn instruction cuts in the same way, except on what can't wait,
        // and what was only waiting is said after it.
        val cutsIn = urgent || guidance && pending.values.none { it.urgent }
        val waiting = if (guidance && cutsIn) pending.values.filter { !it.started && !it.guidance } else emptyList()
        utter(engine, Said(text, locale, urgent, guidance), if (cutsIn) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
        waiting.forEach { utter(engine, Said(it.text, it.locale, it.urgent, guidance = false), TextToSpeech.QUEUE_ADD) }
        stillTalking()
        if (pending.isEmpty()) abandonFocus()
    }

    /** Hands [line] to the engine in its language; it is [pending] until the engine says it is over. */
    private fun utter(engine: TextToSpeech, line: Said, mode: Int) {
        val voice = voiceFor(engine, line.locale) ?: return
        if (engine.setLanguage(voice) < TextToSpeech.LANG_AVAILABLE) return
        val id = "carvoice-${nextId++}"
        if (engine.speak(line.text, mode, null, id) == TextToSpeech.SUCCESS) pending[id] = line
    }

    private fun inCall(): Boolean {
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    // If the engine dies mid-sentence no callback ever comes, and the music
    // would stay ducked: past [SILENCE_MS] without news, give the focus back.
    private val giveUp = Runnable {
        pending.clear()
        abandonFocus()
    }

    /** The engine is (still) speaking: pushes back the give-up deadline. Main thread. */
    private fun stillTalking() {
        main.removeCallbacks(giveUp)
        if (pending.isNotEmpty()) main.postDelayed(giveUp, SILENCE_MS)
    }

    // Utterance callbacks arrive on a binder thread; hop to main and only let
    // the music back up once nothing else is queued.
    private fun releaseFocusWhenQuiet(finishedId: String?) {
        main.post {
            pending.remove(finishedId)
            stillTalking()
            if (pending.isEmpty()) abandonFocus()
        }
    }

    private fun abandonFocus() {
        val request = focus ?: return
        (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(request)
        focus = null
    }
}

/** A sentence handed to the engine; [started] once it is being said. */
private class Said(val text: String, val locale: Locale, val urgent: Boolean, val guidance: Boolean, var started: Boolean = false)

/** A sentence asked for while [CarVoice] was held, and when (elapsed realtime); [guidance]: a turn instruction. */
internal data class HeldLine(val text: String, val locale: Locale, val at: Long, val urgent: Boolean = false, val guidance: Boolean = false)

/** A held sentence older than this is dropped: by then it would be about something else. */
internal const val HELD_MAX_MS = 2 * 60_000L

/** A held turn instruction is only worth saying this long: after that the turn is behind the car. */
internal const val HELD_GUIDANCE_MS = 10_000L

/**
 * What to say when a hold ends: in order, the stale ones dropped, each
 * sentence once. Of the turn instructions only the last, if still fresh, and
 * it goes first.
 */
internal fun linesToRelease(held: List<HeldLine>, now: Long): List<HeldLine> {
    val fresh = held.filter { now - it.at <= if (it.guidance) HELD_GUIDANCE_MS else HELD_MAX_MS }
    val turn = fresh.lastOrNull { it.guidance }
    return (listOfNotNull(turn) + fresh.filter { !it.guidance }).distinctBy { it.text }
}
