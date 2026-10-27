package com.openauto.dash.companion

import android.os.Handler
import android.os.Looper
import com.openauto.dash.link.EnableTyping
import com.openauto.dash.link.PhonePointer
import com.openauto.dash.link.PointerAccess
import com.openauto.dash.link.TypingAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * The phone as the car's mouse: the touchpad screen moves a pointer on the
 * car's screen and taps, holds and scrolls where it points ([PhonePointer]).
 * A finger's moves are gathered and sent a few dozen times a second, not on
 * every touch event, so a Bluetooth link keeps up. The touches come on the
 * main thread; the car's answers on the link's.
 */
object CarTouchpad {
    /** How long the car gets to say it can tap before it is taken for an older Dashwheel. */
    private const val ANSWER_MS = 4_000L
    /** How long the car gets to turn its accessibility service on. */
    private const val TURN_ON_MS = 10_000L
    private const val MOVE_EVERY_MS = 20L
    // Each scroll is a swipe on the car: fewer, longer ones scroll smoother than many short ones.
    private const val SCROLL_EVERY_MS = 100L
    private const val MIN_SCROLL_DP = 8f

    enum class Status {
        /** Opened, the car's answer awaited. */
        ASKING,
        ON,
        /** The car's accessibility service is off: it cannot tap ([turnOn]). */
        OFF,
        TURNING_ON,
        TURN_ON_FAILED,
        NO_ANSWER,
        NOT_CONNECTED
    }

    private val _status = MutableStateFlow(Status.ASKING)
    val status: StateFlow<Status> = _status

    private val main = Handler(Looper.getMainLooper())
    private val noAnswer = Runnable {
        if (_status.value == Status.ASKING || _status.value == Status.TURNING_ON) _status.value = Status.NO_ANSWER
    }

    /** The touchpad screen is open: only then does the car's pointer show. */
    @Volatile private var opened = false

    private var moveX = 0f
    private var moveY = 0f
    private var movePending = false
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollPending = false
    private val sendMove = Runnable {
        movePending = false
        if (moveX != 0f || moveY != 0f) send(PhonePointer(PhonePointer.Kind.MOVE, moveX, moveY))
        moveX = 0f
        moveY = 0f
    }
    private val sendScroll = Runnable {
        scrollPending = false
        // A short one is kept for the next: on the car it would be too short to scroll, and land as a tap.
        if (hypot(scrollX, scrollY) < MIN_SCROLL_DP) return@Runnable
        send(PhonePointer(PhonePointer.Kind.SCROLL, scrollX, scrollY))
        scrollX = 0f
        scrollY = 0f
    }

    private val connected: Boolean get() = LinkServer.state.value is LinkState.Connected

    /** The touchpad opened: the pointer shows on the car, which says whether it can tap. */
    fun open() {
        opened = true
        if (!connected) {
            _status.value = Status.NOT_CONNECTED
            return
        }
        _status.value = Status.ASKING
        LinkServer.send(PhonePointer(PhonePointer.Kind.SHOW))
        main.removeCallbacks(noAnswer)
        main.postDelayed(noAnswer, ANSWER_MS)
    }

    /** The touchpad closed: the pointer leaves the car's screen. */
    fun close() {
        opened = false
        main.removeCallbacks(noAnswer)
        main.removeCallbacks(sendMove)
        main.removeCallbacks(sendScroll)
        movePending = false
        scrollPending = false
        moveX = 0f; moveY = 0f; scrollX = 0f; scrollY = 0f
        if (connected) LinkServer.send(PhonePointer(PhonePointer.Kind.HIDE))
    }

    /** One finger moved by [dx], [dy] dp. */
    fun move(dx: Float, dy: Float) {
        moveX += dx
        moveY += dy
        if (!movePending) {
            movePending = true
            main.postDelayed(sendMove, MOVE_EVERY_MS)
        }
    }

    /** Two fingers moved by [dx], [dy] dp: the car's screen scrolls along with them. */
    fun scroll(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
        if (!scrollPending) {
            scrollPending = true
            main.postDelayed(sendScroll, SCROLL_EVERY_MS)
        }
    }

    fun tap() = press(PhonePointer.Kind.TAP)
    fun hold() = press(PhonePointer.Kind.HOLD)
    fun back() = press(PhonePointer.Kind.BACK)
    fun home() = press(PhonePointer.Kind.HOME)

    /** A press lands where the pointer is now: the moves gathered so far go first. */
    private fun press(kind: PhonePointer.Kind) {
        main.removeCallbacks(sendMove)
        sendMove.run()
        send(PhonePointer(kind))
    }

    private fun send(message: PhonePointer) {
        if (!connected) {
            _status.value = Status.NOT_CONNECTED
            return
        }
        LinkServer.send(message)
    }

    /** The car's word on tapping: on opening, and when a tap could not be made. */
    fun answered(access: PointerAccess) {
        if (!opened) return
        main.removeCallbacks(noAnswer)
        _status.value = if (access.on) Status.ON else Status.OFF
    }

    /** Asks the car to turn its accessibility service on: the one typing from the phone uses too. */
    fun turnOn() {
        if (!connected) {
            _status.value = Status.NOT_CONNECTED
            return
        }
        _status.value = Status.TURNING_ON
        LinkServer.send(EnableTyping)
        main.removeCallbacks(noAnswer)
        main.postDelayed(noAnswer, TURN_ON_MS)
    }

    /** The car's answer to [turnOn] (or to the keyboard card's). */
    fun accessChanged(access: TypingAccess) {
        if (!opened || (_status.value != Status.TURNING_ON && !access.on)) return
        main.removeCallbacks(noAnswer)
        // On, the pointer is asked for again: the service that draws it has only just come up.
        if (access.on) open() else _status.value = Status.TURN_ON_FAILED
    }

    /** What to tell the driver about [status], or null when the touchpad just works. */
    fun message(status: Status): Int? = when (status) {
        Status.ASKING, Status.ON -> null
        Status.OFF -> R.string.touchpad_off
        Status.TURNING_ON -> R.string.keyboard_turning_on
        Status.TURN_ON_FAILED -> R.string.keyboard_turn_on_failed
        Status.NO_ANSWER -> R.string.keyboard_no_answer
        Status.NOT_CONNECTED -> R.string.keyboard_not_connected
    }
}
