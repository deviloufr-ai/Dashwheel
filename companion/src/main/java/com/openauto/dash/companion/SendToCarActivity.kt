package com.openauto.dash.companion

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.openauto.dash.link.Destination
import com.openauto.dash.link.TypeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Send to car", in any app's share sheet and in the menu over selected
 * text: the text goes to the field selected on the car's screen, and onto
 * its clipboard, to paste it there again. A place shared from a map app
 * ([SharedPlaces]), or a geo: link opened here, goes to the car as its
 * destination instead ([Destination]): guidance starts there. No window of
 * its own: a toast says how it went.
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
        if (LinkServer.state.value !is LinkState.Connected) {
            done(getString(R.string.keyboard_not_connected))
            return
        }
        when (val place = SharedPlaces.parse(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))) {
            is SharedPlace.Found -> sendPlace(place)
            // Followed off the main thread; the share's own words if that fails.
            is SharedPlace.ShortLink -> lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { SharedPlaces.resolve(place) } ?: place.fallback
                if (found != null) sendPlace(found) else type(text)
            }
            null -> if (intent.action == Intent.ACTION_VIEW) done(getString(R.string.place_unknown)) else type(text)
        }
    }

    private fun sendPlace(place: SharedPlace.Found) {
        LinkServer.send(Destination(place.label, place.lat, place.lng, place.query))
        done(getString(R.string.place_sent, place.label.ifBlank { place.query.orEmpty() }))
    }

    private fun type(text: String) {
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

    private fun done(status: CarKeyboard.Status) =
        done(getString(if (status == CarKeyboard.Status.TYPED) R.string.sent_to_car else CarKeyboard.message(status)))

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun sharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }

    private companion object {
        /** A little over [CarKeyboard]'s own wait, which says NO_ANSWER first. */
        const val ANSWER_WAIT_MS = 5_000L
    }
}
