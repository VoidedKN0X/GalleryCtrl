# GalleryCtrl

Gallery app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings.

## Layout

- `media/Media.kt`: `MediaRepository` over MediaStore Files (images + videos, trashed items excluded), a ContentObserver, Android's thumbnail store with an LRU cache.
- `media/Actions.kt`: `MediaRequests` (favourite and permanent delete through MediaStore requests; no trash), `Sharing` (copies with GPS removed, FileProvider `share/` only), `Vault` (AES-256-GCM files and an encrypted `index.bin` in `filesDir/vault`, key in AndroidKeyStore; photos and thumbnails decrypted in memory only, videos into `cacheDir/vault`, wiped with `File.wipe()`). Sharing fails closed: with location removal on, `strippedCopy` never returns an original. Photos are rewritten with ExifInterface, MP4/MOV/3GP videos are remuxed (MediaExtractor + MediaMuxer, no `setLocation`), other formats are shared as an exact copy only when `carriesNoLocation` can prove there's no location; everything else isn't shared and a toast says so.
- `media/Duplicates.kt`: dHash + 3×3 colour grid.
- `media/OnThisDay.kt` (date matching: EXIF camera date, then EXIF offset, then phone zone; 29 Feb on 28 Feb), `media/Clustering.kt` (Web Mercator + grid clustering), `media/PhotoInfo.kt` (EXIF dates and GPS via `setRequireOriginal`, memory only, cleared on lock), `media/WorldOutline.kt` (Natural Earth 110m GeoJSON in `assets/`).
- `ui/gallery/PhotoMap.kt`: the photo map. Mapsforge with a user-picked `.map` (content URI with a persisted read grant, `InMemoryTileCache`, never copied) or a Compose canvas with country outlines.
- `ui/gallery`: `Grids.kt` (access, grids, tabs, album, search, duplicates, vault), `Viewer.kt` (pager, zoom, video, Ultra HDR, info, vault viewer).

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting. No cloud or AI services.
- **No trash bin.** Delete is permanent (MediaStore delete request). Never add a hidden or recoverable copy.
- Never request MANAGE_EXTERNAL_STORAGE; library changes go through MediaStore requests.
- Vault plaintext only in memory, or (videos and photos over 64 MB) in `cacheDir/vault`, wiped on leaving the vault, on lock and at start. Never write vault names or thumbnails anywhere unencrypted.
- Map: offline only, no tile servers; map files come from the system file picker. Never cache photo locations on disk, and never show vault items on the map.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "GalleryCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
