package com.openauto.dash

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One piece of a release's notes, as the update dialog lays it out. */
internal sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

/**
 * The GitHub release body (build.yml writes it from the commits: a `##`
 * title, a `###` heading per change, its body under it) turned into blocks.
 * Only the Markdown those notes use: headings, `-`/`*` bullets, paragraphs.
 */
internal object ReleaseNotes {
    // Commit trailers: attribution, not news.
    private val TRAILER = Regex("^(Co-Authored-By|Signed-off-by|Claude-Session):", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += NoteBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                TRAILER.containsMatchIn(line) -> Unit
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.drop(level).trim()
                    if (text.isNotEmpty()) blocks += NoteBlock.Heading(level, text)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    blocks += NoteBlock.Bullet(line.drop(2).trim())
                }
                // An indented line right under a bullet is that bullet, wrapped.
                paragraph.isEmpty() && raw.first().isWhitespace() && blocks.lastOrNull() is NoteBlock.Bullet -> {
                    val last = blocks.removeAt(blocks.lastIndex) as NoteBlock.Bullet
                    blocks += NoteBlock.Bullet(last.text + " " + line)
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line)
                }
            }
        }
        flush()
        return blocks
    }

    /** The blocks to show under a dialog titled with the version: the notes' own `##` title goes. */
    fun body(markdown: String): List<NoteBlock> {
        val blocks = parse(markdown)
        val first = blocks.firstOrNull()
        return if (first is NoteBlock.Heading && first.level <= 2) blocks.drop(1) else blocks
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![\w*])[_*](?![\s*_])(.+?)(?<![\s*_])[_*](?![\w*])""")

/** `**bold**`, `` `code` `` and `_italic_` / `*italic*` as styles. */
private fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(at, m.range.first))
        val (bold, code, italic) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/**
 * What's in [info]'s release, from "Update to vX" in the ⋮ menu or Settings;
 * its Update button starts the install. Parked only, like the install itself.
 * The GitHub edition shows the Ko-fi ask beside the notes, with its QR code
 * for the phone ([SupportPanel]); Google Play allows no such link.
 */
@Composable
internal fun ReleaseNotesDialog(info: UpdateInfo, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val blocks = remember(info.notes) { ReleaseNotes.body(info.notes) }
    val support = Edition.full
    // Material's AlertDialog stops at 560 dp: too narrow for the notes and the ask side by side.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = DashShape.Large,
            color = DashColors.Card.copy(alpha = 1f),
            modifier = Modifier.widthIn(max = if (support) 900.dp else 560.dp).fillMaxWidth(0.94f).keepClearOfWindows()
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.update_notes_title, info.versionName),
                    color = DashColors.TextPrimary, style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                BoxWithConstraints {
                    // Wide enough: the ask stands beside the notes, seen without scrolling. Upright: under them.
                    val beside = support && maxWidth >= 600.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(
                            modifier = Modifier.weight(1f).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NoteBlocks(blocks)
                            if (support && !beside) {
                                Spacer(Modifier.height(10.dp))
                                SupportPanel(Modifier.fillMaxWidth())
                            }
                        }
                        if (beside) SupportPanel(Modifier.width(250.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
                    Button(onClick = onUpdate, colors = buttonColors()) { Text(stringResource(R.string.dash_update)) }
                }
            }
        }
    }
}

@Composable
private fun NoteBlocks(blocks: List<NoteBlock>) {
    val codeBackground = DashColors.CardHi
    if (blocks.isEmpty()) {
        Text(stringResource(R.string.update_notes_empty), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    blocks.forEach { block ->
        when (block) {
            is NoteBlock.Heading -> Text(
                inline(block.text, codeBackground),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            is NoteBlock.Bullet -> Row {
                Text("•", color = DashColors.AccentInk, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(inline(block.text, codeBackground), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            is NoteBlock.Paragraph -> Text(
                inline(block.text, codeBackground),
                color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The ask that comes with every release: who makes it and when, the Ko-fi QR code for the phone, and the link. */
@Composable
private fun SupportPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .clip(DashShape.Medium)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.update_support_title),
            color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.update_support_body),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
        )
        QrCode(KOFI_URL, Modifier.size(150.dp).clip(DashShape.Small))
        Text(stringResource(R.string.about_scan), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) }) {
            Text(stringResource(R.string.about_kofi), color = DashColors.AccentInk, textAlign = TextAlign.Center)
        }
    }
}
