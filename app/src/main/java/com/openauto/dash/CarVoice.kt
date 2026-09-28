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
    // Utterances handed to the engine and not finished yet (main thread).
    private var talking = 0
    // What was asked to be said lately, newest last, so one feature doesn't repeat another.
    private val recent = ArrayDeque<Pair<Long, String>>()
    private const val RECENT_MS = 10 * 60_000L

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    // Sentences asked for while the car reverses or the driver is on the
    // phone, said once it's done ([hold], [holdForCall]). Main thread only.
    private var reversing = false
    private var onCall = false
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

    private fun setHold(change: () -> Unit) {
        main.post {
            val before = reversing || onCall
            change()
            if (!before || reversing || onCall) return@post
            val lines = linesToRelease(held.toList(), SystemClock.elapsedRealtime())
            held.clear()
            lines.forEach { speakNow(it.text, it.locale, it.urgent) }
        }
    }

    /** Quiet asked for this long at most: a drive, not for good. */
    private const val QUIET_MAX_MS = 4 * 3_600_000L
    // Until when (elapsed realtime) the car keeps what it would say by itself to itself; 0 = not quiet.
    @Volatile private var quietUntil = 0L

    /**
     * Quiet for this drive (a learned wheel button): what the car says by
     * itself ([announce]) is dropped, except what can't wait. Ends at the
     * next start of the car ([CarPower]).
     */
    var quiet: Boolean
        get() = SystemClock.elapsedRealtime() < quietUntil
        set(on) {
            quietUntil = if (on) SystemClock.elapsedRealtime() + QUIET_MAX_MS else 0L
        }

    /**
     * What the car says by itself: an alert, a reminder, the briefing. Like
     * [speak], but not while the driver asked for [quiet], unless [urgent].
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
    fun speak(text: String, locale: Locale, urgent: Boolean = false) {
        // Noted when asked, not when spoken, so a check right after already sees it.
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.addLast(now to text)
            while (recent.first().first < now - RECENT_MS) recent.removeFirst()
        }
        main.post {
            if (text.isBlank()) return@post
            if (reversing || onCall && !urgent) {
                held.add(HeldLine(text, locale, SystemClock.elapsedRealtime(), urgent))
                return@post
            }
            speakNow(text, locale, urgent)
        }
    }

    /** Main thread. */
    private fun speakNow(text: String, locale: Locale, urgent: Boolean) {
        val engine = engine() ?: return
        if (!ready) {
            // Still starting: what can't wait goes first.
            if (urgent) queued.add(0, text to locale) else queued.add(text to locale)
            return
        }
        say(engine, text, locale, urgent)
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
                main.post { stillTalking() }
            }
            override fun onDone(utteranceId: String?) = releaseFocusWhenQuiet(finished = 1)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocusWhenQuiet(finished = 1)
            // Flushed or stopped before its end: never gets onDone.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = releaseFocusWhenQuiet(finished = 1)
        })
        tts = created
        return created
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale, urgent: Boolean = false) {
        val voice = voiceFor(engine, locale)
        if (voice == null || engine.setLanguage(voice) < TextToSpeech.LANG_AVAILABLE) {
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
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (engine.speak(text, mode, null, "carvoice-${nextId++}") == TextToSpeech.SUCCESS) {
            talking++
            stillTalking()
        } else {
            releaseFocusWhenQuiet(finished = 0)
        }
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
        talking = 0
        abandonFocus()
    }

    /** The engine is (still) speaking: pushes back the give-up deadline. Main thread. */
    private fun stillTalking() {
        main.removeCallbacks(giveUp)
        if (talking > 0) main.postDelayed(giveUp, SILENCE_MS)
    }

    // Utterance callbacks arrive on a binder thread; hop to main and only let
    // the music back up once nothing else is queued.
    private fun releaseFocusWhenQuiet(finished: Int) {
        main.post {
            talking = (talking - finished).coerceAtLeast(0)
            stillTalking()
            if (talking == 0) abandonFocus()
        }
    }

    private fun abandonFocus() {
        val request = focus ?: return
        (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(request)
        focus = null
    }
}

/** A sentence asked for while [CarVoice] was held, and when (elapsed realtime). */
internal data class HeldLine(val text: String, val locale: Locale, val at: Long, val urgent: Boolean = false)

/** A held sentence older than this is dropped: by then it would be about something else. */
internal const val HELD_MAX_MS = 2 * 60_000L

/** What to say when a hold ends: in order, the stale ones dropped, each sentence once. */
internal fun linesToRelease(held: List<HeldLine>, now: Long): List<HeldLine> =
    held.filter { now - it.at <= HELD_MAX_MS }.distinctBy { it.text }
