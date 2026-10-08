# Play Console Data safety form: answers

Filled in from the code as of 2026-10-08 (see `docs/play/PRIVACY_POLICY.md` for the endpoints). One form per app. "Collected" in Play's sense means data sent off the device, even transiently, to the developer or a third party; data that stays on the device is not collected. Dashwheel and the Companion collect nothing for the developer, but some features send data to third-party services, which Play counts as collection.

## Dashwheel (com.openauto.dash)

### Overview questions

- Does your app collect or share any of the required user data types? **Yes** (location and, with a user-supplied key, app-provided content, are sent to third-party services).
- Is all of the user data collected by your app encrypted in transit? **Yes** (every endpoint is https or wss).
- Do you provide a way for users to request that their data is deleted? **Yes**: nothing is retained by the developer; data on the device is removed by clearing the app's storage or uninstalling. (Pick "Yes" and name the privacy policy; Play accepts "no data is retained" as the mechanism.)
- Privacy policy URL: the raw or rendered link to `docs/play/PRIVACY_POLICY.md` on GitHub (for example `https://github.com/deviloufr-ai/Dashwheel/blob/main/docs/play/PRIVACY_POLICY.md`).

### Data types

| Data type | Collected / shared | Required or optional | Purpose | Notes |
|---|---|---|---|---|
| Location: approximate and precise | Shared with third parties (CARTO tiles, Valhalla, Nominatim, Open-Meteo, Overpass, data.economie.gouv.fr) | Optional: only when the map, weather, fuel prices, speed limits or routes are used | App functionality | Not collected by the developer; not linked to identity; not used for ads |
| App activity: other user-generated content (search text, car fault codes, car readings, questions typed or spoken) | Shared with Google Gemini (only when the user adds their own Gemini API key), search text with Nominatim | Optional | App functionality | Ephemeral processing by the service; the developer never sees it |
| Photos | Shared with Google Gemini (the "my car" picture, only when the user sends one) | Optional | App functionality | Taken by the user on purpose; not stored by the developer |
| Audio: voice or sound recordings | Shared with Google Gemini (Gemini Live, only while the panel is open, user-supplied key) | Optional | App functionality | Streamed, not stored by the developer |
| Personal info, financial info, health, messages, contacts, calendar, files, device IDs | **Not collected and not shared** | | | Notifications, contacts and calendar are read on the device only |

Declare for every row above: not processed ephemerally only where a service may keep logs (Gemini keeps data per Google's terms), "not required", "not linked to the user", "no data collected for advertising or analytics".

### Security practices

- Data encrypted in transit: Yes.
- Users can request deletion: Yes (nothing retained; uninstall removes device data).
- Committed to the Play Families policy: No (not a children's app).
- Independent security review: No.

## Dashwheel Companion (com.openauto.dash.companion)

### Overview questions

- Does your app collect or share any of the required user data types? **No**. Everything the Companion reads (notifications, calls, contacts, calendar, location, texts) goes to the paired head unit over the phone's own hotspot and never to a server. Play's definition of collection excludes device-to-device transfers that the user controls, and the GitHub updater does not exist in the Play edition. If the reviewer disagrees, declare the rows below as "collected, not shared, app functionality, user choice, not linked to identity".
- Encrypted in transit: Yes (the link is encrypted with the pairing secret).
- Deletion: uninstalling, or "Forget" on the car, removes everything.

### If rows are required

| Data type | Where it goes | Purpose |
|---|---|---|
| Messages (notifications content) | The paired car only | App functionality |
| Contacts, call log, phone number of the caller | The paired car only | App functionality |
| Calendar events | The paired car only | App functionality |
| Precise location, in the background | The paired car only, during a drive | App functionality |
| SMS: the "On my way" text | Sent by the phone's SMS to the contact the user picked on the car | App functionality |

### Security practices

Same answers as the launcher.

## Linked declarations (App content page)

Fill these in the Play Console under **Policy > App content**, for the app named:

- **Privacy policy**: both apps.
- **Accessibility service** (Dashwheel): the app uses the AccessibilityService API for a non-accessibility purpose. Core use: split screen, typing dictated text into the selected field, and steering-wheel buttons. The app shows a prominent disclosure in-app before opening accessibility settings and never enables the service itself; the service config declares `isAccessibilityTool="false"`.
- **Foreground service** (Companion): type `connectedDevice`; purpose: keeps the encrypted link to the car's head unit alive while the screen is off. Provide a short video of the connection running.
- **Location, background** (Companion): the phone's GPS is shared with the car during a drive; sharing starts when the car's Bluetooth connects, with the phone in a pocket. Prominent disclosure: the setup checklist row explains it before the request. Provide a video.
- **SMS and Call log permissions** (Companion): use case "Connected device companion app" (phone-to-car companion). SEND_SMS: the "On my way" message composed on the car and sent from the phone. READ_CALL_LOG: the Quick dial's recent calls on the car's screen and the caller's number. Each is requested only from the matching checklist row.
- **Phone permissions** (Companion): READ_PHONE_STATE, ANSWER_PHONE_CALLS, CALL_PHONE: calls answered, ended and placed from the car's screen.
- **Data safety**: as above, both apps.
- **Ads**: no ads, both apps.
- **Target audience**: 18+ (drivers), not designed for children.
- **Government apps / financial features / health**: No.
- **Device and network abuse, Package visibility** (Dashwheel): the launcher lists installed apps and queries `com.qf.bluetooth` and `com.qf.vehicle`; explain "a car launcher shows the apps on the head unit".
