package com.openauto.dash.companion

import android.content.Context
import android.net.Uri
import com.openauto.dash.link.CarLookAck
import com.openauto.dash.link.CarLookPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL: sends a picture of the driver's own car (a car pack from
 * tools/mycar/bake_car.py, or a picture of the car from the side) to the head
 * unit, which shows it in place of its drawn car. In [CarLookPart]s, as one
 * frame can't hold it; the head unit answers once it has it in ([CarLookAck]).
 */
object CarLookSender {
    enum class Status { IDLE, SENDING, SENT, TOO_BIG, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** How long the head unit gets to prepare the pictures once the last part is sent. */
    private const val ANSWER_WAIT_S = 90L

    @Volatile private var waiting: Pair<Long, CountDownLatch>? = null
    @Volatile private var answer = false

    fun send(context: Context, uri: Uri) {
        if (_status.value == Status.SENDING) return
        _status.value = Status.SENDING
        val app = context.applicationContext
        Thread({ _status.value = sendNow(app, uri) }, "car-look-send").start()
    }

    private fun sendNow(context: Context, uri: Uri): Status {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > CarLookPart.MAX_BYTES) return Status.TOO_BIG
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return Status.FAILED
        if (bytes.isEmpty()) return Status.FAILED
        if (LinkServer.state.value !is LinkState.Connected) return Status.FAILED

        val id = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        answer = false
        waiting = id to latch
        val count = (bytes.size + CarLookPart.PART_BYTES - 1) / CarLookPart.PART_BYTES
        val encoder = Base64.getEncoder()
        for (i in 0 until count) {
            val from = i * CarLookPart.PART_BYTES
            val to = minOf(bytes.size, from + CarLookPart.PART_BYTES)
            LinkServer.send(CarLookPart(id, i, count, "car", encoder.encodeToString(bytes.copyOfRange(from, to))))
        }
        val answered = latch.await(ANSWER_WAIT_S, TimeUnit.SECONDS)
        waiting = null
        return if (answered && answer) Status.SENT else Status.FAILED
    }

    /** The head unit's answer. */
    fun acked(ack: CarLookAck) {
        val (id, latch) = waiting ?: return
        if (ack.id != id) return
        answer = ack.ok
        latch.countDown()
    }
}
