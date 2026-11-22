# GalleryCtrl

Gallery app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings.

## Layout

- `media/Media.kt`: `MediaRepository` over MediaStore Files (images + videos, trashed items excluded), a ContentObserver, Android's thumbnail store with an LRU cache.
  The library is read once; `start(access)` reads it again only when the access changed, the observer follows changes, and in the background (`onBackground`/`onForeground` from `GalleryCtrlApp`) changes only mark it dirty for one re-read on return. Grid grouping and albums go through `rememberComputed` (ui/gallery/Computed.kt): memoised by list identity, recomputed off the main thread.
- `media/Actions.kt`: `MediaRequests` (favourite and permanent delete through MediaStore requests; no trash), `Sharing` (hands the original MediaStore URIs to the chooser; no copies, no FileProvider) and `File.wipe()`.
- `media/VaultFormat.kt`: the vault file format v2 (`<id>.gcv`, per-file AES-256 key wrapped by the AndroidKeyStore key, 64 KB AES-GCM chunks with Tink-style nonces and the header as AAD; `ChunkedOutputStream`, `ChunkedReader` with random access) and `VaultConversion` (one-time crash-safe conversion of old `<id>.bin` files: write tmp, read back and compare SHA-256, rename, only then delete the old file). Pure JVM code, unit-tested in `VaultFormatTest`; any format change needs a new version byte and tests.
- `media/Vault.kt`: the vault (entries in an encrypted `index.bin`, still the old single-stream Keystore GCM since it's small; per-entry encrypted `<id>.thumb`; old `.bin` files read the old way until converted). Videos stream to the player through `media/VaultStreamProvider.kt` (not exported, same-UID only, only while `Vault.viewing`), backed by `StorageManager.openProxyFileDescriptor`; large photos decode through the same proxy fd. Only unconverted old videos still go through `cacheDir/vault`. `android:largeHeap` is on so bigger old files can still be converted.
- `media/Duplicates.kt`: dHash + 3×3 colour grid, hashes kept in memory only (cleared on lock). `media/DuplicateGroups.kt`: the grouping on plain arrays (unit-tested against comparing every pair; the groups must stay identical to that).
- `media/OnThisDay.kt` (date matching: EXIF camera date, then EXIF offset, then phone zone; 29 Feb on 28 Feb), `media/PhotoInfo.kt` (EXIF dates for On this day, memory only, cleared on lock; no GPS).
- `ui/gallery`: `Grids.kt` (access, grids, tabs, album, search, duplicates, vault), `Viewer.kt` (pager, zoom, video, Ultra HDR, info, vault viewer). Info is the only place a photo's location is read (EXIF via `setRequireOriginal`, needs `ACCESS_MEDIA_LOCATION`).
- `data/settings/AppSettings.kt`: `removeMapLeftovers()` is the one-time clean-up for the removed photo map (releases the old map file's persisted read grant, deletes the `map_file` key). Nothing else persists URI grants; if a feature ever does, change that clean-up first.
- `SettingsRepository.loaded` turns true once the saved settings are read. `CtrlWindow` keeps FLAG_SECURE on until then, and every activity calls `startHidden()` (FirstFrameGate, MainActivity.kt) so no frame is drawn with default settings. Background/cold-start code that reads `settings.value` before `loaded` gets the defaults, so defaults must stay the strictest choice (lock timeout IMMEDIATELY, vault PIN on).
- The app runs `:app:testDebugUnitTest` in the `build` job; keep tests pure JVM (no Robolectric).

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting. No cloud or AI services.
- **No trash bin.** Delete is permanent (MediaStore delete request). Never add a hidden or recoverable copy.
- Never request MANAGE_EXTERNAL_STORAGE; library changes go through MediaStore requests.
- Vault plaintext only in memory (or streamed through the proxy fd); only old-format files not converted yet may go through `cacheDir/vault`, wiped on leaving the vault, on lock and at start. Never write vault names or thumbnails anywhere unencrypted. Never delete an old vault file before its converted copy has been read back and compared.
- The vault's own PIN (`VaultLock.open`) is separate from `AppLock.unlocked`: cleared on leaving the vault and in `AppContainer.onLocked()`. "Forgot PIN?" must stay behind the phone's screen lock (`AppLock.screenLockAuthenticate`) when one is set.
- **No photo map** (removed at the owner's request; don't add it or Mapsforge back). Photo locations are read only for the Info of the photo being viewed; never scan the library for locations and never cache them on disk.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Build toolchain

- AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37 (Compose 1.12 needs it), `minSdk` 35, `targetSdk` 36. All versions are in `gradle/libs.versions.toml`.
- AGP 9 has built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin any more. The Kotlin version is pinned by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle.kts`; the Compose and serialization compiler plugins use the same `kotlin` version. Don't re-add the Kotlin Android plugin.
- AGP 9's R8 is stricter (`-keep class X` without a member block no longer keeps the constructor; resources are shrunk together with code). Keep rules in `proguard-rules.pro` must name the members they need, and resources must be referenced through `R`, never by name.
- Any version change here needs new checksums in `gradle/verification-metadata.xml`: push the change, start the `verification-metadata.yml` workflow on `main`, and commit the file it prints, unchanged. Never write or edit that file by hand and never make verification lenient.

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.
- `settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made dependency verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "GalleryCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
