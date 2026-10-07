package com.openauto.dash

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date

/** A recorded note: its file and when it was made. */
internal data class VoiceNote(val file: File) {
    val at: Long get() = file.lastModified()
}

/**
 * Voice notes recorded at the wheel: a tap starts, a tap stops, kept in the
 * app's own files; the latest are played back or deleted from the tile.
 */
internal object VoiceNotes {
    private const val TAG = "VoiceNotes"
    private const val DIR = "voice_notes"
    private const val KEPT = 30
    const val SHOWN = 3

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var current: File? = null

    private val _recordingSince = MutableStateFlow<Long?>(null)
    val recordingSince: StateFlow<Long?> = _recordingSince
    private val _notes = MutableStateFlow<List<VoiceNote>>(emptyList())
    val notes: StateFlow<List<VoiceNote>> = _notes
    private val _playing = MutableStateFlow<File?>(null)
    val playing: StateFlow<File?> = _playing

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun load(context: Context) {
        _notes.value = dir(context).listFiles { f -> f.extension == "m4a" }.orEmpty()
            .sortedByDescending { it.lastModified() }.map { VoiceNote(it) }
    }

    fun toggle(context: Context) = if (recorder != null) stop(context) else record(context)

    @Suppress("DEPRECATION")
    private fun record(context: Context) {
        stopPlaying()
        val file = File(dir(context), "note-${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = file
            _recordingSince.value = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "can't record", e)
            runCatching { r.release() }
            file.delete()
        }
    }

    private fun stop(context: Context) {
        val r = recorder ?: return
        recorder = null
        _recordingSince.value = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok) current?.delete()
        current = null
        // Only the latest are kept.
        dir(context).listFiles { f -> f.extension == "m4a" }.orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT).forEach { it.delete() }
        load(context)
    }

    fun play(note: VoiceNote) {
        if (_playing.value == note.file) return stopPlaying()
        stopPlaying()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(note.file.absolutePath)
                setOnCompletionListener { stopPlaying() }
                prepare()
                start()
            }
            _playing.value = note.file
        }.onFailure { Log.w(TAG, "can't play ${note.file.name}", it) }
    }

    fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _playing.value = null
    }

    fun delete(context: Context, note: VoiceNote) {
        if (_playing.value == note.file) stopPlaying()
        note.file.delete()
        load(context)
    }
}

@Composable
internal fun VoiceNotesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mic = rememberPermission(Manifest.permission.RECORD_AUDIO)
    LaunchedEffect(Unit) { VoiceNotes.load(context) }
    val since by VoiceNotes.recordingSince.collectAsState()
    val notes by VoiceNotes.notes.collectAsState()
    val playing by VoiceNotes.playing.collectAsState()
    val units by Units.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (since != null) { now = System.currentTimeMillis(); delay(500) } }
    val tap = rememberTapFeedback()
    val lock = LocalDriveLock.current
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            val recording = since != null
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = DashSize.TouchPrimary).clip(DashShape.Medium)
                    .background(if (recording) DashColors.Critical.copy(alpha = 0.3f) else DashColors.Accent.copy(alpha = 0.2f))
                    .clickable(role = Role.Button) { tap(); if (mic.granted) VoiceNotes.toggle(context) else mic.request() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, tint = if (recording) DashColors.Critical else DashColors.Accent)
                    Text(
                        "  " + if (recording) stringResource(R.string.widgets_notes_stop, ((now - since!!) / 1000).toInt()) else stringResource(R.string.widgets_notes_record),
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            if (notes.isEmpty()) {
                Text(stringResource(R.string.widgets_notes_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            notes.take(VoiceNotes.SHOWN).forEach { note ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { tap(); VoiceNotes.play(note) }, modifier = Modifier.size(DashSize.Touch)) {
                        Icon(if (playing == note.file) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.widgets_notes_play), tint = DashColors.TextPrimary)
                    }
                    Text(
                        units.time(Date(note.at)), color = DashColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                    )
                    // Deleting is for a stop: at the wheel the tap gets the parked-only notice instead.
                    IconButton(onClick = { tap(); lock.whenParked { VoiceNotes.delete(context, note) } }, modifier = Modifier.size(DashSize.Touch)) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.widgets_notes_delete), tint = DashColors.Muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun voiceNotesFace(): WidgetFace {
    val context = LocalContext.current
    val mic = rememberPermission(Manifest.permission.RECORD_AUDIO)
    LaunchedEffect(Unit) { VoiceNotes.load(context) }
    val since by VoiceNotes.recordingSince.collectAsState()
    val notes by VoiceNotes.notes.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Mic,
        title = BuiltinKind.VOICE_NOTES.label,
        value = notes.size.toString(),
        alert = since != null,
        caption = if (since != null) stringResource(R.string.widgets_notes_recording) else pluralStringResource(R.plurals.widgets_notes_count, notes.size),
        actions = listOf(
            FaceAction(
                if (since != null) Icons.Filled.Stop else Icons.Filled.Mic,
                stringResource(R.string.widgets_notes_record),
                onClick = { if (mic.granted) VoiceNotes.toggle(context) else mic.request() }, primary = true
            )
        )
    )
}
