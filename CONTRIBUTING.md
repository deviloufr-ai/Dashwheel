# Contributing to Dashwheel

Thanks for helping. Dashwheel runs on car head units that differ a lot, so the most useful contributions are often reports from real units, not only code.

## Reporting how it works on your unit

In the app: **Settings, About, Report how it works**. It fills in a GitHub issue with your unit's model, firmware and Dashwheel's version, and lets you mark what works. Scan the QR code with your phone to send it. Nothing personal goes in it.

After a crash, Dashwheel offers to send the error the next time it starts, once the car is parked.

## Building

- Android Studio with a Gradle JDK of 17 to 21 (Gradle 9 and JDK 25 are not supported yet).
- `./gradlew :app:assembleGithubDebug` builds the GitHub edition; `:app:assemblePlayDebug` the Google Play edition (see `Edition.kt`).
- Build each edition in its own Gradle run: compiling both flavors in one run has crashed the Kotlin compiler.
- More: [Building from source](https://github.com/deviloufr-ai/Dashwheel/wiki/Building-from-Source).

## Before you open a pull request

- `./gradlew :app:lintGithubDebug :app:testGithubDebugUnitTest` passes. CI runs lint and the unit tests of both editions on every pull request, then a debug build. Forks get no signing secrets, and need none.
- Every string the driver sees is in all nine languages (en, fr, de, es, it, pt, nl, pl, ru): `python tools/check_translations.py` checks it. Strings live in per-area files (`strings_<area>.xml`) with the area's prefix.
- Keep the wording short and plain: a driver reads it at a glance. No ✓, —, or text in brackets.
- Never commit keys, tokens or a keystore: the repository is public.

## Commits

- Subject and body in English.
- The commit body is the release note: CI publishes it as the changelog. Write it as short, user-facing bullets ("The radio tile shows the station's name"), not as a code diary.

## Releases

- Every push to `main` that passes lint and tests is published as a **beta** pre-release. Drivers who turned on **Beta updates** get it.
- A beta becomes **stable** only when a maintainer runs the **Promote to stable** workflow in the Actions tab. The stable release is what every other driver and the phone companion update to.
- Promoting also runs **Prune old betas**, which deletes the betas older than the new stable and the branch pre-releases no one needs any more (only the newest of each live branch stays). It can be run by hand too, with a dry run.
- The version code counts `main`'s history, so never rewrite `main`'s history.

## Code layout

One Android module per app (`app`, `companion`), plus `link` (the phone link protocol) and `display` (the Raspberry Pi second screen). The launcher's code is in `app/src/main/java/com/openauto/dash`.

## License

Dashwheel is free to use but its code is not open source; see [LICENSE](LICENSE). By contributing you give the author a permanent right to use, change and distribute your contribution as part of Dashwheel.
