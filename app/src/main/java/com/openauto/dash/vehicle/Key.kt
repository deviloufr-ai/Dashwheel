package com.openauto.dash.vehicle

/*
 * A key names one element of car data: its value type, unit, kind, domain and
 * criticality, and never where the value comes from. The kind (the subclass)
 * decides what the [VehicleBus] does with the element; see docs/vehicle-bus.md.
 */

/** What an element is about; a label for the UI, it never changes resolution. */
enum class Domain { POWERTRAIN, MOTION, BODY, CLIMATE, TYRES, ENERGY, DRIVER_AIDS, TRIP, IDENTITY, ENVIRONMENT }

/** How much a wrong or missing value matters; a label for strictness, never for resolution. */
enum class Criticality { SAFETY, WARNING, DISPLAY }

sealed class Key<T : Any>(
    val id: String,
    val domain: Domain,
    val criticality: Criticality,
    /** The canonical unit the value is published in, for display; null for non-numbers. */
    val unit: String? = null
) {
    override fun toString() = id

    /**
     * A reading that goes stale: expires [ttlMs] after it was put (2-3 s for the
     * fast ones, up to hours for the slow ones). [maxHz] caps how often the
     * resolved value is emitted (conflated, the latest wins); null = every change.
     */
    class Measurement<T : Any>(
        id: String, domain: Domain, criticality: Criticality,
        val ttlMs: Long, val maxHz: Int? = null, unit: String? = null
    ) : Key<T>(id, domain, criticality, unit)

    /** A discrete state: alive while its source is up; each change is also a [VehicleEvent.Changed]. */
    class State<T : Any>(id: String, domain: Domain, criticality: Criticality, unit: String? = null) :
        Key<T>(id, domain, criticality, unit)

    /** A counter that only grows (odometer): a value below the same source's last one is a glitch and is dropped. */
    class Counter(id: String, domain: Domain, criticality: Criticality, unit: String? = null) :
        Key<Double>(id, domain, criticality, unit)

    /** Identity or static data (versions, VIN): no expiry. */
    class Info<T : Any>(id: String, domain: Domain, criticality: Criticality) : Key<T>(id, domain, criticality)

    /**
     * The first of [members] that has a value. Nobody publishes to it; domain,
     * criticality and unit are the first member's. Subscribing to it is demand
     * for every member.
     */
    class BestOf<T : Any>(id: String, first: Key<T>, vararg fallback: Key<T>) :
        Key<T>(id, first.domain, first.criticality, first.unit) {
        val members: List<Key<T>> = listOf(first) + fallback
    }
}
