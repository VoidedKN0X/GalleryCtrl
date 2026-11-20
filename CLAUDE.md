# GalleryCtrl

Gallery app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings.

## Layout

- `media/Media.kt`: `MediaRepository` over MediaStore Files (images + videos, trashed items excluded), a ContentObserver, Android's thumbnail store with an LRU cache.
- `media/Actions.kt`: `MediaRequests` (favourite and permanent delete through MediaStore requests; no trash), `Sharing` (hands the original MediaStore URIs to the chooser; no copies, no FileProvider), `Vault` (AES-256-GCM files and an encrypted `index.bin` in `filesDir/vault`, key in AndroidKeyStore; photos and thumbnails decrypted in memory only, videos into `cacheDir/vault`, wiped with `File.wipe()`).
- `media/Duplicates.kt`: dHash + 3×3 colour grid, hashes kept in memory only (cleared on lock). `media/DuplicateGroups.kt`: the grouping on plain arrays (unit-tested against comparing every pair; the groups must stay identical to that).
- `media/OnThisDay.kt` (date matching: EXIF camera date, then EXIF offset, then phone zone; 29 Feb on 28 Feb), `media/PhotoInfo.kt` (EXIF dates for On this day, memory only, cleared on lock; no GPS).
- `ui/gallery`: `Grids.kt` (access, grids, tabs, album, search, duplicates, vault), `Viewer.kt` (pager, zoom, video, Ultra HDR, info, vault viewer). Info is the only place a photo's location is read (EXIF via `setRequireOriginal`, needs `ACCESS_MEDIA_LOCATION`).
- `data/settings/AppSettings.kt`: `removeMapLeftovers()` is the one-time clean-up for the removed photo map (releases the old map file's persisted read grant, deletes the `map_file` key). Nothing else persists URI grants; if a feature ever does, change that clean-up first.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting. No cloud or AI services.
- **No trash bin.** Delete is permanent (MediaStore delete request). Never add a hidden or recoverable copy.
- Never request MANAGE_EXTERNAL_STORAGE; library changes go through MediaStore requests.
- Vault plaintext only in memory, or (videos and photos over 64 MB) in `cacheDir/vault`, wiped on leaving the vault, on lock and at start. Never write vault names or thumbnails anywhere unencrypted.
- **No photo map** (removed at the owner's request; don't add it or Mapsforge back). Photo locations are read only for the Info of the photo being viewed; never scan the library for locations and never cache them on disk.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "GalleryCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
