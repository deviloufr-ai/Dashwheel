package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.DisplayWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The second screen's own lines (waiting, no signal, the pairing hint) in
 * Dashwheel's language, sent when a display links and again when the language
 * changes. The display keeps them for its next start ([DisplayWords]).
 */
internal object SecondScreenWords {
    /** How often a language picked meanwhile is noticed (the picker restarts the screens, not this). */
    private const val CHECK_MS = 30_000L

    fun start(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        val checks = flow { while (true) { emit(Unit); delay(CHECK_MS) } }
        scope.launch {
            combine(DisplayLink.state, checks) { link, _ ->
                if (link is DisplayLinkState.Connected) words(app) else null
            }.distinctUntilChanged().collect { words -> words?.let(DisplayLink::send) }
        }
    }

    /** The lines in the language Dashwheel writes in, a language picked inside Dashwheel included. */
    private fun words(app: Context): DisplayWords {
        val res = AppLanguage.wrap(app).resources
        return DisplayWords(
            language = res.configuration.locales[0].language,
            waiting = res.getString(R.string.second_screen_pi_waiting),
            noSignal = res.getString(R.string.second_screen_pi_no_signal),
            connected = res.getString(R.string.second_screen_pi_connected),
            scanApp = res.getString(R.string.second_screen_pi_scan)
        )
    }
}
