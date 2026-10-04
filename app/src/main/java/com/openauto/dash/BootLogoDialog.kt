package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The boot logo choice, kept so the picker opens where it was left. */
private object BootLogoPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("boot_logo", Context.MODE_PRIVATE)

    // The launcher's preset car is a Citroën.
    fun slug(context: Context): String = prefs(context).getString("slug", null) ?: "citroen"
    fun showName(context: Context): Boolean = prefs(context).getBoolean("show_name", false)
    fun stillLogo(context: Context): Boolean = prefs(context).getBoolean("still_logo", false)
    fun installed(context: Context): String? = prefs(context).getString("installed", null)

    fun save(context: Context, slug: String, showName: Boolean, stillLogo: Boolean) =
        prefs(context).edit().putString("slug", slug).putBoolean("show_name", showName).putBoolean("still_logo", stillLogo).apply()

    fun setInstalled(context: Context, slug: String?) = prefs(context).edit().putString("installed", slug).apply()
}

private sealed interface BootStatus {
    data object Idle : BootStatus
    data class Busy(val text: Int) : BootStatus
    data class Done(val text: String) : BootStatus
    data class Failed(val text: String) : BootStatus
}

/**
 * Picks a car make from the full list (logos downloaded as they scroll into
 * view) and turns its logo into the head unit's Android boot animation.
 */
@Composable
internal fun BootLogoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val brands = remember { CarBrands.all(context) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(CarBrands.find(context, BootLogoPrefs.slug(context)) ?: brands.first()) }
    var showName by remember { mutableStateOf(BootLogoPrefs.showName(context)) }
    var stillLogo by remember { mutableStateOf(BootLogoPrefs.stillLogo(context)) }
    var light by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(CarBrands.find(context, BootLogoPrefs.installed(context))) }
    var status by remember { mutableStateOf<BootStatus>(BootStatus.Idle) }
    // Asked before the logo partition is written: the still logo's install, and the restore.
    var confirmStill by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val screen = remember { BootAnimationMaker.screenSize(context) }
    val shown = remember(query) { CarBrands.search(brands, query) }
    val grid = rememberLazyGridState()

    // The chosen brand's large logo; the background follows it (white behind dark logos).
    val logo by produceState<Bitmap?>(null, chosen) {
        value = null
        value = CarLogos.full(context, chosen.slug, maxPx = 1024)?.let { raw ->
            withContext(Dispatchers.Default) { BootAnimationMaker.trim(raw) }
        }
    }
    LaunchedEffect(logo) { logo?.let { light = withContext(Dispatchers.Default) { BootAnimationMaker.isDark(it) } } }
    LaunchedEffect(Unit) {
        val index = brands.indexOf(chosen)
        if (index > 0) grid.scrollToItem(index)
    }
    val style = BootStyle(light, showName)
    val preview by produceState<Bitmap?>(null, logo, style) {
        // Half size is plenty for the preview.
        value = logo?.let { l ->
            withContext(Dispatchers.Default) { BootAnimationMaker.still(l, chosen.name, screen.first / 2, screen.second / 2, style) }
        }
    }

    fun install() {
        val l = logo ?: return
        val brand = chosen
        val withStill = stillLogo
        BootLogoPrefs.save(context, brand.slug, showName, withStill)
        scope.launch {
            status = BootStatus.Busy(R.string.boot_building)
            val zip = File(context.cacheDir, "bootanimation.zip")
            val jpeg = File(context.cacheDir, "bootlogo.jpg")
            val result = withContext(Dispatchers.IO) {
                runCatching { BootAnimationMaker.build(l, brand.name, screen.first, screen.second, style, zip) }
                    .mapCatching {
                        status = BootStatus.Busy(R.string.boot_installing)
                        val place = BootAnimationInstaller.install(context, zip).getOrThrow()
                        // The still logo is extra: its failure doesn't undo the animation.
                        val still = if (!withStill) null else runCatching {
                            val panel = BootAnimationInstaller.panel(context).getOrThrow()
                                ?: error(context.getString(R.string.boot_still_unsupported))
                            val picture = BootAnimationMaker.stillForPanel(l, brand.name, screen.first, screen.second, style, panel)
                            BootAnimationMaker.writeJpeg(picture, jpeg)
                            val (pw, ph) = picture.width to picture.height
                            picture.recycle()
                            BootAnimationInstaller.installStillLogo(context, jpeg, pw, ph).getOrThrow()
                        }
                        // Marked where the work ends: the car setting off closes Settings under it.
                        BootLogoPrefs.setInstalled(context, brand.slug)
                        place to still
                    }
                    .also { zip.delete(); jpeg.delete() }
            }
            status = result.fold(
                onSuccess = { (place, still) ->
                    installed = brand
                    val text = context.getString(R.string.boot_installed, place)
                    when {
                        still == null -> BootStatus.Done(text)
                        still.isSuccess -> BootStatus.Done(text + "\n" + context.getString(R.string.boot_still_done))
                        else -> BootStatus.Failed(
                            text + "\n" + context.getString(R.string.boot_still_failed, still.exceptionOrNull()?.message.orEmpty())
                        )
                    }
                },
                onFailure = { BootStatus.Failed(context.getString(R.string.boot_install_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    fun saveToUsb() {
        val l = logo ?: return
        val brand = chosen
        BootLogoPrefs.save(context, brand.slug, showName, stillLogo)
        scope.launch {
            status = BootStatus.Busy(R.string.boot_building)
            val zip = File(context.cacheDir, "bootanimation.zip")
            val bmpName = "${brand.slug}_logo.bmp"
            val bmp = File(context.cacheDir, bmpName)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BootAnimationMaker.build(l, brand.name, screen.first, screen.second, style, zip)
                    val panel = BootAnimationInstaller.panel(context).getOrNull()
                    val picture = BootAnimationMaker.stillForPanel(l, brand.name, screen.first, screen.second, style, panel)
                    BootAnimationMaker.writeBmp(picture, bmp)
                    picture.recycle()
                }
                    .mapCatching {
                        status = BootStatus.Busy(R.string.boot_saving_usb)
                        BootAnimationInstaller.saveToUsb(context, zip, bmp, bmpName).getOrThrow()
                    }
                    .also { zip.delete(); bmp.delete() }
            }
            status = result.fold(
                onSuccess = { sticks -> BootStatus.Done(context.getString(R.string.boot_saved_usb, sticks.joinToString(), bmpName)) },
                onFailure = { BootStatus.Failed(context.getString(R.string.boot_install_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    fun restore() {
        scope.launch {
            status = BootStatus.Busy(R.string.boot_restoring)
            val result = withContext(Dispatchers.IO) {
                BootAnimationInstaller.restore(context).onSuccess { BootLogoPrefs.setInstalled(context, null) }
            }
            status = result.fold(
                onSuccess = { changed ->
                    installed = null
                    BootStatus.Done(context.getString(if (changed) R.string.boot_restored else R.string.boot_nothing_to_restore))
                },
                onFailure = { BootStatus.Failed(context.getString(R.string.boot_restore_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    fun play() {
        scope.launch {
            status = BootStatus.Busy(R.string.boot_playing)
            val result = withContext(Dispatchers.IO) { BootAnimationInstaller.play(context) }
            status = result.fold(
                onSuccess = { BootStatus.Idle },
                onFailure = { BootStatus.Failed(context.getString(R.string.boot_play_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    // An install or a restore under way is seen through: its result shows here and nowhere else.
    val busy = status is BootStatus.Busy
    val close = { if (!busy) onDismiss() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .keepClearOfWindows()
                .clip(DashShape.Large)
                .background(DashColors.Card.copy(alpha = 1f))
                .border(1.dp, DashColors.Line, DashShape.Large)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.boot_title), color = DashColors.TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = close, enabled = !busy) {
                    Text(stringResource(R.string.boot_close), color = if (busy) DashColors.Muted else DashColors.Accent)
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // The list of makes.
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = DashColors.TextSecondary) },
                        placeholder = { Text(stringResource(R.string.boot_search, brands.size), color = DashColors.Muted) },
                        colors = fieldColors()
                    )
                    if (shown.isEmpty()) {
                        Text(stringResource(R.string.boot_no_match), color = DashColors.TextSecondary)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        state = grid,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.slug }) { brand ->
                            BrandCell(brand, brand == chosen) { chosen = brand }
                        }
                    }
                }

                // The chosen make as it will boot, and what to do with it.
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.height(170.dp)
                            .align(Alignment.CenterHorizontally)
                            .aspectRatio(screen.first.toFloat() / screen.second)
                            .clip(DashShape.Medium)
                            .background(if (light) Color.White else Color.Black)
                            .border(1.dp, DashColors.Line, DashShape.Medium),
                        contentAlignment = Alignment.Center
                    ) {
                        val p = preview
                        if (p != null) {
                            Image(p.asImageBitmap(), contentDescription = chosen.name, modifier = Modifier.fillMaxSize())
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(28.dp))
                                Text(stringResource(R.string.boot_downloading), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val black = stringResource(R.string.boot_bg_black)
                    val white = stringResource(R.string.boot_bg_white)
                    ChoiceRow(listOf(false, true), light, { if (it) white else black }) { light = it }
                    SwitchRow(stringResource(R.string.boot_show_name), stringResource(R.string.boot_show_name_detail), showName) { showName = it }
                    SwitchRow(stringResource(R.string.boot_still), stringResource(R.string.boot_still_detail), stillLogo) { stillLogo = it }

                    val ready = logo != null && !busy
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (stillLogo) confirmStill = true else install() },
                            enabled = ready,
                            modifier = Modifier.weight(1f),
                            colors = buttonColors(),
                            shape = DashShape.Small
                        ) {
                            Text(stringResource(R.string.boot_install), textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = ::saveToUsb,
                            enabled = ready,
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, if (ready) DashColors.Accent else DashColors.Line),
                            shape = DashShape.Small
                        ) {
                            Text(stringResource(R.string.boot_usb), color = if (ready) DashColors.Accent else DashColors.Muted, textAlign = TextAlign.Center)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = ::play, enabled = installed != null && !busy) {
                            Text(stringResource(R.string.boot_play), color = if (installed != null && !busy) DashColors.Accent else DashColors.Muted)
                        }
                        TextButton(onClick = { confirmRestore = true }, enabled = !busy) {
                            Text(stringResource(R.string.boot_restore), color = if (!busy) DashColors.Accent else DashColors.Muted)
                        }
                    }
                    when (val s = status) {
                        is BootStatus.Busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = DashColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text(stringResource(s.text), color = DashColors.TextSecondary)
                        }
                        is BootStatus.Done -> Text(s.text, color = DashColors.Good)
                        is BootStatus.Failed -> Text(s.text, color = DashColors.Warning, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        BootStatus.Idle -> installed?.let {
                            Text(stringResource(R.string.boot_current, it.name), color = DashColors.TextSecondary)
                        }
                    }
                    Text(stringResource(R.string.boot_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmStill) {
        ConfirmDialog(
            title = stringResource(R.string.boot_still_confirm_title),
            body = stringResource(R.string.boot_still_confirm_body),
            action = stringResource(R.string.boot_install),
            onConfirm = { confirmStill = false; install() },
            onDismiss = { confirmStill = false }
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            title = stringResource(R.string.boot_restore_confirm_title),
            body = stringResource(R.string.boot_restore_confirm_body),
            action = stringResource(R.string.boot_restore),
            onConfirm = { confirmRestore = false; restore() },
            onDismiss = { confirmRestore = false }
        )
    }
}

/** One make: its logo on a white chip (dark logos stay readable on every theme) and its name. */
@Composable
private fun BrandCell(brand: CarBrand, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = DashShape.Medium
    val thumb by produceState(CarLogos.cachedThumb(brand.slug), brand.slug) {
        if (value == null) value = CarLogos.thumb(context, brand.slug)
    }
    Column(
        modifier = Modifier
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.CardHi, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.4f).clip(DashShape.Small).background(Color.White).padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(t.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(brand.name.take(1), color = Color.LightGray, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            brand.name,
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
