package com.openauto.dash.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.openauto.dash.carphoto.Argb
import com.openauto.dash.carphoto.CarPhotoKit
import com.openauto.dash.carphoto.CarPhotoResult
import com.openauto.dash.carphoto.CarPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/*
 * EXPERIMENTAL: the driver's car from three photos (side, front, back), built
 * on the phone by the shared car photo kit, checked here, then sent to the
 * car as a car pack ([CarLookSender]): its own picture from the side and a
 * view from above for the tyre tile, with where the wheels are on each.
 */

/** Which photo a slot holds, in the order they are asked for. */
internal enum class CarShot(val titleRes: Int) {
    SIDE(R.string.car_photos_side), FRONT(R.string.car_photos_front), BACK(R.string.car_photos_back)
}

/** A photo as picked: the decoded picture and a small copy for its slot. */
private class Shot(val photo: Argb, val thumb: Bitmap)

/** What building ended with. */
private sealed interface Built {
    class Car(val result: CarPhotoResult, val side: Bitmap, val top: Bitmap?) : Built
    class NotFound(val shot: CarShot) : Built
}

/** Photos are decoded about this long a side; the kit works at [CarPhotoKit.MAX_SIDE]. */
private const val DECODE_SIDE = 2000

@Composable
internal fun CarFromPhotosScreen(connected: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shots = remember { mutableStateMapOf<CarShot, Shot>() }
    var built by remember { mutableStateOf<Built.Car?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The slot a camera or gallery answer is for.
    var asking by remember { mutableStateOf<CarShot?>(null) }
    var menuFor by remember { mutableStateOf<CarShot?>(null) }
    val status by CarLookSender.status.collectAsState()

    fun take(shot: CarShot, uri: Uri) {
        busy = true
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { runCatching { decode(context, uri) }.getOrNull() }
            busy = false
            if (decoded == null) Toast.makeText(context, R.string.car_look_failed, Toast.LENGTH_SHORT).show()
            else shots[shot] = decoded
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val shot = asking
        if (uri != null && shot != null) take(shot, uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val shot = asking
        if (ok && shot != null) take(shot, cameraUri(context, shot))
    }
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val shot = asking
        if (ok && shot != null) camera.launch(cameraUri(context, shot))
    }
    val openCamera = { shot: CarShot ->
        asking = shot
        // The QR scanner declares the camera, so the camera app may only be used once it is allowed.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.launch(cameraUri(context, shot))
        } else {
            cameraAllowed.launch(Manifest.permission.CAMERA)
        }
    }
    val openGallery = { shot: CarShot ->
        asking = shot
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun build() {
        val side = shots[CarShot.SIDE] ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                buildCar(side.photo, shots[CarShot.FRONT]?.photo, shots[CarShot.BACK]?.photo)
            }
            busy = false
            when (result) {
                is Built.Car -> built = result
                is Built.NotFound -> {
                    val which = context.getString(result.shot.titleRes).lowercase()
                    Toast.makeText(context, context.getString(R.string.car_photos_not_found, which), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(onBack = onClose)
    Surface(color = CompanionColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (built == null) R.string.car_photos_title else R.string.car_photos_result),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.car_photos_close)) }
            }
            val car = built
            if (car == null) {
                Text(stringResource(R.string.car_photos_detail), color = CompanionColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Panel(Modifier.padding(top = 14.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (shot in CarShot.entries) {
                            Box {
                                ShotSlot(shot, shots[shot]) { menuFor = shot }
                                DropdownMenu(expanded = menuFor == shot, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.car_photos_take)) }, onClick = { menuFor = null; openCamera(shot) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.car_photos_choose)) }, onClick = { menuFor = null; openGallery(shot) })
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.car_photos_tip), color = CompanionColors.Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)
                )
                Button(
                    onClick = { build() },
                    enabled = !busy && CarShot.entries.all { it in shots },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CompanionColors.Background)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.car_photos_building))
                    } else {
                        Text(stringResource(R.string.car_photos_build))
                    }
                }
            } else {
                Text(stringResource(R.string.car_photos_result_hint), color = CompanionColors.Muted, style = MaterialTheme.typography.bodyMedium)
                CarPreview(car.side, car.result.side.wheels, Modifier.padding(top = 14.dp).fillMaxWidth().height(160.dp))
                car.top?.let { top ->
                    CarPreview(top, car.result.top?.wheels.orEmpty(), Modifier.padding(top = 10.dp).fillMaxWidth().height(260.dp))
                }
                if (car.result.side.facedLeft) {
                    Text(
                        stringResource(R.string.car_photos_turned), color = CompanionColors.Muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(CompanionColors.SurfaceHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                val line = when {
                    status == CarLookSender.Status.SENDING -> stringResource(R.string.car_look_sending)
                    status == CarLookSender.Status.SENT -> stringResource(R.string.car_look_sent)
                    status == CarLookSender.Status.TOO_BIG -> stringResource(R.string.car_look_too_big)
                    status == CarLookSender.Status.FAILED -> stringResource(R.string.car_look_failed)
                    !connected -> stringResource(R.string.car_look_offline)
                    else -> null
                }
                line?.let {
                    val tint = if (status == CarLookSender.Status.SENT) CompanionColors.Teal else if (connected) CompanionColors.Muted else CompanionColors.Amber
                    Text(it, color = tint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                Button(
                    onClick = {
                        scope.launch {
                            val pack = withContext(Dispatchers.Default) { packOf(car.result) }
                            CarLookSender.send(pack)
                        }
                    },
                    enabled = connected && status != CarLookSender.Status.SENDING,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp)
                ) { Text(stringResource(R.string.car_photos_send)) }
                OutlinedButton(
                    onClick = { built = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)
                ) { Text(stringResource(R.string.car_photos_change)) }
            }
        }
    }
}

/** One photo's slot: its picture once there, else a + to add it. */
@Composable
private fun ShotSlot(shot: CarShot, taken: Shot?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CompanionColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val box = Modifier.width(96.dp).height(60.dp).clip(RoundedCornerShape(10.dp))
        if (taken != null) {
            Box(box.background(CompanionColors.Background), contentAlignment = Alignment.Center) {
                Image(taken.thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            }
        } else {
            Box(box.border(1.5.dp, CompanionColors.Blue.copy(alpha = 0.5f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = CompanionColors.Blue)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(shot.titleRes), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(if (taken != null) R.string.car_photos_ready else R.string.car_photos_add),
                color = if (taken != null) CompanionColors.Teal else CompanionColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** A built picture with a ring on each wheel, as the car will use them. */
@Composable
private fun CarPreview(picture: Bitmap, wheels: List<CarPoint>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(StageColor), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(10.dp).aspectRatio(picture.width.toFloat() / picture.height, matchHeightConstraintsFirst = picture.height >= picture.width)) {
            Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val r = 7.dp.toPx()
                for (w in wheels) {
                    val c = Offset(w.x * size.width, w.y * size.height)
                    drawCircle(CompanionColors.Teal.copy(alpha = 0.25f), r + 4.dp.toPx(), c)
                    drawCircle(CompanionColors.Teal, r, c, style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}

/** Behind a built picture: darker than the cards, like the car's screen. */
private val StageColor = Color(0xFF070B16)

/** Where the camera app puts a slot's photo (shared through the app's FileProvider). */
private fun cameraUri(context: Context, shot: CarShot): Uri {
    val dir = File(context.cacheDir, "car_photos").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.logs", File(dir, "${shot.name.lowercase()}.jpg"))
}

/** A photo decoded upright (camera rotation applied) and no bigger than needed. */
private fun decode(context: Context, uri: Uri): Shot {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > DECODE_SIDE) {
            val scale = DECODE_SIDE.toFloat() / longest
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    }
    val argb = bitmap.toArgb()
    val thumbScale = 240f / max(bitmap.width, bitmap.height)
    val thumb = Bitmap.createScaledBitmap(bitmap, (bitmap.width * thumbScale).toInt().coerceAtLeast(1), (bitmap.height * thumbScale).toInt().coerceAtLeast(1), true)
    if (thumb !== bitmap) bitmap.recycle()
    return Shot(argb, thumb)
}

/** The car from its photos; a photo with no car in it is named, so the driver knows which to redo. */
private fun buildCar(side: Argb, front: Argb?, back: Argb?): Built {
    for ((shot, photo) in listOf(CarShot.SIDE to side, CarShot.FRONT to front, CarShot.BACK to back)) {
        if (photo != null && runCatching { CarPhotoKit.cutOut(photo) }.isFailure) return Built.NotFound(shot)
    }
    val result = runCatching { CarPhotoKit.build(side, front, back) }.getOrElse { return Built.NotFound(CarShot.SIDE) }
    return Built.Car(result, result.side.image.toBitmap(), result.top?.image?.toBitmap())
}

/** The car pack the head unit imports: car.json and the pictures as PNG. */
private fun packOf(result: CarPhotoResult): ByteArray {
    val files = LinkedHashMap<String, ByteArray>()
    files["car.json"] = result.toCarJson().toByteArray()
    for ((name, picture) in result.packEntries()) files[name] = picture.toPng()
    return CarPhotoKit.zip(files)
}
