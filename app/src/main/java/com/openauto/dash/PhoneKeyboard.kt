package com.openauto.dash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Toast
import com.openauto.dash.link.TypeResult
import com.openauto.dash.link.TypeText
import com.openauto.dash.link.TypingAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The phone as the car's keyboard: text from the companion app ([TypeText])
 * typed into the text field selected on the car's screen, whatever app it is
 * in (a Maps search, a Wi-Fi password, a message), through the accessibility
 * service. With no field selected, or the service off, the text goes on the
 * clipboard, to paste by hand; a paste from the phone goes there anyway.
 */
internal object PhoneKeyboard {
    private const val TAG = "PhoneKeyboard"

    /** `KeyEvent.KEYCODE_ENTER`, for `input keyevent`. */
    private const val KEYCODE_ENTER = 66

    // One thread, so the phone's keystrokes land in the order they were typed,
    // and a field slow to answer never holds up the main thread.
    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())

    fun type(context: Context, message: TypeText, answer: (TypeResult) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val outcome = runCatching { typeNow(app, message) }
                .onFailure { Log.w(TAG, "text from the phone not typed", it) }
                .getOrDefault(TypeResult.Outcome.FAILED)
            // On the clipboard because the service that finds the field is off, not for want of a field.
            val off = outcome == TypeResult.Outcome.COPIED && !SplitAccessibilityService.isConnected
            answer(TypeResult(message.id, outcome, typingOff = off))
        }
    }

    /** How long the accessibility service gets to come up once turned on, and how often it is looked for. */
    private const val BIND_WAIT_MS = 5_000L
    private const val BIND_POLL_MS = 250L

    /**
     * Typing into the car's fields turned on, asked from the phone's keyboard
     * card ([com.openauto.dash.link.EnableTyping]): the accessibility service
     * is added to the enabled ones through the privileged shell, the others
     * kept, then given a few seconds to come up. Answers whether it did.
     */
    fun enable(context: Context, answer: (TypingAccess) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            if (!SplitAccessibilityService.isConnected) {
                val service = ComponentName(app, SplitAccessibilityService::class.java).flattenToString()
                runCatching { DockShell.shell(app, enableCommand(service)) }
                    .onFailure { Log.w(TAG, "typing from the phone not turned on: ${it.message}") }
                var waited = 0L
                while (!SplitAccessibilityService.isConnected && waited < BIND_WAIT_MS) {
                    delay(BIND_POLL_MS)
                    waited += BIND_POLL_MS
                }
            }
            val on = SplitAccessibilityService.isConnected
            Log.i(TAG, if (on) "typing from the phone is on" else "typing from the phone is still off")
            answer(TypingAccess(on))
        }
    }

    /** The shell line that adds [service] to the enabled accessibility services, keeping those already there. */
    fun enableCommand(service: String): String =
        "s=\$(settings get secure enabled_accessibility_services); " +
            "case \":\$s:\" in *\":$service:\"*) ;; " +
            "*) if [ -z \"\$s\" ] || [ \"\$s\" = null ]; then s=$service; else s=\"\$s:$service\"; fi; " +
            "settings put secure enabled_accessibility_services \"\$s\" ;; esac; " +
            "settings put secure accessibility_enabled 1"

    private suspend fun typeNow(context: Context, message: TypeText): TypeResult.Outcome {
        val text = message.text.take(TypeText.MAX_CHARS)
        val insert = message.mode == TypeText.Mode.INSERT
        // A paste or a share stays on the clipboard, and the field pastes it from there.
        if (insert && text.isNotEmpty()) copy(context, text)
        val focus = SplitAccessibilityService.inputFocus()
        val field = focus?.takeIf { canType(it) }
        if (field == null) {
            focus?.let(::recycle)
            if (!insert && text.isNotEmpty()) copy(context, text)
            if (insert && text.isNotEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(context, R.string.phone_text_copied, Toast.LENGTH_LONG).show() }
            }
            return TypeResult.Outcome.COPIED
        }
        try {
            val typed = when {
                text.isEmpty() && insert -> true
                insert -> field.performAction(AccessibilityNodeInfo.ACTION_PASTE) || setText(field, pasted(field, text))
                else -> setText(field, text to text.length)
            }
            if (!typed) return TypeResult.Outcome.FAILED
            if (message.enter) pressEnter(context, field)
            return TypeResult.Outcome.TYPED
        } finally {
            recycle(field)
        }
    }

    private fun canType(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    /** The field's text with [text] at its cursor, for a field that won't paste. */
    private fun pasted(field: AccessibilityNodeInfo, text: String): Pair<String, Int> {
        val shown = field.text?.toString().orEmpty()
        // An empty field reads out its hint; a password field, dots.
        val current = if (field.isShowingHintText || field.isPassword) "" else shown
        return inserted(current, field.textSelectionStart, field.textSelectionEnd, text)
    }

    /** Sets the field to the first of [edit], with the cursor at the second. */
    private fun setText(field: AccessibilityNodeInfo, edit: Pair<String, Int>): Boolean {
        val (text, cursor) = edit
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val at = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, at)
        return true
    }

    /**
     * The field's own Enter action (a search runs) from Android 11; before,
     * the unit's Enter key, pressed through the privileged shell.
     */
    private suspend fun pressEnter(context: Context, field: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        ) return
        runCatching { DockShell.shell(context, "input keyevent $KEYCODE_ENTER") }
            .onFailure { Log.w(TAG, "Enter not pressed: ${it.message}") }
    }

    private suspend fun copy(context: Context, text: String) = withContext(Dispatchers.Main) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.phone_from_phone), text))
    }

    // Before Android 13 node objects come from a pool and go back to it only when recycled.
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { node.recycle() }
    }

    /**
     * [current] with [text] in place of its selection ([start] to [end], in
     * either order), and the cursor right after it; at the end when the field
     * tells no cursor (-1).
     */
    fun inserted(current: String, start: Int, end: Int, text: String): Pair<String, Int> {
        if (start < 0 || end < 0) return (current + text) to current.length + text.length
        val from = minOf(start, end).coerceIn(0, current.length)
        val to = maxOf(start, end).coerceIn(0, current.length)
        return (current.substring(0, from) + text + current.substring(to)) to from + text.length
    }
}
