# Resona for Android

Resona is a YouTube Music client for Android. It is a fork of
[ArchiveTune](https://github.com/rukamori/ArchiveTune) by rukamori and its
contributors (GPLv3), with Resona's own additions ported over from the
[iPad app](https://github.com/jackderganove-sudo/Resona).

- Package name: `dev.jackderganove.resona` (installs next to ArchiveTune)
- Releases: signed APKs on [GitHub Releases](https://github.com/jackderganove-sudo/Resona-Android/releases), built by CI on every push to `main`
- The in-app updater checks this repository's releases, nothing else
- No telemetry, no crash reporting, no remote kill switch

## Install with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on the phone.
2. Tap **Add App** and paste
   `https://github.com/jackderganove-sudo/Resona-Android`
3. Leave the source on **GitHub**. Under **APK filter (regex)** enter
   `arm64` for modern phones (Pixel, most devices since 2017) or
   `universal` if you are unsure.
4. Tap **Add**, then **Install**. Obtainium checks for new releases and
   offers updates.

Each release has two APKs:

| File | For |
| --- | --- |
| `app-gms-mobile-arm64-release.apk` | 64-bit ARM phones (smaller) |
| `app-gms-mobile-universal-release.apk` | every device |

All releases are signed with the same key. Certificate SHA-256:
`BE:A3:71:6A:BF:76:D4:74:67:8D:E8:2D:CB:F6:D2:43:30:56:BD:62:2D:FA:D5:AE:A3:33:1A:F3:5F:15:CC:2F`

## Building

```bash
git clone --recursive https://github.com/jackderganove-sudo/Resona-Android
./gradlew assembleGmsMobileUniversalDebug
```

Release builds are signed in CI only (`.github/workflows/resona-release.yml`);
the keystore never enters the repository.

## Credits

ArchiveTune by [rukamori](https://github.com/rukamori) and contributors,
which builds on Metrolist by Mostafa Alagamy. The original
README is kept in [docs/README-ArchiveTune.md](docs/README-ArchiveTune.md), including its full list of acknowledgments.

License: GPL-3.0, see [LICENSE](LICENSE).
