package com.openauto.dash

/**
 * How long to wait before asking a server again after it failed: the base wait
 * once, then double each further time in a row, up to a cap. A key the service
 * refused, a server down for the afternoon or a car in a tunnel then costs the
 * server one request every few minutes instead of one a minute for as long as
 * the tile is on screen.
 */
internal object Backoff {
    /**
     * The wait after [failures] failures in a row (1 for the first), from
     * [baseMs] doubling to at most [capMs]. Below one failure the base wait.
     */
    fun doubling(baseMs: Long, capMs: Long, failures: Int): Long {
        if (failures <= 1) return minOf(baseMs, capMs)
        // 2^(failures - 1), guarded against the shift overflowing a Long.
        val factor = 1L shl (failures - 1).coerceAtMost(40)
        val wait = if (baseMs > capMs / factor) capMs else baseMs * factor
        return minOf(wait, capMs)
    }
}
