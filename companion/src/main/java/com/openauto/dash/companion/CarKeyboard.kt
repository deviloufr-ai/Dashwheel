package com.openauto.dash.companion

import android.os.Handler
import android.os.Looper
import com.openauto.dash.link.EnableTyping
import com.openauto.dash.link.TypeResult
import com.openauto.dash.link.TypeText
import com.openauto.dash.link.TypingAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone as the car's keyboard: text typed, pasted or shared here goes to
 * the text field selected on the car's screen ([TypeText]), and the car says
 * what became of it ([TypeResult]). Typing sends a text every keystroke: only
 * the answer to the latest one counts.
 */
object CarKeyboard {
    /** How long the car gets to answer before it is taken for an older Dashwheel, which skips the text. */
    private const val ANSWER_MS = 4_000L

    enum class Status {
        TYPED,
        /** No field selected on the car's screen: the text is on its clipboard. */
        COPIED,
        FAILED,
        NO_ANSWER,
        NOT_CONNECTED,
        /** The car cannot see its fields at all: its accessibility service is off ([turnOn]). */
        TYPING_OFF,
        /** Asked the car to turn it on, no answer yet. */
        TURNING_ON,
        /** On now: text goes into the field selected on the car's screen. */
        TYPING_ON,
        /** The car could not turn it on by itself: its own settings, then. */
        TURN_ON_FAILED
    }

    /** What became of the text sent under [id]. */
    data class Answer(val id: Long, val status: Status)

    private val _answer = MutableStateFlow<Answer?>(null)
    val answer: StateFlow<Answer?> = _answer

    private val ids = AtomicLong(0)
    @Volatile private var awaiting = 0L
    private val main = Handler(Looper.getMainLooper())
    private val noAnswer = Runnable {
        val id = awaiting
        if (id != 0L) {
            awaiting = 0L
            _answer.value = Answer(id, Status.NO_ANSWER)
        }
    }

    /** Sends [text] to the car; its id, or null (and [Status.NOT_CONNECTED]) with no car linked. */
    fun send(text: String, mode: TypeText.Mode, enter: Boolean = false): Long? {
        if (LinkServer.state.value !is LinkState.Connected) {
            _answer.value = Answer(0, Status.NOT_CONNECTED)
            return null
        }
        val id = ids.incrementAndGet()
        awaiting = id
        LinkServer.send(TypeText(text.take(TypeText.MAX_CHARS), mode, enter, id))
        main.removeCallbacks(noAnswer)
        main.postDelayed(noAnswer, ANSWER_MS)
        return id
    }

    /** The car's answer; one to an earlier text than the latest is left out. */
    fun answered(result: TypeResult) {
        if (result.id != awaiting) return
        awaiting = 0L
        main.removeCallbacks(noAnswer)
        _answer.value = Answer(
            result.id,
            when (result.outcome) {
                TypeResult.Outcome.TYPED -> Status.TYPED
                TypeResult.Outcome.COPIED -> if (result.typingOff) Status.TYPING_OFF else Status.COPIED
                TypeResult.Outcome.FAILED -> Status.FAILED
            }
        )
    }

    /** How long the car gets to turn typing on: the service takes a few seconds to come up. */
    private const val TURN_ON_MS = 10_000L

    private val turnOnUnanswered = Runnable {
        if (_answer.value?.status == Status.TURNING_ON) _answer.value = Answer(0, Status.NO_ANSWER)
    }

    /** Asks the car to turn typing into its fields on ([EnableTyping]), after a [Status.TYPING_OFF]. */
    fun turnOn() {
        if (LinkServer.state.value !is LinkState.Connected) {
            _answer.value = Answer(0, Status.NOT_CONNECTED)
            return
        }
        awaiting = 0L
        main.removeCallbacks(noAnswer)
        _answer.value = Answer(0, Status.TURNING_ON)
        LinkServer.send(EnableTyping)
        main.removeCallbacks(turnOnUnanswered)
        main.postDelayed(turnOnUnanswered, TURN_ON_MS)
    }

    /** The car's answer to [turnOn]. */
    fun accessChanged(access: TypingAccess) {
        main.removeCallbacks(turnOnUnanswered)
        _answer.value = Answer(0, if (access.on) Status.TYPING_ON else Status.TURN_ON_FAILED)
    }

    /** What to tell the driver about [status]. */
    fun message(status: Status): Int = when (status) {
        Status.TYPED -> R.string.keyboard_typed
        Status.COPIED -> R.string.keyboard_copied
        Status.FAILED -> R.string.keyboard_failed
        Status.NO_ANSWER -> R.string.keyboard_no_answer
        Status.NOT_CONNECTED -> R.string.keyboard_not_connected
        Status.TYPING_OFF -> R.string.keyboard_typing_off
        Status.TURNING_ON -> R.string.keyboard_turning_on
        Status.TYPING_ON -> R.string.keyboard_typing_on
        Status.TURN_ON_FAILED -> R.string.keyboard_turn_on_failed
    }
}
