# Vehicle bus: design and migration plan

One small, typed bus sits between every source of car data and every consumer. **Sources publish what they know; consumers ask for an element by key, never for a store; the choice between sources happens once, inside the bus.**

This replaces the per-source stores of `oldies`, where ObdData, CarBox, McuReader, CarFeed, UnitSignals, Tyres and others are each read directly by consumers, each with its own fallback chain. See `docs/data-flow-oldies.html` (baseline) and `docs/vehicle-data-refactor-review.md` (why the earlier refactor, b63d1de1, is not carved as is).

The work is done in six commits on `feature/vehicle-bus`, branched from `oldies`. Every commit builds, passes the tests and keeps the app working.

## 1. Core concepts

### Keys
A key names one element of car data. It carries the element's value type, unit, kind, domain and criticality, but **never where the value comes from**.

The **kind** decides what the bus does with the element:

| Kind | Class | Examples | Bus rules |
|---|---|---|---|
| Fast measurement | `Key.Measurement<T>` (`ttlMs` short, `maxHz`) | vehicle speed, GPS speed, rpm, throttle, load, fuel flow, steering | expires after `ttlMs` (2–3 s); emits at most `maxHz` per second (conflated, latest wins) |
| Slow measurement | `Key.Measurement<T>` (`ttlMs` long) | coolant, intake temp, outside temp, fuel level, battery volts, range | expires after `ttlMs` (30–120 s) |
| Discrete state | `Key.State<T>` | doors, reverse, headlights, handbrake, gear, ignition, belt, engine running, climate, radar, tyres | no clock expiry: alive while its source is up; every change of the resolved value is also emitted as a `VehicleEvent.Changed` |
| Counter | `Key.Counter` (Double) | odometer | a value lower than the same source's last value is rejected as a glitch |
| Identity / static | `Key.Info<T>` | unit/canbox version, VIN | no expiry |

`Key.BestOf<T>(id, first, fallback…)` is a composite. It resolves to the first of its member keys that has a value. For example, `K.SPEED` = `BestOf(VEHICLE_SPEED, GPS_SPEED)`. Subscribing to a BestOf counts as demand for all its members.

`Domain` (POWERTRAIN, MOTION, BODY, CLIMATE, TYRES, ENERGY, DRIVER_AIDS, TRIP, IDENTITY, ENVIRONMENT) and `Criticality` (SAFETY, WARNING, DISPLAY) are labels for the UI and for strictness. They never change resolution.

**Different meaning means a different key.** GPS speed is ground speed and the car's speed comes from the wheels, so they are `GPS_SPEED` and `VEHICLE_SPEED`, and they can legitimately disagree. In the same way, the car's outside-temperature sensor and the weather forecast are `OUTSIDE_TEMP` and `FORECAST_TEMP`, combined as `AMBIENT_TEMP = BestOf(OUTSIDE_TEMP, FORECAST_TEMP)`.

### Sources and providers
`Source(id: String, kind: SourceKind, label: String)` is a provider's identity.

`SourceKind` carries a rank; lower ranks win:

| Kind | Rank | Meaning |
|---|---|---|
| `DEMO` | -2 | demo mode, while it is on |
| `LEARNED` | -1 | driver-taught signals (Signal Finder); these beat built-in decoding, as on oldies |
| `CANBOX`, `MCU`, `VEHICLE_APP` | 0 | the car, through the head unit |
| `OBD` | 1 | adapter, Deep OBD |
| `COMPUTED` | 2 | estimates: gear from rpm and speed, range from fuel |
| `GPS` | 3 | |
| `NETWORK` | 4 | weather |

**An estimate is a source, not a kind of data.** `K.GEAR` is the same `State<Gear>` on every car. A computed provider publishes it with `Quality.ESTIMATED` when no better source is live.

`Quality`: GOOD, ESTIMATED, DEGRADED. DEGRADED loses to GOOD and ESTIMATED from any rank.

`Sample<T>(value: T, source: Source, atMs: Long, quality: Quality)`.

### The bus API

```kotlin
class VehicleBus(clock: () -> Long = SystemClock::elapsedRealtime, scope: CoroutineScope) {
    fun register(source: Source): Publisher
    fun <T : Any> sample(key: Key<T>): StateFlow<Sample<T>?>      // resolved: value + where from + quality
    fun <T : Any> value(key: Key<T>, from: SourceKind? = null): Flow<T?>   // what consumers use; from = pinned read
    fun <T : Any> now(key: Key<T>): T?                              // snapshot for non-Compose code
    fun <T : Any> candidates(key: Key<T>): StateFlow<List<Sample<T>>>     // every source, for diagnostics
    fun has(key: Key<*>): StateFlow<Boolean>                         // capability: some up source published it
    fun choose(key: Key<*>, sourceId: String?)                       // the driver's override (null = automatic)
    val events: SharedFlow<VehicleEvent>                             // state changes now; keys and DTCs later
    val wanted: StateFlow<Set<Key<*>>>                               // keys with live subscribers
}
interface Publisher {
    val source: Source
    fun <T : Any> put(key: Key<T>, value: T?, quality: Quality = Quality.GOOD, atMs: Long = clock(), ttlMs: Long? = null)   // null = withdraw; ttlMs = this value's own lifetime
    fun up(isUp: Boolean)            // down: every value of this source drops out at once
    val wanted: StateFlow<Set<Key<*>>>
}
object Vehicle { val bus: VehicleBus }    // the app-wide instance
@Composable fun <T : Any> rememberVehicle(key: Key<T>): T?   // = Vehicle.bus.value(key).collectAsState(null)
```

### Resolution: the only place a source is chosen
Resolution runs per key on `put`, `up`, `choose` and expiry:

1. Drop candidates whose source is down, whose value is expired (Measurement `ttlMs`), or that were withdrawn.
2. If the driver chose a source for this key and it has a live candidate, that candidate wins.
3. Otherwise pick the best quality first (GOOD and ESTIMATED beat DEGRADED; GOOD beats ESTIMATED at the same rank), then the lowest `SourceKind.rank`, then the newest `atMs`.

The resolved `StateFlow` emits **only when the value, the source or the quality changes**, never for a new timestamp alone.

**Cost.** Resolution is O(candidates of that key) and safe to call from any thread: guard it with a lock, never a global state copy. One expiry job checks Measurement keys about every 250 ms, using the injected clock so tests can drive it.

**Pinned reads** (`value(key, from = X)`) bypass step 2 and step 3 and return that source kind's live candidate or null. They keep expiry, and they still count as demand.

### Demand
`wanted` is the set of keys whose resolved flow, pinned flow or BestOf parent has subscribers. It comes from `MutableStateFlow.subscriptionCount`, with no coordinator.

Providers that pay per value (OBD) use it to poll only wanted keys. A key that is also read by non-Compose code via `now()` must be marked as standing demand by that code, through `Vehicle.bus.hold(key)`, a returned handle that is released when done.

### What stays outside the value bus
- **Commands:** read/clear DTCs, the PID explorer, Deep OBD queries, MCU/canbox queries. These remain direct calls to the provider that can do them, for example `ObdBluetoothManager.readDtcs()`.
- **Raw values:** the Signal Finder's `SignalSources`, and CAN frames for the CAN monitor.
- **OBD-specific consumers:** the AI mechanic's DTC scan, the PID explorer, MyReadings, the OBD connection UI. They may keep reading `ObdBluetoothManager` directly, because the OBD identity is the point of the feature.
- **Navigation-side data** (`LocationFeed.location`, `trip`, `heading`, weather fetches) stays where it is. **Only speed** moves to the bus, as `GPS_SPEED`.

### Rules for every step
- **Every commit builds and passes** `.\gradlew.bat :unitproto:test :app:testGithubDebugUnitTest :app:assembleGithubDebug`.
- **Behaviour the driver sees stays the same unless the step says otherwise.** Each intended change goes in the commit message under "Behaviour changes".
- **Unknown is `null`, never 0, in new code.** Where an old consumer still needs an Int, it maps null to its old default explicitly, at the edge.
- **Old stores keep working until step 6.** Providers publish *in addition to* writing the old store, until the old store's last reader is gone.
- **Match the surrounding code:** package `com.openauto.dash.vehicle` for the bus; naming and comment density like the rest of the app; commit messages in the repo's voice (a headline plus a body), ending with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

## Decisions

- **Speed correction (step 2).** `VEHICLE_SPEED` is the car's speed *after* the driver's correction (Settings, Car): every car-speed provider applies `SpeedCorrection.corrected` before it publishes (OBD at its poll, the QF car app and the unit bus when they put). `GPS_SPEED` is raw. Display goes through one helper, `displaySpeedKmh(sample)` / `rememberDisplaySpeedKmh()` in `vehicle/Speed.kt`: it returns `SPEED`'s value and applies `SpeedCorrection.correctedGps` only when the resolved sample's source kind is GPS. The bar speedo (`rememberSpeedKmh`) and the second screen (`ClusterFeed`) use it; everything else reads the raw keys, as they always did.
- **Slow senders carry their own lifetime (step 3).** The QF car app and a unit bus say their speed on a change, while `VEHICLE_SPEED` expires in 3 s. A provider that knows its own cadence puts with `ttlMs` (`Publisher.put(key, value, ttlMs = ...)`): that value lives that long instead of the key's, for Measurement keys only. The car app puts with 6 s (as `CarBox.freshBody()`), the unit bus with 10 s; a silent source drops out by expiry, with no republish loop (`SteadySpeed` is gone).
- **OBD publishes what it answered (step 3).** `ObdData` keeps 0 as its default for the readers that rely on it; the bus never sees that 0. The `obd` provider (`ObdGaugePublisher`) puts a key only when the adapter answered that PID in the poll just done, and nothing before (unknown is null). A key that is not asked again ages out by its ttl. Throttle, load and fuel flow are asked every 2nd-4th poll, so they carry a 10 s `ttlMs`; Deep OBD says a page now and then, so its keys live at least 10 s. The 0 of speed, revs, throttle and load after `engineStopped()` is published: the car is stopped. When the link goes, everything the adapter put is withdrawn.
- **Demand-driven OBD poll (step 3).** `obdPollPlan(wanted, tick)` says what poll `tick` asks for, from `Vehicle.bus.wanted`: speed every poll (always: keep-alive, and how "the engine went quiet" is read), then revs every poll, throttle and load every 4th poll on alternate polls, fuel flow every 2nd, and coolant, intake, fuel level and volts one per poll in turn; the first poll asks for every wanted key. Nothing wanted: speed alone. Floor: `VehicleMonitor.POLL_FLOOR` (speed, revs, coolant, intake, load, fuel level, volts) is held for as long as the monitor polls, for what runs off it (the AI mechanic's live watch and scan, car care's rules and fuel range, the CarFacts / CarQuestion / briefing snapshots); the alerts' coolant is a collector and the battery judge holds revs and volts. The LPG tank holds coolant and fuel flow while it is on.
- **Volts are plain resolution (step 3).** `UnitSignals` publishes `BATTERY_VOLTS` as `qf.mcu` (MCU) from the MCU's message (believed 10 min) and, while the key is wanted and the MCU has not spoken, from the firmware's `sys.qf.update_battery_power`. The OBD figure is a source like any other: the engine computer's `0142` is GOOD, the adapter's own `ATRV` (clone adapters misread it) is `DEGRADED`. So the unit's volts win whenever present, not only when they disagree by more than 1 V, and an ATRV loses to everything. "Trusted volts" (`BatteryJudge`, `LiveWatch`) is `quality != DEGRADED`.
- **Gauge readers (step 3).** A leaf composable reads the keys it shows (`rememberGauges(K.RPM, ...)`, `rememberGaugeInt`, `rememberReadings`, which maps null to the old 0 at the edge for tiles drawn against the old record); the subscription is the demand. Non-Compose readers that use `now()` take a `hold` for as long as they run. `ObdData` stays for the OBD-specific readers (AI mechanic's scan, PID explorer, MyReadings, Deep OBD) and as the input of `CareRules` / `LiveWatch`, which the monitor builds from the bus (`Gauges.toObdData()`).

## 2. The key catalogue (step 1 defines all of it)

| Key | Type | Kind / ttl | Domain | Crit. |
|---|---|---|---|---|
| `VEHICLE_SPEED` | Double km/h | Measurement 3 s | MOTION | SAFETY |
| `GPS_SPEED` | Double km/h | Measurement 5 s | MOTION | DISPLAY |
| `SPEED` | BestOf(VEHICLE_SPEED, GPS_SPEED) | | MOTION | SAFETY |
| `RPM` | Double | Measurement 3 s | POWERTRAIN | DISPLAY |
| `THROTTLE` | Double % | Measurement 3 s | POWERTRAIN | DISPLAY |
| `ENGINE_LOAD` | Double % | Measurement 3 s | POWERTRAIN | DISPLAY |
| `COOLANT_TEMP` | Double °C | Measurement 60 s | POWERTRAIN | WARNING |
| `INTAKE_TEMP` | Double °C | Measurement 60 s | POWERTRAIN | DISPLAY |
| `FUEL_FLOW` | Double L/h | Measurement 5 s | ENERGY | DISPLAY |
| `BATTERY_VOLTS` | Double V | Measurement 60 s | ENERGY | WARNING |
| `FUEL_LEVEL` | Double % | Measurement 120 s | ENERGY | WARNING |
| `RANGE` | Double km | Measurement 120 s | ENERGY | DISPLAY |
| `OUTSIDE_TEMP` | Double °C (car sensor) | Measurement 2 h | ENVIRONMENT | DISPLAY |
| `FORECAST_TEMP` | Double °C | Measurement 3 h | ENVIRONMENT | DISPLAY |
| `AMBIENT_TEMP` | BestOf(OUTSIDE_TEMP, FORECAST_TEMP) | | ENVIRONMENT | DISPLAY |
| `STEERING_ANGLE` | Double ° | Measurement 3 s | MOTION | DISPLAY |
| `ENGINE_RUNNING` | Boolean | State | POWERTRAIN | DISPLAY |
| `GEAR` | `Gear` (sealed: Park, Reverse, Neutral, Drive, Forward(n)) | State | POWERTRAIN | DISPLAY |
| `IGNITION` | Boolean | State | POWERTRAIN | WARNING |
| `DOORS` | `McuReader.DoorState` (the existing type) | State | BODY | WARNING |
| `REVERSE` | Boolean | State | BODY | SAFETY |
| `HEADLIGHTS` | Boolean | State | BODY | DISPLAY |
| `HANDBRAKE` | Boolean | State | BODY | DISPLAY |
| `DRIVER_BELT` | Boolean (true = unfastened) | State | BODY | WARNING |
| `CLIMATE` | the existing CarBox climate type | State | CLIMATE | DISPLAY |
| `RADAR` | the existing CarBox radar type | State | DRIVER_AIDS | SAFETY |
| `TYRES` | the existing `Tyres` map type | State | TYRES | WARNING |
| `ODOMETER` | Double km | Counter | TRIP | DISPLAY |

The table's TTLs reflect how these values arrive today. For example, the outside temperature is kept for 2 h on oldies. Reuse the existing app types for structured values during the migration; the step that introduces a key may refine its type if it must.

## 3. The six steps

1. **Core.**
   - Add `com.openauto.dash.vehicle` with the keys, sources, samples, `VehicleBus`, `Publisher`, `Vehicle`, `rememberVehicle`, `Gear`, `VehicleEvent` and the full catalogue.
   - Unit-test resolution: rank, quality, driver choice, expiry, withdraw, down, BestOf, pinned reads, emit-on-change-only, counter glitch, the `wanted` set and threading.
   - Nothing in the app uses it yet.
2. **Speed.**
   - Providers publish `VEHICLE_SPEED` (OBD, QF car app, CarFeed/unit bus) and `GPS_SPEED` (LocationFeed).
   - All speed consumers read `SPEED`, or a specific key where the meaning requires it: GPS-only consumers read `GPS_SPEED`; car-only consumers (gear estimator, belt alert) read `VEHICLE_SPEED`. That replaces the ten chains.
   - Speed correction lives in one documented place, and the numbers the driver sees stay the same.
3. **Gauges.**
   - The OBD provider publishes rpm, throttle, load, coolant, intake, fuel flow, volts and fuel level, polling only wanted keys (speed and rpm stay cheap; the rest rotate as before but only when wanted).
   - Tiles, skins, the bar, alerts, BatteryWatch and CarCare read keys instead of `ObdData`.
   - The volts override (the unit's supply volts vs OBD) becomes plain resolution: UnitSignals publishes `BATTERY_VOLTS` as an MCU source.
4. **Body, fuel, tyres, environment.**
   - Doors, reverse, headlights, handbrake, belt, steering, climate, radar, tyres, ignition, fuel level and range from the car, odometer, outside and forecast temperature.
   - Providers: QF car app (CarBox), MCU (McuReader, UnitSignals), TPMS (Tyres), learned signals (`LEARNED`), CarPower, WeatherRepo (`FORECAST_TEMP`; WeatherRepo stops overwriting `tempC` with the car's value).
   - Overlays, door tiles, DayNight, fuel and range, the bar's OUTSIDE (`AMBIENT_TEMP`), servicing and tyres read keys.
5. **Adapters publish directly; estimates become providers.**
   - Unit adapters get a `Publisher` instead of forwarding through `CarFeed` into old stores: `VehicleBusSource.start(context, publisher)`, with FYT/MTC putting keys.
   - The QF receivers publish straight from their decoders.
   - Computed providers: gear (from rpm and speed, replacing GearEstimator's role as a source), range (from fuel and consumption), engine running (rpm > threshold). Each subscribes to its inputs only while its output is wanted and no better source is live.
6. **Delete the old.**
   - Remove what no longer has readers: the old StateFlows on CarFeed, CarBox, McuReader, UnitSignals and ObdData consumers outside the OBD-specific ones, the per-consumer speed helpers, and DemoMode's writes into stores. DemoMode becomes a `DEMO` provider.
   - The vehicle `Cap`s (DOORS, REVERSE, STEERING, RADAR, CLIMATE, BODY_TRIP, SPEED_RPM, VOLTS, HEADLIGHTS, OUTSIDE_TEMP, TYRES) become `has(key)`. Platform caps stay.
   - Update `docs/` to describe the result.
