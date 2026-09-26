package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The language options, one row each; picking one restarts the activity in that language. */
@Composable
internal fun LanguageChoices(onPicked: () -> Unit = {}) {
    val context = LocalContext.current
    val selected = remember { AppLanguage.current(context) }
    val system = remember { AppLanguage.systemLocale() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { language ->
            val name = if (language == AppLanguage.SYSTEM) {
                stringResource(
                    R.string.language_system,
                    system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) }
                )
            } else {
                language.nativeName
            }
            LanguageOption(name, language == selected) {
                onPicked()
                context.findActivity()?.let { AppLanguage.select(it, language) }
            }
        }
    }
}

@Composable
private fun LanguageOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = DashShape.Medium
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.CardHi, shape)
            // Scale (not replace) the alpha: glass themes use a translucent CardHi.
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = DashColors.TextPrimary, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = DashColors.Accent)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
