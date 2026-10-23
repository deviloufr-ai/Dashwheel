# Review of b63d1de1 ("Vehicle data: one repository…")

*Review written on 2026-10-23, read-only. Line numbers refer to b63d1de1. The baseline is `oldies` (1251898a); see `data-flow-oldies.html`.*

## Verdict
The core idea is right and it mostly works:
- Element constants (partly typed): `ElementKey` is a bare string id, and the value type and unit live in a runtime descriptor.
- Readings that carry their source, time and quality.
- "Withdraw" instead of 0 for an unknown value.
- One resolver with user choice, overrides and default ranking (`VehicleDataState.resolve`).
- Demand-driven OBD polling that really works, not a stub.

But the core is buried under a framework sized for a multi-vendor platform:
- per-consumer binding modes, consumer policies and feature requirements;
- a third capability system and a 5-state capability lattice;
- catalogue conflict tracking and a dead provider interface;
- a full Sources UI in 11 locales.

That comes to 1,619 lines in `vehicle/` plus about 3,300 lines of adapter and consumer glue. It is also built on one big immutable whole-repository `StateFlow` that every consumer maps on every publish.

The migration is mostly done, but not clean:
- Old side stores remain: `UnitSignals.supplyVolts`, `CarPower.ignition`, `CarBox.body`, `LocationFeed.location`.
- There is a second resolver (`lastKnown`).
- Speed has 8 entry points, with different null/0 and correction rules.
- Demo mode still writes through the old stores into the real providers.
- The old `Cap` system still has 330 uses.

The commit also sits on 14 commits that are not on `oldies`, and cherry-picking it gives 12 conflicting files.

**Recommendation: option 2.** Port a slimmed core onto `oldies` by hand rather than cherry-picking.

## Yardstick
| # | Rating | Evidence |
|---|---|---|
| 1 Typed keys + units | **partial** | `ElementKey(val id: String)` has no value type (vehicle/Elements.kt:11). The unit is in a runtime descriptor (Elements.kt:60-95). `publish(key, value: Any)` (VehicleDataRepository.kt:545-552) is only shape-checked at runtime (`fits`, :504-515). Consumers cast: `?.value as? Climate` (CarState.kt:80,86). Raw discovery does stay separate, as strings (SignalSources). |
| 2 Normalise at edge | **good / partial** | A reading has provider, time, quality and staleness (Readings.kt:22-32). Unknown means withdraw (HeadUnitData.kt:150-152; the XyautoAdapter heartbeat). 0-as-unknown comes back in `Telemetry.asObdData()` (Powertrain.kt:112-123), `VehicleData.speedKmh()` (VehicleData.kt:112-120) and `movingSpeedKmh` (Powertrain.kt:214-217). |
| 3 Arbitration once | **partial, over-built** | The core is good: VehicleDataRepository.kt:363-379, with overrides at VehicleData.kt:137-138 and CarState.kt:71. But arbitration is repeated in several places: `lastKnown` (CarState.kt:199-207) is another resolver that accepts stale readings; 3 `ConsumerPolicy` classes (ConsumerPolicies.kt); `SourceBinding` per consumer (ConsumerBinding.kt:105-147); and the voltage rule appears both in the poll (ObdBluetoothManager.kt:913-925) and in `VoltageDisagreementPolicy`. |
| 4 Staleness per element | **partial** | `staleAfterMillis` is set by the publisher, not by the element (Readings.kt:29-31); `expireWhenDue` is at :483-489. Bus/canbox readings have none (HeadUnitData.kt:137-152), and the bus provider is always UP. OBD keeps readings for 60 s (ObdProvider.kt:124, VehicleData.kt:61). |
| 5 States vs events | **missing** | There is no event channel. The DTC count and MIL are modelled as states (Elements.kt:187-188); key presses stay in the old code. |
| 6 Demand-driven polling | **good, over-built** | `ObdPolling.due` (ObdProvider.kt:54-74) and `pollLocked` read `VehicleData.obd.demand` (ObdBluetoothManager.kt:864). The resolution-aware `DemandCoordinator.needs` (DemandCoordinator.kt:56-65) is clever but heavy. `CarState.follow` takes demand that is never released (CarState.kt:60-63), which contradicts the doc's "no standing demand". |
| 7 Raw vs semantic | **partial** | Learned signals are definitions on the provider that owns them (HeadUnitData.kt:356-444); My Readings registers its keys into the catalogue (MyReadingDefinitions.kt:132). But decoders are still not providers: data goes XyautoBus → `CarSink` (UnitAdapter.kt) → `CarFeed` → `HeadUnitData.bus*` → `ProviderHandle`. |
| 8 Capabilities from registry | **missing (worse)** | It adds a per-provider `CapabilityState` (Providers.kt:101-114) and `ServiceCapabilities`/`CapabilityKey` (FeatureRequirements.kt). `Cap.*` still has 330 uses in 82 files, `markUsed` is still called (CarFeed.kt:45+), and the `available` flags remain. That makes three systems. |
| 9 Demo as provider | **missing** | `DemoMode` still calls `ObdBluetoothManager.demoWrite`, which publishes as the OBD provider with method "demo" (ObdBluetoothManager.kt:1258-1260). `CarBox.demoWrite` publishes as `qf.car_body` even on XYAuto and FYT units (DemoMode.kt:457, 675). |
| 10 Commands separate | **partial** | DTC read/clear availability is a service capability (ObdProvider.kt:207-216; FaultCodesTile.kt:102-103,149-150). There is no command API: the DTC scan and canbox queries stay ad hoc. |

**Compose performance: poor shape.**
- **Every publish rebuilds the whole state.** It copies nested maps in one `VehicleDataState` (VehicleDataRepository.kt:435-445). It also runs `demand.refresh()` synchronously on the publisher's thread, which costs O(providers × demands × candidate sort) per publish (DemandCoordinator.kt:77-86). Broadcast receivers run on the main thread. Every subscriber's `state.map{…}` runs on every publish too.
- **Identical values still count as changes.** A republished identical value gets a new `observedAtMillis`, because the default is `now()` (:548). So every canbox frame changes the state, re-triggers `ComputedProvider.refresh` (VehicleData.kt:72) and causes a second emission.
- **Raw flows emit on every frame.** The `bound`/`resolved` flows fire each time because `VehicleReading` equality includes the time. This hits LiveValuesTile.kt:115 and the gear `REVERSE` flow.
- **Some tiles read the whole state.** `rememberExtras` reads all of `state` in Compose (Powertrain.kt:298-299), so the CarCare and WidgetFace tiles recompose on every publish.
- **Fast elements are not throttled.** Only `Telemetry` deduplicates, and only after mapping to ints.

**Testability: good.** Resolution is a pure function of the state, and there are about 175 tests (1,053 lines under `test/vehicle`). Consumers still go through global singletons (`VehicleData`, `CarState`, `Powertrain`).

## Ceremony
**(a) A new element.** Example: be7a38b0 adds `instrument_dimmer` and the lever-gear codes, touching 21 files:
- `Elements.kt`: the constant and descriptor (2 lines).
- `ReadingFormat.kt`: the label map.
- `strings_sources.xml` in each of the 11 locales. The label is now defined three times: descriptor, map and resource.
- `Models.kt` (GearCodes).
- The decoder in `XyautoProtocol.kt`, plus its test.
- The inventory doc.

**(b) New kinds of value from an existing source.** `CarSink` had to grow `flag()` and `code()` (UnitAdapter.kt), with `CarFeed` overrides and `HeadUnitData.busFlag`/`busCode`. That is 3 framework files just to pass a Boolean or a String through. Only about 4 of the 21 files are actual decoding.

**The payoff is missing.** The canbox gear and `ENGINE_RUNNING` reach nothing on the dashboard:
- `rememberGear` still estimates the gear from rpm and speed (GearEstimator.kt:128-160).
- `Telemetry.engineRunning` is still rpm-based.
- `ComputedReadings.selectedGear`/`engineRunning` duplicate both (VehicleFeeds.kt:263-280).
- Only Live Values and the Sources page show the new elements. Every purpose-built tile still needs its own consumer edit: a `Gauge` entry, a `Telemetry` field and a `telemetryOf` line, or a `CarState.follow` call.

**Outside temperature for XYAuto** already flows `s.outsideTempC` → `CarFeed` → `HeadUnitData.busNumber(OUTSIDE_TEMPERATURE)` → `CarState.outsideTempC` (via `lastKnown`). That is 4 hops for one Double.

**A genuinely new source** needs a `ProviderId`, a descriptor and staleness constants (following the VehicleData.kt:43-61 pattern), then `ProviderHandle.publish` calls. That would be cheap if it bypassed `CarSink`. But all unit families share one "bus" provider, chosen by family (VehicleData.kt:123-146).

## Over-engineering
**These earn their keep:**
- `VehicleReading` (Readings.kt) and the `ProviderId`/`ProviderKind` ranking (Providers.kt:67-81).
- The element constants with their canonical units.
- `VehicleDataState.resolve`/`candidates`/`order`, about 60 lines.
- `ObdPolling`, which turns demand into polls (ObdProvider.kt:28-91).
- The provider-side publishers: `publishCarBody`, `doorSnapshot`, `publishPsa`, `DoorComposer`.
- `ObdReadings`, which withdraws values on disconnect.

**These don't, for a few dozen elements:**
- `ConsumerBinding`/`SourceBinding`/`bound()`: four binding modes, and the only real override is the compass.
- `ConsumerPolicies` as a plugin interface. `MovementPolicy` should be one function, `KindOrderPolicy` is used once, and `VoltageDisagreementPolicy` duplicates the poll's own logic.
- `FeatureRequirements`/`ServiceCapabilities`/`CapabilityKey`. They are used for two booleans in FaultCodesTile. `LocationConsumer.requirements` (LocationData.kt:219-266) and `SourceBinding.requirement()` are referenced only by tests and comments.
- `DemandCoordinator`'s resolution-aware demand and `ignoreChoice`. A per-key subscription count would do.
- The 6 states of `CapabilityState` (Defined/Supported/Observed/Live/Unavailable/Error).
- `SourceOverview`, `SourcePersistence`, `SourcesSheet` and `SourceDiagnosticsSheet`, plus 168 strings × 11 locales. That is a full source-picker UI before most users have a second real source for most elements.
- `ElementCatalogue`'s runtime registration and conflict tracking, which only My Readings needs.
- The `VehicleDataProvider` interface and `attach()`: dead, with no production implementation.
- The generic `GroupedSnapshot` + `PositionAxis` + string member names; typed `Doors`/`Tyres` models would be simpler.
- `ComputedProvider`: a useful idea, but it re-runs every definition on every state change. Defer it.

## Migration findings
**What moved.** Consumers have mostly moved off `ObdData` and the old flows. `ObdBluetoothManager.data`, `CarFeed.speedKmh`/`rpm` and `UnitSignals.headlights`/`outsideTemp`/`handbrake` are removed, and `fedByBus`/`busWrite` are gone. Good.

**Parallel paths remain:**
- `UnitSignals.volts`/`supplyVolts()`, still used by CarFacts and the OBD poll, alongside `VEHICLE_VOLTAGE`.
- `CarPower.ignition` (21 uses), while `Elements.IGNITION` is published by two providers and read by nothing.
- `CarBox.body` (SignalFinderSheet, DemoMode).
- `McuReader` fuel learning (by design).
- `LocationFeed.location` (17 uses) beside `Elements.POSITION`, partly by design.
- `Tyres`, kept as a router (`Tyres.busWrite` → `HeadUnitData.busTyres`).
- The `Cap` capability system, untouched.

**Speed is better but not unified.** There are 8 entry points:
- `VehicleData.speedKmh()`: 0 when unknown, and uncorrected for the bus and GPS.
- `CarState.speedKmhNow()`, `speedKmhOrNull()` and `carSaysSpeed()`.
- `Powertrain.movingSpeedKmh()` and `movingSpeedNow()` (the maximum of any credible source), plus `timerSpeedKmh()`.
- `Telemetry.speedKmh`: the only path with the `displaySpeedKmh` correction (Powertrain.kt:132-139).
- `carSpeedKmh()` (DriveLock.kt:122), used by SpeedLimit, CarCare, Moments and CanvasTabs.

As a result, the SpeedLimit warning compares an uncorrected bus or GPS speed while the tiles show a corrected one.

**Unrelated changes mixed into the commit:**
- Map theming in MapLibrePanel, RouteLine and OsmMapTile (`DashColors`, an accent-coloured route line, `RouteLine.tint`). This has already been carved into `feature/gles2`.
- `PageMotion`, which suppresses a tap just after a swipe: PendingAction.kt, AutomotiveDashboard.kt:1544-1548 and PendingActionTest.
- New features bundled in: the `LIVE_VALUES` tile (LiveValuesTile.kt, DashboardGrid, WidgetCatalog, TileSettings) and the compass-source sheet (strings_location.xml × 11).
- A behaviour change in PhoneGps: fixes are now published even when the unit refuses them (`_refused`).
- An 829-line `docs/vehicle-data-flow.html`.
- A deleted `SignalProbeTest` door-merge test; the logic moved to `DoorComposer`.

**Base mismatch.** The commit's parent is 14 commits past `oldies`: the license changes, the API 27 back-port, XYAuto, MapLibre 10, TLS 1.3 and the map UI. `git diff oldies b63d1de1^` covers 131 files and +4,016 lines. Cherry-picking b63d1de1 onto `oldies` conflicts in 12 files, with modify/delete conflicts for `XyautoAdapter`, `XyLocationEngine`, `OsmMapTile` and `RouteLine`.

## Risks (from reading)
1. **CPU and main-thread cost per canbox frame** (see "Compose performance"). XYAuto broadcasts arrive in `onReceive` on the main thread, and each one triggers a synchronous whole-state copy, a demand refresh and a computed refresh. That is a real cost on a 4-core AC8227L.
2. **Bus values never expire, and the bus provider is always UP** (HeadUnitData.kt:137-152). If the box goes silent without first marking its data invalid, rpm, speed and lights stick. XYAuto has heartbeats; FYT and MTC rely on sending only changes.
3. **OBD speed can outrank live canbox speed for up to 60 s.** This happens when OBD speed stops arriving but is not withdrawn, for example during a DTC scan (`OBD_FRESH_MS` + `keepDashboardSpeedOrder`).
4. **XYAuto publishes `FUEL_LEVEL` from two frames under the same provider and key.** ENGINE gives a %, and FUEL gives litres ÷ the profile's tank size. The value flip-flops if the profile's tank size is wrong. This was carried over from the old code.
5. **`CarState` holds demand forever** for fuel, range, status keys and more. Whenever no canbox supplies them, OBD polls them for the whole session.
6. **Two engine-running definitions and two gear computations disagree** (computed vs Telemetry/GearEstimator). Canbox gear and engine-running never reach the dashboard (see "Ceremony").
7. **Demo mode writes into real provider ids.** The Sources page and the source choices treat demo data as the car's, and demo shows `qf.car_body` on non-QF units.
8. **Voltage is judged twice.** The poll substitutes the unit's volts in its own bookkeeping and marks the OBD reading DEGRADED, then `VoltageDisagreementPolicy` decides again.

## Recommendation: (2) a slimmed core, ported by hand onto a fresh branch from `oldies`
Don't cherry-pick: both the base and the shape (one whole-state flow) are wrong.

**Lift these pieces:**
- The element list and canonical units, converted to typed keys.
- The `VehicleReading` semantics.
- The ~60 lines of resolve/order logic, with their tests.
- `ObdPolling` + `ObdReadings`.
- The provider-side publish helpers (`publishCarBody`, the door/PSA snapshots, `DoorComposer`).
- The speed, voltage and movement decisions, as plain functions.
- The inventory doc.

**Drop or defer:** `ConsumerBinding`, the `ConsumerPolicy` interface, `FeatureRequirements`/`ServiceCapabilities`, `SourceOverview`/`SourcePersistence`/`SourcesSheet`, catalogue registration and conflicts, `VehicleDataProvider`/`attach`, the 6-state `CapabilityState`, `GroupedSnapshot`, `DemandCoordinator`, and `ComputedProvider` (add it back later, only if My Readings needs it).

**Rough size:**
- The core: about 500 lines, plus about 400 lines of tests.
- The speed migration: about 15 files.
- The powertrain gauges: about 40 skin and tile files, mostly mechanical.
- Body, fuel and tyres: about 20 files.
- Deleting the old stores and `Cap` comes last.

In total about 4–6k changed lines over 4–5 reviewable branches, instead of 12.8k in one commit.

```kotlin
// Element key: value type and canonical unit in the type; string ids only for persistence/raw discovery.
class Key<T : Any>(val id: String, val unit: Unit?, val ttlMs: Long?, val order: List<SourceKind>? = null)
object K {
  val SPEED = Key<Double>("speed", Unit.KMH, 3_000, order = listOf(SourceKind.OBD))
  val RPM = Key<Double>("rpm", Unit.RPM, 3_000)
  val COOLANT_TEMP = Key<Double>("coolant_temp", Unit.CELSIUS, 30_000)
  val OUTSIDE_TEMP = Key<Double>("outside_temp", Unit.CELSIUS, 120_000)
  val GEAR = Key<Gear>("gear", null, 5_000)              // sealed Gear: P/R/N/D/Forward(n)
  val DOORS = Key<Doors>("doors", null, null)
}
enum class SourceKind(val rank: Int) { CANBOX(0), MCU(0), VEHICLE_APP(0), OBD(1), COMPUTED(2), GPS(3), DEMO(-1) }
data class Source(val id: String, val kind: SourceKind, val label: String)
data class Sample<T : Any>(val value: T, val source: Source, val atMs: Long, val quality: Quality = Quality.GOOD)

interface Publisher {                                     // handed to a provider at register()
  fun <T : Any> put(key: Key<T>, value: T?, atMs: Long = now(), ttlMs: Long? = key.ttlMs)  // null = withdraw
  val wanted: StateFlow<Set<Key<*>>>                      // keys with live subscribers (OBD polls only these)
  fun up(isUp: Boolean)                                   // down = all its samples ignored
}
object VehicleBus {
  fun register(source: Source): Publisher
  fun <T : Any> sample(key: Key<T>): StateFlow<Sample<T>?> // one StateFlow per key; emits only on value/source change
  fun <T : Any> value(key: Key<T>): Flow<T?>               // sample().map { it?.value }.distinctUntilChanged()
  fun <T : Any> candidates(key: Key<T>): StateFlow<List<Sample<T>>>  // diagnostics + the few policy consumers
  fun choose(key: Key<*>, source: Source?)                 // user override, persisted by the caller
  fun has(key: Key<*>): StateFlow<Boolean>                 // capability = some up source has published it
}
// Arbitration lives only inside VehicleBus, per key, on put/expiry:
// drop expired/BAD/down → user choice → key.order → kind.rank. DEMO (rank -1) outranks everything while it is up.
// Demand = subscriptionCount of the per-key StateFlow (no coordinator).
// Commands stay outside the bus: ObdService.readDtcs()/clearDtcs(), CanboxCommands.query(...); DTCs/key presses go on a SharedFlow<VehicleEvent>.
```

**How XYAuto plugs in with this API:**
- `XyautoBus.start` registers directly: `VehicleBus.register(Source("xyauto.canbox", CANBOX, "XYAuto CAN box"))`. Data no longer hops through `CarSink`.
- The decoder calls `pub.put(K.GEAR, gearOf(lever))` and `pub.put(K.OUTSIDE_TEMP, c)`.
- When the heartbeat marks a value invalid, it calls `pub.put(key, null)`. When the box goes silent, it calls `pub.up(false)`.
- A new element costs one `Key` line plus a label string. A new source costs one `register` call plus its `put`s.
- `CarSink` shrinks to platform-only duties: keys, the raw Signal Finder feed and ignition.
- The gear tile reads `VehicleBus.value(K.GEAR)`. The gear estimator becomes a `COMPUTED` source, so the canbox's lever position wins automatically.
