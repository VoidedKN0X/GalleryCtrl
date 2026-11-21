# Changelog

What changed in each GalleryCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/GalleryCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there.

### Faster and smoother

- **The duplicate finder is much quicker on a big library, and finds exactly the same groups.** Three things changed in **Albums > Duplicates**:
  - *Comparing.* Every photo used to be compared with every other photo (for 20,000 photos that's about 200 million comparisons). Two photos only count as similar when their 64-bit fingerprints differ in at most 4 bits, and then at least one of five slices of the fingerprint must be exactly the same in both. So GalleryCtrl now only compares photos that share a slice. This is a shortcut that can't miss a pair; a test compares its result with the old every-pair method on thousands of made-up libraries (`DuplicateGroupsTest`). The groups, the order inside a group and which photo is marked **Best** are unchanged.
  - *Reading.* Thumbnails are fingerprinted 4 at a time instead of one after the other.
  - *Looking again.* After **Keep best** the list is worked out again. The fingerprints of photos already looked at are now remembered, so that takes a moment instead of reading every thumbnail again. They are kept in memory only, never written to storage, dropped for photos that are gone or changed, and forgotten when the app locks or closes.
- **The duplicate finder no longer empties the grid's thumbnail memory.** It used to put a small thumbnail of every photo in the same memory the grids use, pushing out the thumbnails you had been looking at, so the Photos tab had to load them again afterwards. Its thumbnails are now used once and freed straight away.

- **On this day shows up sooner the first time.** To know on which day a photo was taken where it was taken, GalleryCtrl reads the date inside the photo file, for the photos from around today's date in earlier years. It used to read them one after the other; it now reads 4 at a time. Which photos are shown, and in which order, is unchanged.

- **The Photos tab fills in sooner when the app starts with a big library.** The whole library used to be read before the first photo was shown. Now the newest 500 photos and videos are shown as soon as they've been read, and the rest follows a moment later in the same grid, at the same place. Only the Photos tab does this, and only at the first load after the app starts. Albums, search, On this day and duplicates still wait for the whole library, so their counts and results are never half-done; later refreshes replace the list in one go as before.
  - If you open a photo in that first moment, the viewer pages through those newest 500 until you go back to the grid.

### Security

- **The signing key is no longer available to the build.** Nothing in the app changed. Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Releases are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. The release job itself has not run with the new workflow yet; the next version will be the first.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the weekly code scan start from a clean Gradle home, and the plugin portal is no longer a plugin source at all (`settings.gradle.kts`): everything the build needs is on Maven Central and Google's Maven. Dependency verification itself is unchanged and as strict as before. Builds take a little longer.

### Changed

- **Newer build tools and libraries; nothing changes in how GalleryCtrl looks or works.** GalleryCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.11.1 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin 2.2.0 → 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2025.07.00 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries move to the versions the newest Ctrl apps use: AndroidX Activity 1.10.1 → 1.13.0, Core 1.16.0 → 1.18.0, Lifecycle 2.9.1 → 2.10.0, Navigation 2.9.1 → 2.9.8, DataStore 1.1.7 → 1.3.0, kotlinx.serialization 1.9.0 → 1.13.0 and kotlinx.coroutines 1.10.2 → 1.11.0. No library was added, and the app is still Google-free and offline (CI checks both).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions GalleryCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4; the release APK is about 4% larger (3.25 MB → 3.39 MB). GalleryCtrl's own keep rules are unchanged. The new R8 shrinks differently from the old one (stricter keep rules, resources removed together with unused code, classes repackaged), so the release build needs a run on a phone before the next version is published.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

## v0.3.0 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Removed

- **The photo map is gone.** **Albums > Map** and everything behind it were removed at the owner's request: the pins and numbered clusters, the simple world map with country outlines, the Mapsforge map from a `.map` file, the layers button with *Choose a map file*, *Use the simple map* and *Where to get map files*, and the scan that read the GPS position of every photo in your library to place the pins.
  - **What stays:** a photo's **Info** still shows its location (as coordinates) when the file has one, so the "Access media location" permission stays and is still asked for together with photo access. On this day is unchanged; it only ever read dates. Grids, albums, search, duplicates, the vault, the viewer, sharing and settings are untouched.
  - **Your photos and your map files are not touched.** A `.map` file you picked stays where it is on the phone; GalleryCtrl just no longer reads it. At the first start after updating, GalleryCtrl gives back the permission Android kept for reading that file and deletes the saved choice of map file. That was the only such permission the app ever held.
  - **Smaller app:** the Mapsforge libraries (with AndroidSVG, which came with them) and the built-in Natural Earth country outlines were removed from the app, along with their shrinker rules, their credits in the README and the map's unit tests.
  - GalleryCtrl now reads where a photo was taken only when you open that photo's Info. Nothing goes through your library for locations any more.
  - **Not tested on a phone.** The removal, and the one-time clean-up at the first start, were only built by GitHub (CI), never run on a device.
- **"Remove location when sharing" is gone.** The setting, and the whole Sharing group in Settings, were removed at the owner's request. Sharing now always hands the original photo or video to the app you pick, exactly as it is in your library, including the place it was taken if the file has one. Before, with the setting on (the default), JPEG, WebP, PNG and HEIC photos were shared as a copy without their GPS position.
  - If a file must not carry its location, remove it before sharing. Photos taken with CameraCtrl don't record a location in the first place; photos from other cameras, downloads or chat apps may.
  - GalleryCtrl no longer makes copies to share, so the file provider that served those copies was removed from the app, and copies left in the cache by an older version are deleted at the next start.
  - The "Access media location" permission stays: Info still uses it.
  - An earlier unreleased change on `main` that made this setting refuse or rewrite videos and other file types was removed with it and never shipped.
- **Not tested on a phone yet.** Both removals above were only built by GitHub, never run.

### Faster and smoother

- **Selecting photos no longer stutters in a big library.** The grid used to sort every photo into its day again on each tap while selecting, each change of the grid size and each redraw. It now does that once per list (and again only when the library, the list shown or the phone's time zone changes). What you see is the same.
- **Smoother scrolling:** day headings and photo tiles are now told apart in the grid, so Android reuses a tile for a tile and a heading for a heading.
- **Thumbnails you've already seen show at once.** A photo scrolled back into view used to be an empty square for a moment even when its thumbnail was still in memory; it's now drawn straight away.
- **A fast scroll no longer starts hundreds of thumbnail reads.** At most 4 thumbnails are read at a time. Photos that have already scrolled off the screen are skipped instead of being read anyway, and a read that's under way is told to stop. This saves battery and makes the thumbnails you're actually looking at appear sooner.
- **Search no longer freezes while you type.** Matching names, folders and dates against the whole library now happens in the background. The previous results stay on screen until the new ones are ready, and "No results" shows only once the search is done. The results are the same as before.

## v0.2.0 (2026-11-13)

On this day gets its own page and includes videos, and there's a new offline photo map.

### New

- **On this day, as a page.** *See all* next to the On this day row at the top of Photos, or **Albums > On this day**, shows every photo and video from today's date in earlier years, grouped by year with how long ago it was.
- **Videos in On this day.** Videos from this date in earlier years now show too, not only photos.
- **Photo map.** **Albums > Map** shows your photos that have a GPS location as pins. Pins close together are grouped into a numbered circle that splits up as you zoom in; tap a pin to see its photos and open them in the viewer. Pinch to zoom, drag to move, double-tap to zoom in.
- **Works without internet.** Out of the box the map is a simple world map with country outlines (Natural Earth), built into the app. For streets and places, download a Mapsforge `.map` file for your region (for example from download.mapsforge.org, the same maps MapsCtrl uses) and pick it with the layers button > *Choose a map file*. GalleryCtrl reads it where it is and never downloads anything; *Use the simple map* switches back.

### Changed

- **On this day uses the day the photo was taken where it was taken.** A photo from 23:30 on a holiday in New York counts for that day, even though it was already the next morning at home. GalleryCtrl uses the camera's own clock (or the time zone stored in the photo) when it has one.
- **Leap days:** photos from 29 February show on 28 February in years without a 29 February.
- "1 year ago" instead of "1 years ago" on the On this day row.

### Privacy

- The map only shows photos from your library that already have a location; vault items never appear. Locations are read on the phone, kept in memory only and forgotten when the app locks; GalleryCtrl never saves or sends them. "Remove location when sharing" still applies to everything you share from the map or On this day.
- GalleryCtrl still has no internet permission. *Where to get map files* opens download.mapsforge.org in your browser, only when you tap it.
## v0.1.1 (2026-11-13)

A security and privacy update for the vault and for sharing.

### Security

- **The vault's list is encrypted too.** The names, dates and sizes of what's in your vault are now encrypted like the photos themselves. The old readable list is converted and wiped the first time you open GalleryCtrl after updating.
- **Vault photos are no longer written to storage to show them.** Photos and their thumbnails are decrypted in memory only. Only a video you play still needs a temporary file in GalleryCtrl's private cache; it's overwritten and deleted when you leave the vault, when the app locks, and at the next start if GalleryCtrl was closed while a video was open.
- **Restoring from the vault** goes straight from the encrypted file into your library, with no unencrypted copy in between.
- **"Forgot PIN?" now also deletes the vault's key,** so nothing left behind on the phone's storage can be decrypted any more.
- A damaged or half-written vault file is never shown or restored half-way.

### Privacy

- **Sharing never leaks your location by accident.** Before, if GalleryCtrl couldn't remove the location from a photo (for example some HEIC photos), the photo was shared with its location. Now the shared copy is checked, and a photo whose location can't be removed isn't shared; a message tells you, and you can turn off "Remove location when sharing" to share it as it is. Location stored in a photo's XMP data is removed too.

### Performance

- Preparing photos to share happens in the background, so the app doesn't freeze when you share many large photos.
- Vault thumbnails are kept in memory while you scroll instead of being decrypted again.
- Less work on the main thread when GalleryCtrl starts.

## v0.1.0 (2026-11-12)

The first release of GalleryCtrl: a private, offline gallery.

### New

- **Photos tab** grouped by day with 2 to 6 columns, and **On this day** memories.
- **Albums tab** with every folder, Favourites, Videos, the Vault and Duplicates.
- **Viewer** with swipe, pinch and double-tap zoom (sharper as you zoom on very large photos), Ultra HDR on HDR screens, and video playback.
- **Info** with date, size, folder, camera, lens, exposure and location.
- **Search** by name, folder or date.
- **Select several** to share, favourite, delete or move to the vault.
- **Share without your location** (on by default).
- **Edit** in LumenCtrl or any editor, **Set as** wallpaper or contact photo, and **slideshows**.
- **Duplicate finder** that keeps the best photo of each group.
- **Encrypted vault** (AES-256-GCM, key in the phone's secure hardware), optionally behind your app PIN.
- **Delete means deleted:** no trash bin; Android confirms each delete.
- **Privacy:** no internet permission, limited photo access supported, app lock, Hide in screenshots and Hide in recent apps.
