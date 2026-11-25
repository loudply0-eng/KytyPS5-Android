# PS5 Game Hub unified Android integration

## Scope

This maintained fork keeps the KytyPS5 Android launcher, game-library scanner, SDL3-backed `GameActivity`, GPU-driver controls, and native engine integration. The project-owned updater is added to the existing launcher rather than replacing the game UI with a separate shell.

The unified production Android application ID is:

`com.ps5gamehub.android`

The Kotlin namespace remains `com.kytyps5.android` so the upstream source package paths and engine activity references remain intact. Android update compatibility is determined by the application ID and signing certificate, not the Kotlin namespace.

## In-app update behavior

- Checks the latest published stable release from the PS5 Game Hub GitHub Releases API when the launcher opens.
- Automatic checks are throttled to once every 12 hours; after a failed attempt, the app waits at least 30 minutes before retrying. Manual checks remain available at any time.
- Check timestamps are stored in app-private preferences and survive launcher restarts; disabling automatic checks stops launch-triggered requests without disabling the manual check button.
- Offers a manual **Check for updates** action.
- Accepts only stable three-part semantic version tags.
- Requires exactly one APK asset and a valid GitHub SHA-256 digest.
- Allows HTTPS downloads only from the explicit GitHub release asset host allowlist and validates each redirect.
- Limits the APK size, checks the declared content length, hashes the downloaded bytes, and stores the APK only under the app's private `cache/update/` directory.
- Exposes only that cache subdirectory through a non-exported AndroidX FileProvider.
- Hands the verified APK to Android's package installer.

Android requires the user to approve installation. The app does not and cannot silently install an update on an ordinary, non-rooted Android device.

## Release identity

Release builds must use the same production keystore and certificate as the established PS5 Game Hub app. The build refuses to produce a release if any of these environment variables is missing or the keystore file is absent:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

CI must supply monotonically increasing `appVersionCode` and stable `appVersion` values. Before publishing, verify the APK certificate SHA-256 against the already-distributed production app certificate. Never generate a replacement keystore to bypass an identity mismatch.

## Upstream source and license

The KytyPS5 engine and Android launcher are derived from:

- Upstream: https://github.com/tarek369/KytyPS5-Android
- Upstream revision integrated as the starting point: `9ab8b82daf2de1f763491b005e1ed1b28f843bb7`
- License: GPL-2.0-only

The original license and upstream notices must remain in the source distribution. Modified upstream-derived files must carry appropriate change notices, and any distributed binary must be accompanied by the complete corresponding source and GPL-2.0-only license information as required by the license. Third-party submodule notices must also be retained.

## Validation status

A successful native compilation proves that the engine libraries were built and packaged; it does not prove that commercial PS5 games boot or run correctly. The unified APK still requires:

1. CI build and unit-test success.
2. APK inspection confirming the expected application ID, updater permission/provider, and ARM64 native libraries.
3. Production certificate comparison against the existing app.
4. Install-over-existing-app and in-app update testing on a physical Android device.
5. Actual game compatibility testing on the target device.

No release or tag should be published until these gates pass and release publication is explicitly authorized.
