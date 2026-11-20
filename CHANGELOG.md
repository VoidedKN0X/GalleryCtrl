# Changelog

What changed in each GalleryCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/GalleryCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Not released yet: these changes are built but have no version number. When the next version is published, move them under its heading.

### Privacy

- **"Remove location when sharing" no longer lets videos and other file types through with their location.** Until now only JPEG, WebP, PNG and HEIC photos had their location removed; videos, AVIF, RAW/DNG, GIF and JPEG XL files were shared exactly as they were, location included, even with the setting on. With the setting on (the default), nothing is shared any more unless its location was removed or GalleryCtrl could tell it has none:
  - **MP4, MOV and 3GP videos are rewritten without the location.** The picture and sound are copied into a new MP4 exactly as they are (nothing is re-encoded, so the quality is the same) and the rotation is kept. Android's own tools do this, and they only write a location when asked to. The copy is checked afterwards: Android must report no location, no known location entry may be left in the file, and the rotation must match the original. **A rewrite also drops everything else stored in the video file:** the recording date (it becomes the moment you shared), camera make and model, titles, and extra data tracks such as motion data. A MOV or 3GP is shared as an MP4. Your original isn't touched. A short "Removing the location from the video…" message shows while this runs; a long video takes a while and needs as much free space as the video itself.
  - **A video that can't be rewritten** (a codec Android's MP4 writer doesn't accept, a subtitle track, a damaged file, not enough free space) is shared as an exact copy only if no location is found in it. Otherwise it isn't shared.
  - **AVIF, DNG and RAW photos and GIFs** can't be rewritten. An exact copy is shared only if GalleryCtrl can read the file's details and finds no GPS tags and no XMP position in it.
  - **File types GalleryCtrl can't read well enough to be sure are not shared while the setting is on:** JPEG XL, Canon CR3, MKV and WebM videos, and AVIF or RAW files that carry no camera details at all.
  - **You're told what was left out:** "N videos weren't shared: their location couldn't be removed" for videos, "N files weren't shared: the location can't be removed from this file type" for other formats, and the existing message for photos. Everything else in the selection is still shared. To share an item as it is, turn off Settings > Remove location when sharing.
  - Sharing with the setting off is unchanged: the originals are shared as they are.
- **Not tested on a phone yet.** This change was only built by GitHub, never run. In particular it's untried whether every kind of recording (Dolby Vision, HDR10+, slow motion) goes through the rewrite and plays correctly afterwards. If the rewrite fails, the video is refused rather than shared with its location.
- **Limits of the check:** it looks in the standard places a location is kept (EXIF GPS tags, XMP, and the location entries of MP4/MOV files). A position a camera stores in its own private format, or prints into the picture, isn't recognised.

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
