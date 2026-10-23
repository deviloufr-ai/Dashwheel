package com.openauto.dash.vehicle

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The one place a source is chosen. Providers [register] and publish what they
 * know; consumers ask for an element by key. Per key the bus keeps what each
 * source last said and resolves them on every put, up/down, choice and expiry:
 *
 *   1. drop what is from a source that is down, expired (a Measurement's ttl) or withdrawn;
 *   2. the driver's chosen source, if it has a live value;
 *   3. else best quality first (DEGRADED loses), then lowest [SourceKind.rank],
 *      GOOD before ESTIMATED, then the newest.
 *
 * The resolved flow emits only when the value, the source or the quality
 * changes, never for a new timestamp alone, so a consumer that follows a key
 * wakes only for real changes. All state is per key and guarded by one lock;
 * there is no copy of "the whole car". See docs/vehicle-bus.md.
 */
class VehicleBus(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private val slots = HashMap<Key<*>, Slot<*>>()
    private val sources = HashMap<String, SourceState>()
    private val holds = HashMap<Key<*>, Int>()

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** State changes now; keys and fault codes later. */
    val events: SharedFlow<VehicleEvent> = _events.asSharedFlow()

    private val _wanted = MutableStateFlow<Set<Key<*>>>(emptySet())
    /** The keys somebody is listening to (a resolved flow, a pinned flow, a BestOf over it, or a [hold]). */
    val wanted: StateFlow<Set<Key<*>>> = _wanted.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(EXPIRY_TICK_MS)
                expireNow()
            }
        }
    }

    /** A provider's way in. Registering an id again gives another handle to the same source. */
    fun register(source: Source): Publisher {
        val state = synchronized(lock) { sources.getOrPut(source.id) { SourceState() } }
        return PublisherImpl(source, state)
    }

    /** The resolved value with where it came from and how far to trust it; [from] pins the read to one kind of source. */
    fun <T : Any> sample(key: Key<T>, from: SourceKind? = null): StateFlow<Sample<T>?> = synchronized(lock) {
        val slot = slotOf(key)
        if (from == null) slot.resolved.flow else slot.pinnedOut(from).flow
    }

    /**
     * What consumers use. [from] is a pinned read: that kind of source's live
     * value or null, whatever outranks it (it still expires and still counts as demand).
     */
    fun <T : Any> value(key: Key<T>, from: SourceKind? = null): Flow<T?> =
        sample(key, from).map { it?.value }.distinctUntilChanged()

    /** A snapshot for non-Compose code. Mark a key you read this way with [hold] if a provider should poll for it. */
    fun <T : Any> now(key: Key<T>): T? = synchronized(lock) { slotOf(key).pick(null, clock())?.value }

    /** Every live source's value, best first, for diagnostics. */
    fun <T : Any> candidates(key: Key<T>): StateFlow<List<Sample<T>>> = synchronized(lock) { slotOf(key).all.asStateFlow() }

    /** Whether some source that is up has published the key (it stays true through a quiet spell). */
    fun has(key: Key<*>): StateFlow<Boolean> = synchronized(lock) { slotOf(key).has.asStateFlow() }

    /** The driver's override: that source wins while it has a live value. null = automatic. */
    fun choose(key: Key<*>, sourceId: String?) = synchronized(lock) {
        slotOf(key).choose(sourceId)
        refreshAll(key)
    }

    /** Standing demand for [key], for code that reads it with [now]; release it when done. */
    fun hold(key: Key<*>): Hold {
        synchronized(lock) {
            holds[key] = (holds[key] ?: 0) + 1
            recomputeWanted()
        }
        return Hold {
            synchronized(lock) {
                val n = (holds[key] ?: 0) - 1
                if (n > 0) holds[key] = n else holds.remove(key)
                recomputeWanted()
            }
        }
    }

    /** One pass of the expiry job: re-resolves keys whose values may have aged out and emits conflated values that are due. */
    internal fun expireNow() = synchronized(lock) {
        val now = clock()
        for (slot in slots.values.toList()) slot.expire(now)
        for (slot in slots.values) slot.flushDue(now)
    }

    // --- Internals (every one runs under [lock]) ---------------------------------------

    private class SourceState { var up = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> slotOf(key: Key<T>): Slot<T> {
        (slots[key] as Slot<T>?)?.let { return it }
        val slot = Slot(key)
        slots[key] = slot
        slot.watch(slot.resolved)
        slot.refresh(clock())
        return slot
    }

    private fun refreshAll(key: Key<*>) {
        val slot = slots[key] ?: return
        slot.refresh(clock())
    }

    private fun recomputeWanted() {
        val set = HashSet<Key<*>>()
        fun add(slot: Slot<*>) {
            if (set.add(slot.key)) slot.members?.forEach { add(it) }
        }
        for (slot in slots.values) if (slot.listened()) add(slot)
        for (key in holds.keys) add(slotOf(key))
        _wanted.value = set
    }

    /** What a flow is showing, and when it may show the next. */
    private inner class Out<T : Any>(private val maxHz: Int?) {
        val flow = MutableStateFlow<Sample<T>?>(null)
        private var pending: Sample<T>? = null
        private var hasPending = false
        private var lastAt = Long.MIN_VALUE / 2
        private var scheduled = false

        private val periodMs: Long get() = if (maxHz == null) 0 else 1000L / maxHz

        fun offer(s: Sample<T>?, now: Long) {
            val cur = flow.value
            if (same(cur, s)) { hasPending = false; return }
            // A new value from the same source is what conflation holds back; anything else shows at once.
            if (maxHz != null && s != null && cur != null && s.source == cur.source) {
                val wait = lastAt + periodMs - now
                if (wait > 0) {
                    pending = s
                    hasPending = true
                    schedule(wait)
                    return
                }
            }
            emit(s, now)
        }

        fun flushDue(now: Long) {
            if (hasPending && now >= lastAt + periodMs) pending?.let { emit(it, now) }
        }

        private fun emit(s: Sample<T>?, now: Long) {
            hasPending = false
            pending = null
            lastAt = now
            flow.value = s
        }

        private fun schedule(waitMs: Long) {
            if (scheduled) return
            scheduled = true
            scope.launch {
                delay(waitMs)
                synchronized(lock) {
                    scheduled = false
                    val now = clock()
                    flushDue(now)
                    if (hasPending) schedule(maxOf(1L, lastAt + periodMs - now))
                }
            }
        }
    }

    /** Everything the bus knows about one key. A BestOf has [members] and no values of its own. */
    private inner class Slot<T : Any>(val key: Key<T>) {
        private val cands = HashMap<String, Sample<T>>()
        private val lastCounter = HashMap<String, Double>()
        /** A source's own lifetime for its current value ([Publisher.put]'s ttlMs), instead of the key's. */
        private val ttlOverride = HashMap<String, Long>()
        private var choice: String? = null
        private val pinned = HashMap<SourceKind, Out<T>>()
        private var truth: Sample<T>? = null
        private val parents = ArrayList<Slot<T>>()
        private val maxHz = (key as? Key.Measurement<T>)?.maxHz
        private val ttlMs = (key as? Key.Measurement<T>)?.ttlMs

        val members: List<Slot<T>>? = (key as? Key.BestOf<T>)?.members?.map { slotOf(it) }
        val resolved = Out<T>(maxHz)
        val all = MutableStateFlow<List<Sample<T>>>(emptyList())
        val has = MutableStateFlow(false)

        init {
            members?.forEach { it.parents.add(this) }
        }

        /** Whether anything listens to this key directly. */
        fun listened(): Boolean =
            resolved.flow.subscriptionCount.value > 0 || pinned.values.any { it.flow.subscriptionCount.value > 0 }

        /** Demand changes with the subscriber count, so each flow's count is watched. */
        fun watch(out: Out<T>) {
            scope.launch { out.flow.subscriptionCount.collect { synchronized(lock) { recomputeWanted() } } }
        }

        fun pinnedOut(kind: SourceKind): Out<T> = pinned.getOrPut(kind) {
            Out<T>(maxHz).also {
                it.offer(pick(kind, clock()), clock())
                watch(it)
            }
        }

        fun choose(sourceId: String?) {
            choice = sourceId
            members?.forEach { it.choose(sourceId) }
        }

        fun put(source: Source, value: T?, quality: Quality, atMs: Long, ttlMs: Long?) {
            if (value == null) {
                cands.remove(source.id)
                lastCounter.remove(source.id)
                ttlOverride.remove(source.id)
            } else {
                if (key is Key.Counter) {
                    val v = value as Double
                    val last = lastCounter[source.id]
                    if (last != null && v < last) return        // a glitch: the counter went backwards
                    lastCounter[source.id] = v
                }
                cands[source.id] = Sample(value, source, atMs, quality)
                if (ttlMs != null && this.ttlMs != null) ttlOverride[source.id] = ttlMs else ttlOverride.remove(source.id)
            }
            refresh(clock())
        }

        private fun live(now: Long): List<Sample<T>> = cands.values.filter {
            sources[it.source.id]?.up != false && (ttlMs == null || now - it.atMs <= (ttlOverride[it.source.id] ?: ttlMs))
        }

        /** The winner among live values (of one source [kind] when pinned); the driver's choice only counts unpinned. */
        fun pick(kind: SourceKind?, now: Long): Sample<T>? {
            members?.let { m -> return m.firstNotNullOfOrNull { it.pick(kind, now) } }
            val live = live(now)
            if (kind == null) {
                choice?.let { c -> live.firstOrNull { it.source.id == c }?.let { return it } }
                return live.minWithOrNull(ORDER)
            }
            return live.filter { it.source.kind == kind }.minWithOrNull(ORDER)
        }

        fun refresh(now: Long) {
            val before = truth
            truth = pick(null, now)
            resolved.offer(truth, now)
            for ((kind, out) in pinned) out.offer(pick(kind, now), now)
            if (members == null) {
                val list = live(now).sortedWith(ORDER)
                if (!sameList(all.value, list)) all.value = list
                has.value = cands.values.any { sources[it.source.id]?.up != false }
            } else {
                val list = members.flatMap { it.all.value }
                if (!sameList(all.value, list)) all.value = list
                has.value = members.any { it.has.value }
            }
            if (key is Key.State<*> && before?.value != truth?.value) {
                _events.tryEmit(VehicleEvent.Changed(key, truth?.value, truth?.source))
            }
            for (p in parents) p.refresh(now)
        }

        fun refreshFrom(sourceId: String) {
            if (cands.containsKey(sourceId)) refresh(clock())
        }

        /** Re-resolves when values that can age out are held, so a silent source stops being believed. */
        fun expire(now: Long) {
            if (ttlMs != null && cands.isNotEmpty()) refresh(now)
        }

        fun flushDue(now: Long) {
            resolved.flushDue(now)
            for (out in pinned.values) out.flushDue(now)
        }
    }

    private inner class PublisherImpl(override val source: Source, private val state: SourceState) : Publisher {
        override val clock: () -> Long get() = this@VehicleBus.clock
        override val wanted: StateFlow<Set<Key<*>>> get() = this@VehicleBus.wanted

        override fun <T : Any> put(key: Key<T>, value: T?, quality: Quality, atMs: Long, ttlMs: Long?) {
            require(key !is Key.BestOf<*>) { "${key.id} is a BestOf: publish to one of its members" }
            synchronized(lock) { slotOf(key).put(source, value, quality, atMs, ttlMs) }
        }

        override fun up(isUp: Boolean) = synchronized(lock) {
            if (state.up == isUp) return@synchronized
            state.up = isUp
            for (slot in slots.values.toList()) slot.refreshFrom(source.id)
        }
    }

    private companion object {
        const val EXPIRY_TICK_MS = 250L

        /** Best first: not degraded, lowest rank, GOOD before ESTIMATED, newest, then by id so ties are stable. */
        val ORDER: Comparator<Sample<*>> = compareBy<Sample<*>>(
            { it.quality == Quality.DEGRADED }, { it.source.kind.rank }, { it.quality.ordinal }
        ).thenByDescending { it.atMs }.thenBy { it.source.id }

        /** Equal for the bus's purposes: value, source and quality; the timestamp alone is not a change. */
        fun <T : Any> same(a: Sample<T>?, b: Sample<T>?): Boolean =
            a === b || (a != null && b != null && a.value == b.value && a.source == b.source && a.quality == b.quality)

        fun <T : Any> sameList(a: List<Sample<T>>, b: List<Sample<T>>): Boolean =
            a.size == b.size && a.indices.all { same(a[it], b[it]) }
    }
}

/** What a provider holds from [VehicleBus.register]. */
interface Publisher {
    val source: Source
    /** The bus clock, for stamping a value that was measured earlier than it is published. */
    val clock: () -> Long
    /**
     * Publishes [value] for [key]; null withdraws it (unknown is null, never 0).
     * [ttlMs] replaces the Measurement key's lifetime for this value only: for a source that says a
     * value on a change and knows its own cadence (the car app's body, a unit bus). Ignored for other kinds of key.
     */
    fun <T : Any> put(key: Key<T>, value: T?, quality: Quality = Quality.GOOD, atMs: Long = clock(), ttlMs: Long? = null)
    /** Down: every value of this source drops out at once; up brings back what has not expired. */
    fun up(isUp: Boolean)
    /** The keys somebody listens to, for providers that pay per value (poll only these). */
    val wanted: StateFlow<Set<Key<*>>>
}

/** Standing demand from [VehicleBus.hold]; [release] it when done (more than once is harmless). */
class Hold internal constructor(private val onRelease: () -> Unit) {
    private val released = AtomicBoolean(false)
    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
