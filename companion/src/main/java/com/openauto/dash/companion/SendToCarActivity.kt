package com.openauto.dash.companion

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.openauto.dash.link.TypeText
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Send to car", in any app's share sheet and in the menu over selected
 * text: the text goes to the field selected on the car's screen, and onto
 * its clipboard, to paste it there again. No window of its own: a toast says
 * how it went.
 */
class SendToCarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app it came from stays usable while the car answers.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val text = sharedText(intent)
        if (text.isNullOrBlank() || savedInstanceState != null) {
            finish()
            return
        }
        val id = CarKeyboard.send(text, TypeText.Mode.INSERT)
        if (id == null) {
            done(CarKeyboard.Status.NOT_CONNECTED)
            return
        }
        lifecycleScope.launch {
            val answer = withTimeoutOrNull(ANSWER_WAIT_MS) {
                CarKeyboard.answer.filterNotNull().first { it.id == id }
            }
            done(answer?.status ?: CarKeyboard.Status.NO_ANSWER)
        }
    }

    private fun done(status: CarKeyboard.Status) {
        val message = if (status == CarKeyboard.Status.TYPED) R.string.sent_to_car else CarKeyboard.message(status)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun sharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    private companion object {
        /** A little over [CarKeyboard]'s own wait, which says NO_ANSWER first. */
        const val ANSWER_WAIT_MS = 5_000L
    }
}
