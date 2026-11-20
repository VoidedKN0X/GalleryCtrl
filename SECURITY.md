# Security policy

Thank you for helping keep GalleryCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/GalleryCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of GalleryCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

GalleryCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for GalleryCtrl to send photos, videos or their details off the phone
- Reading vault contents without the key, decrypted vault files left behind, or the vault opening without the PIN when it should ask
- Deleted photos remaining recoverable through GalleryCtrl
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
02:1A:90:9B:83:9F:67:9E:15:57:53:44:94:2E:8B:A1:2B:EA:BC:3F:B0:39:62:06:0B:02:70:8D:3A:9D:16:20
```

The key never meets the build: the job that runs Gradle (and with it every plugin and library) has no secrets and produces an unsigned APK. A separate job that checks out nothing and runs no build code signs that APK, then refuses to pass it on unless it is signed by exactly this certificate and its contents are unchanged.
