# Changelog

What changed in each GalleryCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/GalleryCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
