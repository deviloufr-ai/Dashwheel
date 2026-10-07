# Dashwheel on Google Play

The Play build is the `play` product flavour of the launcher. It is the same
app as the GitHub release, with the differences a Play listing needs:

| | GitHub release (`github` flavour) | Google Play (`play` flavour) |
|---|---|---|
| Updates | In place, from GitHub Releases (`UpdateManager`) | From Google Play; the updater stays idle and `REQUEST_INSTALL_PACKAGES` is removed from the manifest |
| Support | Ko-fi link and QR code in Settings → About | *Dashwheel Pro*, a one-time in-app purchase (Play Billing) |
| Skins | All | Orbit free; Cockpit, Horizon, Tape Deck and Canvas in Pro |
| Widgets | All | 13 in Pro (`Premium.LOCKED_KINDS`): performance timer, G-force, eco score, speed cameras, fuel log, engine temperatures, head unit monitor, CAN monitor, weather alerts, commute, share ETA, voice notes, radio presets |
| PMPatch3 offer (Settings → Advanced, root only) | Downloads and flashes the Magisk module | Not offered: a Play app may not download executable code |
| Companion download (pairing dialog) | The release's `dashwheel-companion.apk` | The companion's Play listing |
| Version name | `1.0.<run>` | `1.0.<run>-play` |

Everything is decided in three places: `app/src/main/java/com/openauto/dash/Premium.kt`
(what Pro holds, the lock badges, the unlock dialog), `app/src/github/.../Edition.kt`
and `app/src/play/.../Edition.kt` + `PlayBilling.kt` (what each store does).

## Building the bundle

Run the **Google Play bundle** workflow (`.github/workflows/play.yml`) by hand.
It uploads `Dashwheel-play-1.0.<run>.aab` (for the Play Console) and the same
build as an APK (for trying it on a unit) as the `dashwheel-play` artifact.
Locally: `./gradlew :app:bundlePlayRelease`.

The version code is the workflow's run number: it grows with every run, which
is all Play asks. It is a separate sequence from the GitHub releases'.

## Play Console checklist

1. **Signing.** Enrol in Play App Signing. To keep one signature for both
   builds (so a unit can switch between the GitHub APK and the Play build
   without uninstalling), upload the existing release key as the app signing
   key and use it as the upload key: the workflow then needs no `PLAY_*`
   secrets. A separate upload key goes in `PLAY_KEYSTORE_BASE64`,
   `PLAY_KEYSTORE_PASSWORD`, `PLAY_KEY_ALIAS`, `PLAY_KEY_PASSWORD`.
2. **The Pro product.** Monetise → Products → In-app products → Create:
   product id `dashwheel_pro` (exactly, see `PlayBilling.PRODUCT_ID`),
   one-time, non-consumable, activated. Without it the unlock dialog says
   Play cannot be reached. Test it with a licence tester account on an
   internal test track before going live.
3. **Target API.** The app targets API 36, as Play requires of new apps
   from 31 August 2026.
4. **Declarations.** The Play Console asks for: the data safety form
   (location, contacts, calendar, microphone, camera; nothing leaves the
   device except what the driver sends to their own phone and to the APIs
   they configured), the Accessibility API declaration
   (`SplitAccessibilityService`: split-screen, the swap button, steering
   wheel keys and typing from the phone; the service's description is
   `split_a11y_description`), and the foreground service / location
   permission prompts where asked.
5. **Listing.** `fastlane/metadata/android/{en-US,fr-FR}` holds the title,
   descriptions and screenshots; the descriptions mention Pro.

## The companion app

The companion (`com.openauto.dash.companion`) is **not** ready for Play as
it is: it reads the call log and sends SMS (`READ_CALL_LOG`, `SEND_SMS`),
which Play only allows to default phone / SMS apps or by exception, and it
updates itself from GitHub (`CompanionUpdate`, `REQUEST_INSTALL_PACKAGES`).
Until it has a Play flavour of its own, the Play build's pairing dialog
links to its Play listing (`Edition.companionUrl`), which must exist for the
link to work; otherwise point it back at the GitHub release.
