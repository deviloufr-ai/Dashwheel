package com.openauto.dash

import com.openauto.dash.link.ClusterRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The route the in-app navigation follows, for the map the second screen
 * draws itself ([ClusterRoute]): sent when it changes (a reroute) and again
 * when a display links, empty once the guidance ends. Maps and Waze keep
 * their route to themselves: the display then shows the car and the next
 * turn without a line.
 */
internal object ClusterRouteFeed {

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(DisplayLink.state, InAppNav.guidance) { link, guidance ->
                if (link !is DisplayLinkState.Connected) null else (guidance?.route?.geometry?.let { Polyline.decode(it, 6) } ?: emptyList())
            }
                // The same link and the same route send nothing; a new link sends the route again.
                .distinctUntilChanged { a, b -> a === b || (a != null && b != null && a == b) }
                .collect { points -> if (points != null) DisplayLink.send(message(points)) }
        }
    }

    /** The route as the link carries it: lat, lon pairs, thinned to [ClusterRoute.MAX_POINTS]. */
    internal fun message(points: List<Pair<Double, Double>>): ClusterRoute {
        val kept = if (points.size <= ClusterRoute.MAX_POINTS) points else {
            val step = points.size.toDouble() / ClusterRoute.MAX_POINTS
            (0 until ClusterRoute.MAX_POINTS).map { points[(it * step).toInt()] } + points.last()
        }
        return ClusterRoute(kept.flatMap { (lat, lon) -> listOf(Math.round(lat * 1e5) / 1e5, Math.round(lon * 1e5) / 1e5) })
    }
}
