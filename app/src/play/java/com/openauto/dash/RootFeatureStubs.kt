package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Play edition: no root, no internal ADB, no PMPatch3 (see Edition). These
 * stand in for the GitHub edition's root tooling (app/src/github) with the
 * same public surface, so the shared code compiles unchanged. Every call
 * answers "not available" without running a process, opening a socket or
 * touching /system.
 */

/** The unit's internal ADB socket: never opened in this edition. */
object AdbInstaller {
    const val DEFAULT_PORT = 9876

    fun announcedPort(): Int = DEFAULT_PORT

    /** Nothing listens, as far as this edition is concerned: no probe is made. */
    internal fun listeningPort(): Int? = null

    internal fun openShell(context: Context, port: Int, timeoutMs: Int): DockShell.AdbShell =
        throw IllegalStateException(NOT_HERE)

    fun installViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> =
        Result.failure(IllegalStateException(NOT_HERE))

    fun rebootViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> =
        Result.failure(IllegalStateException(NOT_HERE))

    private const val NOT_HERE = "no ADB shell in the Play edition"
}

/** The privileged system-app install: never offered, and refused if ever asked. */
object PrivApp {
    /** What the root probe would run; never run here ([SystemInstaller.isRootAvailable] answers first). */
    val rootProbe: String = "id"

    fun install(context: Context): Result<Unit> = Result.failure(IllegalStateException(SystemInstaller.NO_ROOT_PLAY))

    fun rebootDevice(context: Context): Result<Unit> = Result.failure(IllegalStateException(SystemInstaller.NO_ROOT_PLAY))
}

/** PMPatch3 setup: nothing to finish in this edition. */
internal object PmPatch {
    fun finishSetup(context: Context) = Unit
}

/** Never shown: the row that opens it is left out of the Play edition's settings. */
@Composable
internal fun PmPatchDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

/** The ahead-of-time compile after an update runs through the unit's ADB: not in this edition. */
internal object CompileAfterUpdate {
    fun schedule(context: Context) = Unit
}

/** The boot logo writes the unit's partitions as root: never offered in this edition. */
internal object BootLogoSupport {
    val available: Boolean = false
}

/** Never shown: the boot logo row is left out of the Play edition's settings. */
@Composable
internal fun BootLogoDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

/** The default-Home and system-copy upkeep run through the shell: nothing to do in this edition. */
internal object SystemUpkeep {
    fun start(context: Context) = Unit
}

/** The firmware's volume bar stays: hiding it takes a root module. */
internal object VolumeRomHide {
    const val FIRMWARE_PACKAGE = "com.qf.framework"

    /** Never in place in this edition. */
    val active: StateFlow<Boolean> = MutableStateFlow(false)

    fun refresh(context: Context) = Unit

    suspend fun apply(context: Context, hide: Boolean): Boolean = false
}
