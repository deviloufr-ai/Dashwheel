package com.openauto.dash.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Road watch on the Car tab: what it does, the vehicle finder to fetch once
 * ([RoadModel]), and the button that opens the camera ([RoadWatchActivity]).
 */
@Composable
internal fun RoadWatchCard(connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model by RoadModel.state.collectAsState()
    LaunchedEffect(Unit) { RoadModel.check(context) }
    Panel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeading(
                Icons.Filled.Traffic, stringResource(R.string.road_title), stringResource(R.string.road_detail),
                if (connected) CompanionColors.Teal else CompanionColors.Blue
            )
            when (val m = model) {
                RoadModel.State.Ready -> Unit
                is RoadModel.State.Getting -> LinearProgressIndicator(progress = { m.done }, modifier = Modifier.fillMaxWidth())
                RoadModel.State.Failed -> Text(stringResource(R.string.road_model_failed), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Amber)
                RoadModel.State.Missing -> Text(stringResource(R.string.road_model_none), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { RoadWatchActivity.open(context) }) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.road_start))
                }
                if (model == RoadModel.State.Missing || model == RoadModel.State.Failed) {
                    OutlinedButton(onClick = { scope.launch { RoadModel.get(context) } }) {
                        Text(stringResource(if (model == RoadModel.State.Failed) R.string.road_model_retry else R.string.road_model_get))
                    }
                }
            }
            Text(stringResource(R.string.road_note), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
        }
    }
}
