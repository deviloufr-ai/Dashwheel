package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.util.Locale

// The Test button's patience: long enough for a slow link, short enough to read the verdict.
private const val TEST_BUDGET_MS = 45_000L

/** Gemini key, language and voice for the AI mechanic, with a one-tap test. */
@Composable
internal fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AiSettings.load(context)) }
    var testing by remember { mutableStateOf(false) }
    // Seconds spent on the current test, shown so a slow link doesn't look frozen.
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(testing) {
        waited = 0
        while (testing) {
            delay(1_000)
            waited++
        }
    }
    // (worked, message) from the last test.
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var code by remember { mutableStateOf("") }

    fun close() {
        AiSettings.save(context, config)
        // A new key or language: redo the advice for any codes on the tile.
        AiMechanic.refresh()
        onDismiss()
    }

    /** Saves, then checks the key with a tiny request; [activated] says the key just came from the code. */
    fun test(activated: Boolean = false) {
        AiSettings.save(context, config)
        testing = true
        result = null
        scope.launch {
            // A quick check first tells "no internet" from "Gemini slow", and the
            // test gives up after a while instead of waiting on a dead link.
            val online = GeminiClient.reachGoogle()
            val reply = online.exceptionOrNull()?.let { Result.failure(it) }
                ?: GeminiClient.generate(config.apiKey.trim(), "Reply with the single word OK.", budgetMs = TEST_BUDGET_MS)
            testing = false
            // The voice is the launcher's, whatever language Gemini writes its answers in.
            val voiceLocale = InAppNav.locale(context)
            reply.onSuccess {
                val works = context.getString(if (activated) R.string.ai_test_activated_ok else R.string.ai_test_ok, it.model)
                // The missing voice is named in the screen's language ("allemand" on a French screen).
                val voice = if (CarVoice.canSpeak(voiceLocale) == false) {
                    " " + context.getString(R.string.ai_no_voice, voiceLocale.getDisplayLanguage(Locale.getDefault()))
                } else ""
                result = true to works + voice
                CarVoice.speak(AppLanguage.wrap(context).getString(R.string.ai_say_ready), voiceLocale)
            }.onFailure {
                // Google answered the quick check, so the link is fine: Gemini itself is slow.
                val why = if (online.isSuccess && it is InterruptedIOException) {
                    context.getString(R.string.ai_error_gemini_busy)
                } else {
                    AiMechanic.describe(context, it)
                }
                // An activated key is saved either way; only reaching Gemini failed.
                result = activated to context.getString(
                    if (activated) R.string.ai_test_activated_unreachable else R.string.ai_test_failed,
                    why
                )
            }
        }
    }

    fun activate() {
        testing = true
        result = null
        scope.launch {
            val key = withContext(Dispatchers.Default) { AiKeyVault.unlock(code) }
            testing = false
            if (key == null) {
                result = false to context.getString(R.string.ai_wrong_code)
            } else {
                config = config.copy(apiKey = key, keyFromCode = true)
                code = ""
                test(activated = true)
            }
        }
    }

    SettingsSheet(
        title = stringResource(R.string.ai_title),
        onDismiss = ::close,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = ::close) }
    ) {
        Text(
            stringResource(R.string.ai_explanation),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )

        if (AiKeyVault.available) {
            Label(stringResource(R.string.ai_activation_code))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; result = null },
                    placeholder = { Text(stringResource(R.string.ai_activation_hint), color = DashColors.Muted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = fieldColors()
                )
                Spacer(Modifier.size(12.dp))
                Button(
                    enabled = !testing && code.isNotBlank(),
                    onClick = ::activate,
                    colors = buttonColors()
                ) { Text(stringResource(R.string.ai_activate)) }
            }
        }

        Label(stringResource(if (AiKeyVault.available) R.string.ai_own_key else R.string.ai_key))
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { config = config.copy(apiKey = it.trim(), keyFromCode = false); result = null },
            // The built-in key stays hidden; typing replaces it with your own.
            visualTransformation = if (config.keyFromCode) PasswordVisualTransformation() else VisualTransformation.None,
            placeholder = { Text(stringResource(R.string.ai_key_hint), color = DashColors.Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )

        // The mechanic answers for the car set in "My car".
        Label(stringResource(R.string.car_ai_car))
        Text(
            stringResource(R.string.car_ai_car_detail, CarProfileStore.current.displayName(LocalContext.current)),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Label(stringResource(R.string.ai_language))
        Text(
            stringResource(R.string.ai_language_detail),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        // "Same as app" (null) first, then every language by its own name.
        val sameAsApp = stringResource(R.string.ai_language_auto)
        ChoiceRow(
            listOf<AiLanguage?>(null) + AiLanguage.entries,
            config.languageChoice,
            { it?.label ?: sameAsApp },
            perRow = 3
        ) { config = config.copy(languageChoice = it) }

        // What the car says by itself is switched under Settings, Alerts (VoiceSettings).
        Text(
            stringResource(R.string.voice_where),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !testing && config.apiKey.isNotBlank(),
                onClick = { test() },
                colors = buttonColors()
            ) { Text(stringResource(R.string.ai_test)) }
            Spacer(Modifier.size(12.dp))
            if (testing) {
                CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.ai_test_waiting, waited),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            result?.let { (ok, message) ->
                Text(
                    message,
                    color = if (ok) DashColors.Good else DashColors.Warning,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// Explicit disabled colours: the Material defaults vanish on the light theme cards.
@Composable
internal fun buttonColors() = ButtonDefaults.buttonColors(
    containerColor = DashColors.Accent,
    contentColor = DashColors.OnAccent,
    disabledContainerColor = DashColors.CardHi,
    disabledContentColor = DashColors.Muted
)

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = DashColors.TextPrimary,
    unfocusedTextColor = DashColors.TextPrimary,
    focusedBorderColor = DashColors.Accent,
    unfocusedBorderColor = DashColors.Line,
    cursorColor = DashColors.Accent
)

@Composable
internal fun Label(text: String) {
    Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
}

/** An on/off setting: title and explanation on the left, the switch on the right. */
@Composable
internal fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DashColors.OnAccent,
                checkedTrackColor = DashColors.Accent
            )
        )
    }
}

/**
 * Segmented single choice; the picked segment wears the accent gradient.
 * Long lists wrap onto rows of [perRow] segments.
 */
@Composable
internal fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    perRow: Int = options.size,
    onPick: (T) -> Unit
) {
    val shape = DashShape.Medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f), shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    val chosen = option == selected
                    val segment = DashShape.Small
                    Text(
                        label(option),
                        color = if (chosen) DashColors.OnAccent else DashColors.TextPrimary,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(segment)
                            .then(if (chosen) Modifier.background(DashColors.AccentBrush, segment) else Modifier)
                            .clickable { onPick(option) }
                            .padding(vertical = 10.dp)
                    )
                }
                // Keep a short last row's segments the same width as the others.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
