package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app-wide bus: providers register on it, consumers read it. */
object Vehicle {
    val bus: VehicleBus by lazy { VehicleBus(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}

/** The resolved value of [key], null while unknown; recomposes only when it really changes. */
@Composable
fun <T : Any> rememberVehicle(key: Key<T>): T? {
    val flow = remember(key) { Vehicle.bus.value(key) }
    val value by flow.collectAsState(null)
    return value
}
