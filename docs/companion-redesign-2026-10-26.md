# Companion redesign proposals, 2026-10-26

Three ways to reorganise Dashwheel Companion so every feature has an obvious place. Mockups (phone screens and
a feature map) are on the design canvas: https://claude.ai/artifact/XLzwh2A94MEduMDJU2PjXN

Paths are under `companion/src/main/java/com/openauto/dash/companion/`.

## What is wrong today

- **The Car tab holds eight unrelated cards** (`MainActivity.kt:381-402`): OBD relay, Road watch, clips, car
  picture, Ask about a photo, registration, Rescue, then the paired cars. Rescue, the thing a locked-out driver
  needs fast, is seventh.
- **Home mixes the link, Type on the car and Where's my car** (`MainActivity.kt:355-366`), whether the driver is
  in the car or walking away from it.
- **Setup asks for eight things before any car is paired** (`MainActivity.kt:358-359`, `SetupPanel`), with no
  link to the feature each one serves, and counts them as left to do forever (also noted in
  `docs/ux-review-2026-10-07.md`).
- **Features with no screen**: messages, calls, music covers, agenda, phone position, On my way texts, parking
  reminders, Send to car and second-screen codes all work, but only show up as setup rows or not at all.

## The three directions

| | A · Moments | B · Hub | C · Car and shares |
|---|---|---|---|
| Idea | Home changes with where you are: parked or in the car | One home, every feature a tile in three groups | The car on top; one switch per thing the phone gives the car |
| Tabs | Home · Trips · Road · Tools (Settings behind a gear) | Home · Drives · Settings | Car · Road · Journal · Shares |
| Parked | Map first: Where's my car, From your car, last drive | Same home; tiles show status ("Left 50 min ago") | Car picture, notices, Find it |
| In the car | Big Start Road watch, Type on the car, Ask a photo, Rescue | Full-width Start Road watch | Road tab |
| Permissions | Asked when a feature first needs one, list in Settings | Settings › What the car gets | Shares tab: turning a switch on asks for its permission |
| Strength | Least to scan at any moment | Everything one tap from Home, easy to learn | Makes the hidden features visible and the setup optional |
| Risk | "In the car" depends on the link or the car's Bluetooth | Home grows as features are added | Four tabs, two of them new names |

Shared by all three:

- **Pair first, ask later** (board D1): the first screen is only the pairing, with the launcher's own path
  (Settings › Phone › Pair a phone). No permission is asked before a car is paired.
- **One scan button in the header** for every code (pairing, second screen, dashboards), instead of the scan
  living only in the pairing flow.
- **Road watch, the recorder and clips on one screen**, with the computer fetch beside the clips.
- **Updates move to Settings › About**, with a badge on the tab while one is ready.

## Recommendation

Start from **C's Shares screen** (it fixes the permission and hidden-feature problems on its own and fits any of the
three) with **A's tabs and adaptive Home**. B is the safest fallback if a single home is preferred.
