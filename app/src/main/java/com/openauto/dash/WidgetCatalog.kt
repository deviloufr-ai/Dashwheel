package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsCarFilled
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The built-in tile catalogue: names, blurbs and icons (the picker is AddSheet.kt).
 */

/** Display name of a built-in tile, in the current language. */
val BuiltinKind.label: String
    @Composable get() = stringResource(labelRes)

/** One-line description of a built-in tile for the picker, in the current language. */
val BuiltinKind.blurb: String
    @Composable get() = stringResource(blurbRes)

internal fun kindIcon(kind: BuiltinKind): ImageVector = when (kind) {
    BuiltinKind.NAVMAP -> Icons.Filled.Navigation
    BuiltinKind.NAVIGATION -> Icons.Filled.Directions
    BuiltinKind.PIP_ANCHOR -> Icons.Filled.PictureInPicture
    BuiltinKind.MAPS_INSIDE -> Icons.Filled.Map
    BuiltinKind.MEDIA -> Icons.Filled.MusicNote
    BuiltinKind.TELEMETRY -> Icons.Filled.Speed
    BuiltinKind.OBD_DTC -> Icons.Filled.Warning
    BuiltinKind.OBD_ALL -> Icons.Filled.Sensors
    BuiltinKind.RANGE -> Icons.Filled.LocalGasStation
    BuiltinKind.DOORS -> Icons.Filled.SensorDoor
    BuiltinKind.CAN_MON -> Icons.Filled.Sensors
    BuiltinKind.SPEED_HUD -> Icons.Filled.Speed
    BuiltinKind.COMPASS -> Icons.Filled.Explore
    BuiltinKind.TRIP -> Icons.Filled.Timeline
    BuiltinKind.GFORCE -> Icons.Filled.Adjust
    BuiltinKind.PARKING -> Icons.Filled.LocalParking
    BuiltinKind.CLOCK -> Icons.Filled.Schedule
    BuiltinKind.WEATHER -> Icons.Filled.WbSunny
    BuiltinKind.CALENDAR -> Icons.Filled.Event
    BuiltinKind.QUICK_DIAL -> Icons.Filled.Call
    BuiltinKind.NOTIFICATIONS -> Icons.Filled.Notifications
    BuiltinKind.AUDIO -> Icons.Filled.VolumeUp
    BuiltinKind.FILTER_CARE -> Icons.Filled.FilterAlt
    BuiltinKind.WARMUP -> Icons.Filled.Thermostat
    BuiltinKind.BATTERY -> Icons.Filled.BatteryChargingFull
    BuiltinKind.MY_CAR -> Icons.Filled.CarRepair
    BuiltinKind.CAR_STATUS -> Icons.Filled.DirectionsCarFilled
    BuiltinKind.TYRES -> Icons.Filled.TireRepair
    BuiltinKind.ECO_DRIVE -> Icons.Filled.Eco
    BuiltinKind.BREAK_TIMER -> Icons.Filled.Coffee
    BuiltinKind.FUEL_TO_DEST -> Icons.Filled.EvStation
    BuiltinKind.SERVICE -> Icons.Filled.Build
    BuiltinKind.FUEL_PRICES -> Icons.Filled.LocalGasStation
    BuiltinKind.DASH_BAR -> Icons.Filled.Dashboard
    BuiltinKind.HEAD_UNIT -> Icons.Filled.Memory
    BuiltinKind.GEAR -> Icons.Filled.SettingsInputComponent
}
