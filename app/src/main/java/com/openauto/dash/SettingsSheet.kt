package com.openauto.dash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * A deep setting (My car, the AI mechanic, servicing, the wheel buttons...)
 * has one body and two frames. Inside the Settings screen it takes the
 * right-hand pane: a back arrow, its title and its buttons along the top,
 * where the keyboard never covers them, and the whole pane to scroll in.
 * Anywhere else (a tile, the setup) it is the dialog it always was.
 */

/** True inside the Settings screen's pane: a [SettingsSheet] composed there fills it. */
internal val LocalSheetInPane = staticCompositionLocalOf { false }

/**
 * The sheet open in the Settings pane. Whoever takes Settings away from
 * outside (another category, the bar, the close button) lets the sheet
 * leave first, the way its back arrow would, so it saves what it saves.
 */
internal object OpenSheet {
    private var leave: (() -> Unit)? = null

    fun opened(onLeave: () -> Unit) {
        leave = onLeave
    }

    fun closed(onLeave: () -> Unit) {
        if (leave === onLeave) leave = null
    }

    fun dismiss() {
        leave?.also { leave = null }?.invoke()
    }
}

/** The widest a sheet's fields get in the pane: a form, not a banner. */
private val SHEET_WIDTH = 760.dp

/**
 * @param onDismiss leaves the sheet: the back arrow, the Back key, a tap beside the dialog
 * @param onStepBack a step back inside the sheet, while it is on an inner screen
 * @param actions its buttons ([SheetButton]), the main one last
 */
@Composable
internal fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    onStepBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!LocalSheetInPane.current) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = onDismiss,
            containerColor = DashColors.Card,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onStepBack != null) {
                        IconButton(onClick = onStepBack, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back), tint = DashColors.TextPrimary)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(title, color = DashColors.TextPrimary)
                }
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            },
            confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions) }
        )
        return
    }

    val back = onStepBack ?: onDismiss
    BackHandler(onBack = back)
    val leave by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        val onLeave = { leave() }
        OpenSheet.opened(onLeave)
        onDispose { OpenSheet.closed(onLeave) }
    }
    val tap = rememberTapFeedback()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(DashSize.TouchPrimary)
                    .clip(DashShape.Medium)
                    .background(DashColors.CardHi)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.setup_back)) { tap(); back() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back),
                    tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(color = DashColors.Line)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The keyboard takes the bottom of the screen: what is typed in stays above it.
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.widthIn(max = SHEET_WIDTH).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/**
 * A sheet's button: a text button in the dialog, a full one in the pane
 * ([DashSize.TouchPrimary] tall); [primary] is the one that keeps the changes.
 */
@Composable
internal fun SheetButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    when {
        !LocalSheetInPane.current -> TextButton(onClick = onClick) {
            Text(text, color = if (primary) DashColors.Accent else DashColors.Muted)
        }
        primary -> Button(
            onClick = { tap(); onClick() },
            modifier = Modifier.heightIn(min = DashSize.TouchPrimary),
            colors = buttonColors(),
            shape = DashShape.Medium,
            contentPadding = PaddingValues(horizontal = 24.dp)
        ) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        else -> OutlinedButton(
            onClick = { tap(); onClick() },
            modifier = Modifier.heightIn(min = DashSize.TouchPrimary),
            border = BorderStroke(1.dp, DashColors.Line),
            shape = DashShape.Medium,
            contentPadding = PaddingValues(horizontal = 24.dp)
        ) { Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
    }
}
