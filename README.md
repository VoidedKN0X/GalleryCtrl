# GalleryCtrl

**A private, offline gallery for your photos and videos, with an encrypted vault.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/GalleryCtrl?label=download)](https://github.com/VoidedKN0X/GalleryCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Offline](https://img.shields.io/badge/internet-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

GalleryCtrl replaces your phone maker's gallery app. It shows the photos and videos on your phone where they already are. There's no cloud, no account and no AI service, and nothing is uploaded or scanned elsewhere. Private photos go in an encrypted vault, and what you delete is gone for good.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/GalleryCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **Works fully offline.** GalleryCtrl doesn't have the internet permission and never goes online
- **Photos tab:** everything newest first, grouped by day, with 2 to 6 columns
- **On this day:** photos and videos from this date in earlier years, as a row at the top of Photos and as a page of their own (Albums > On this day), grouped by year. A photo counts on the day it was taken where it was taken, using the camera's own clock, so holiday photos from another time zone land on the right day; photos from 29 February show on 28 February in other years
- **Photo map:** your photos with a GPS location as pins on a map, grouped into numbered clusters that split as you zoom in; tap one to see its photos. Fully offline: without a map file it shows a simple world map with country outlines, and with a Mapsforge `.map` file you pick (for example from download.mapsforge.org, the maps MapsCtrl uses) it shows streets and places. Only photos in your library appear, never vault items
- **Albums tab:** every folder, plus Favourites, Videos, the Vault and Duplicates
- **Viewer:** swipe between photos, pinch or double-tap to zoom (very large photos, up to 200 MP, load sharper as you zoom), and Ultra HDR photos shown in HDR on HDR screens
- **Video playback** with Android's own player controls
- **Info:** date, size and resolution, folder, camera, lens, exposure and the photo's location (shown on the phone only)
- **Search** by file name, folder or date ("june 2024", "12 november")
- **Select several** with a long-press: share, favourite, delete or move to the vault
- **Share without your location:** the shared copy has its GPS position removed (on by default); your original keeps it
- **Edit** in [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) if it's installed, otherwise in any editor you choose
- **Set as** wallpaper or contact photo, and **slideshows** of any album (2, 4 or 8 seconds per photo)
- **Duplicate finder:** near-identical photos (bursts, re-saved copies) found on the phone by comparing structure and colour; keep the best and delete the rest
- **Encrypted vault:** move photos and videos into GalleryCtrl's own storage, encrypted with AES-256-GCM and a key held in the phone's secure hardware; the originals are removed from your library. View, restore or delete them; the vault can ask for your app PIN
- **Delete means deleted:** there's no trash bin, so deleted photos don't linger in a hidden folder. Android asks you to confirm each delete
- **Limited access:** allow only the photos you pick, and add more any time
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, following your phone's dark theme setting, high contrast, one violet accent, Space Grotesk and JetBrains Mono; landscape, tablet and foldable layouts

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/GalleryCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `GalleryCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings and vault are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds GalleryCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/GalleryCtrl` in Obtainium by hand.

## Getting started

- **Access:** allow all photos and videos, or pick only some ("Select photos and videos"). You can choose more later from the banner on the Photos tab.
- **View:** tap a photo. Tap again to hide the bars; pinch or double-tap to zoom; the play button starts a slideshow.
- **Select:** long-press a photo, tap more, then use the bar at the bottom.
- **Vault:** select photos and tap the lock. Open it from **Albums > Vault**. Android asks you to confirm removing the originals from your library.
- **Duplicates:** **Albums > Duplicates** compares your photos on the phone; **Keep best** deletes the others in that group.
- **Map:** **Albums > Map**. For streets and places, download a Mapsforge `.map` file for your region from download.mapsforge.org in your browser, then tap the layers button on the map and **Choose a map file**. GalleryCtrl reads the file where it is and never goes online.
- **Settings** (profile picture): grid size, On this day, slideshow speed, location removal when sharing, the vault PIN and privacy.

## Is it safe to install?

- **Open source.** All of GalleryCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks that the app has no network permission and no Google libraries.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over GalleryCtrl. The SHA-256 fingerprint of GalleryCtrl's signing certificate is:

  ```
  02:1A:90:9B:83:9F:67:9E:15:57:53:44:94:2E:8B:A1:2B:EA:BC:3F:B0:39:62:06:0B:02:70:8D:3A:9D:16:20
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum GalleryCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify GalleryCtrl-vX.Y.Z.apk -R VoidedKN0X/GalleryCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.** GalleryCtrl has **no internet permission**: it can't go online at all. It never asks for "all files" access; changes to your library go through Android's own confirmation dialogs.

| Permission | What GalleryCtrl uses it for |
| --- | --- |
| Photos and videos (all, or only the ones you pick) | Showing your library where it is on the phone. |
| Access media location | Showing a photo's location in Info, and removing it from shared copies. Never sent anywhere. |
| Use biometric hardware | Optional fingerprint unlock for the app lock and the vault. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it. |
| App-internal broadcasts (`com.vknxworks.galleryctrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only GalleryCtrl itself can send to its own internal receivers. |

See [Privacy](#privacy) for what happens to your data.

## App lock and vault

Turn the app lock on under **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **The vault** asks for the PIN by itself when the lock is on (Settings > Vault). Vault files, and the list of what's in the vault, are encrypted with AES-256-GCM (a fresh random IV per file); the key never leaves the phone's secure hardware, so a copy of the files is useless elsewhere. Photos and thumbnails are decrypted in memory only. A video you play is decrypted into GalleryCtrl's private cache, then overwritten and deleted when you leave the vault or the app locks (or at the next start if the app was closed). Restoring goes straight from the encrypted file into your library.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to delete the vault (it's what the lock protects, and its key is deleted too), reset GalleryCtrl's settings and turn the lock off. Your photo library isn't touched.

**Hide in screenshots** and **Hide in recent apps** (both off by default): the first keeps GalleryCtrl out of screenshots and screen recordings, the second shows a blank card in the recent-apps view while screenshots still work.

## Privacy

**Telemetry-free.** GalleryCtrl has no analytics, crash reporting, usage stats or tracking of any kind. It has **no internet permission**, works fully offline and never connects to the internet. Every build checks that the app has no network permission and no Google libraries.

- Your photos are read in place. GalleryCtrl keeps no copy, index or thumbnails of its own outside Android's thumbnail store; the duplicate finder works in memory.
- **Locations stay on the phone.** The map and On this day read dates and GPS positions from your photos on the phone and keep them in memory only; nothing about where your photos were taken is saved by GalleryCtrl or sent anywhere. A photo's location is only shown where the photo already has one, and "Remove location when sharing" still applies to everything you share from the map or On this day. Vault items never appear on the map.
- **Maps are offline.** GalleryCtrl has no internet permission and talks to no tile server. The map file you pick is read in place (Android remembers only your permission to read it); "Where to get map files" opens download.mapsforge.org in your browser, only when you tap it.
- No cloud sync, face recognition service, AI eraser or anything else that sends photos away.
- Shared copies have their location removed by default (GPS tags, and XMP data that carries a position) and are deleted from GalleryCtrl's cache the next time it starts. Each copy is checked: a photo whose location can't be removed isn't shared while this setting is on.
- The vault and settings are in GalleryCtrl's private storage. Android backups don't include them, and GalleryCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How GalleryCtrl is made

GalleryCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/GalleryCtrl.git
   cd GalleryCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

GalleryCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/GalleryCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| **GalleryCtrl** (this app) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

GalleryCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

The photo map uses [Mapsforge](https://github.com/mapsforge/mapsforge) (LGPL-3.0) with [AndroidSVG](https://github.com/BigBadaboom/androidsvg) (Apache-2.0), and country outlines from [Natural Earth](https://www.naturalearthdata.com/) (1:110m countries, public domain).

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
