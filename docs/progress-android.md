# Resona Android: Fortschritt

Fork von [rukamori/ArchiveTune](https://github.com/rukamori/ArchiveTune), Basis Branch `dev`.

## Schritt 1: Branding, Signatur, Releases

| Punkt | Stand |
| --- | --- |
| App-Name „Resona“, adaptives Resona-Icon (inkl. Monochrom für Themed Icons) | erledigt |
| applicationId `dev.jackderganove.resona` (läuft neben ArchiveTune) | erledigt |
| Update-Quelle: GitHub Releases von jackderganove-sudo/Resona-Android | erledigt |
| Gatekeeper (Remote-Sperre) aus, keine Telemetrie/Crash-Reports im Code | geprüft |
| Upstream-Workflows (R2, Telegram, fremde Secrets) entfernt | erledigt |
| CI: signierte APKs (arm64 + universal) bei jedem Push auf `main` als Release | erledigt |
| README mit Obtainium-Anleitung | erledigt |

Signatur: eigener Keystore, liegt nur lokal und als GitHub-Secret
(`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`).
Zertifikat SHA-256 `BE:A3:71:6A:BF:76:D4:74:67:8D:E8:2D:CB:F6:D2:43:30:56:BD:62:2D:FA:D5:AE:A3:33:1A:F3:5F:15:CC:2F`.

## v1.0.0 → v1.0.1: „Update required“-Sperre behoben

Fehler: Beim Start erschien ArchiveTunes Bildschirm „Update required“
(installiert 1.0.0, verfügbar 15.1.0) und sperrte die App.

Ursache: Der Updater cacht die Release-Liste im DataStore und nutzt den Cache
ohne Netzabfrage (bis 6 h alt oder wenn die Abfrage scheitert). Der Cache war
nicht an ein Repository gebunden; kam er aus ArchiveTune (z. B. eingespieltes
ArchiveTune-Backup), galt ArchiveTune 15.1.0 als „neuere Version“. ArchiveTunes
Regel „mindestens zwei stabile Releases zurück → Update erzwingen“ sperrte
dann die App.

Behebung:
- Release-Cache an `jackderganove-sudo/Resona-Android` gebunden; ein Cache
  aus einer anderen Quelle wird samt ETag verworfen.
- Nur Releases, deren Seite *und* APK aus diesem Repo stammen, zählen; alle
  anderen werden beim Einlesen verworfen.
- Kein Update-Zwang mehr (`ResonaReleasePolicy.FORCE_UPDATES = false`).
  Ein echtes Resona-Update öffnet höchstens einmal am Tag die normale
  Update-Seite, die man mit Zurück schließt; dazu die Update-Benachrichtigung.
- Download nur von `github.com/jackderganove-sudo/Resona-Android/releases/download/…`;
  vor der Installation prüft die App, dass die APK `dev.jackderganove.resona`
  ist und mit demselben Schlüssel signiert wurde wie die installierte App.
- Versionen: versionName = Resona-Version (1.0.x aus dem Release-Tag), die
  auch „Über“, „Was ist neu“ und Vergleich nutzen; ArchiveTune-Nummern kommen
  nicht mehr in die Liste.
- Prüfung in der CI bei jedem Release: Unit-Tests der Update-Regeln
  (`ResonaReleasePolicyTest`) und ein Start-Test auf dem Emulator: frisch
  installierte APK starten, nach 45 s Bildschirm und Log prüfen (keine
  „Update required“-Seite, kein Absturz, Resona im Vordergrund). Erst dann
  wird das Release veröffentlicht; Screenshot + UI-Dump als Artefakt.

Ergebnis v1.0.1 (CI-Starttest auf dem Emulator, frisch installiert):
„Welcome to Resona – Version 1.0.1 – Get started“, keine Update-Seite, kein
Absturz. v1.0.2: Willkommens-Logo und Icon-Vorschau zeigen das Resona-Glyph
statt ArchiveTunes Zeichen.

### ArchiveTune-Server und -Links: Stand

| Stelle | Vorher | Jetzt |
| --- | --- | --- |
| App-Update / Update-Seite / Update-Benachrichtigung | GitHub-Releases (Cache ohne Quelle) | nur Resona-Android-Releases, Cache an Repo gebunden |
| Update-Zwang (2 Releases zurück) | an | aus |
| Update-Download | beliebige URL aus der Release-Liste | nur eigene Release-Assets + Signaturprüfung |
| „Was ist neu“ / Release-Notes | Updater | Resona-Android-Releases |
| News | `koiverse/ArchiveTuneNewsRepository` | aus |
| Gatekeeper / „ArchiveTune Remote“ | aus, Adresse `archivetune-data.koiiverse.cloud` | aus, Adresse entfernt |
| Telemetrie / Crash-Reporting | keine; Crash-Log nur lokal zum Teilen | unverändert lokal |
| Stern-/Spenden-Dialog nach X Starts | rukamori, miuka.moe/donate | aus |
| Onboarding-Community (GitHub, Discord, Telegram, Spenden) | ArchiveTune | nur Resona-Android-GitHub |
| Über: Website, Telegram, Spenden, Discord, Datenschutz | ArchiveTune | entfernt; GitHub = Resona-Android, Link „ArchiveTune“ als Basis; Credits (rukamori, Mitwirkende) bleiben |
| Early-Access beitreten | ArchiveTune-Discord | Resona-Android-Releases |
| Listen Together online | Server-Adresse aus `rukamori/ArchiveTune` | aus (lokal im WLAN bleibt) |
| Discord Rich Presence: Knopf, App-Icon | ArchiveTune-Repo | Resona-Android-Repo |
| User-Agent (Updater, SponsorBlock, Paxsenix, Artwork, Übersetzung, ListenBrainz-Client) | ArchiveTune | Resona |
| Ordner Bilder / externer Speicher | „ArchiveTune“ | „Resona“ |
| Sichtbare Texte (alle Sprachen) | „ArchiveTune“ | „Resona“ (außer Credits, rukamoris eigener Unterstützer-Text, „Original-ArchiveTune-Icon“) |

## Schritt 2: Resona-Extras

| Extra | Stand |
| --- | --- |
| Zum Drop springen (YouTube „Meistgehört“) | v1.0.3 – Player-Menü |
| Playlist-Werkzeuge: Duplikate entfernen, nach Künstler aufteilen, CSV-Export | v1.0.4 – Werkzeug-Knopf im Kopf lokaler Playlists |
| Smarte Downloads (WLAN, meistgehört 30 Tage + Likes, max. 150) | v1.0.5 – Einstellungen → Speicher |
| Slowed/Sped-up-Erkennung (Lyrics-Zeiten skalieren, LRCLIB-Originallänge) | v1.0.5 – automatisch |
| Crossfade, Sleep-Timer-Fade | gibt es in ArchiveTune schon (Einstellungen → Player) |
| Mehrfachauswahl, Übersetzung, zusätzliche Lyrics-Anbieter | gibt es in ArchiveTune schon |
| Frosted Glass | ArchiveTune hat Haze-Blur/Acryl; kein eigener Port nötig |
| Versionsbewusstes Lyrics-Ranking | teilweise: ArchiveTunes Anbieter liefern nur Text, keine Kandidaten mit Titel/Dauer; Plausibilitäts-/Tempoprüfung über das Slowed-Modul |
| Selbst-Sync (Tippen im Takt) | v1.0.6 – Lyrics-Menü → „Selbst synchronisieren“ (ArchiveTune hat dazu schon Versatz ±5 s) |
| KI-Sync/-Kalibrierung (whisper.cpp) | **nicht umgesetzt** – siehe „Offen“ |

## Testliste für das Pixel

Installation
- [ ] Obtainium: Repo-URL hinzufügen, Filter `arm64`, installieren
- [ ] App heißt „Resona“, Icon ist das Resona-Icon (auch mit Themed Icons)
- [ ] Läuft parallel zu einer installierten ArchiveTune-Version

Grundfunktionen (ohne Login)
- [ ] Startseite lädt, Suche findet Songs
- [ ] Wiedergabe startet, Benachrichtigung + Sperrbildschirm-Steuerung
- [ ] Bluetooth-Kopfhörer: Play/Pause/Weiter
- [ ] Lyrics erscheinen und laufen synchron
- [ ] Download eines Songs, Wiedergabe im Flugmodus

Resona-Extras
- [ ] Lyrics-Menü → „Selbst synchronisieren“: Text-Lyrics im Takt antippen, speichern, laufen danach synchron
- [ ] Player-Menü → „Zum Drop springen“ springt zur meistgehörten Stelle
- [ ] Lokale Playlist → Werkzeug-Knopf: Duplikate entfernen / nach Künstler aufteilen / CSV teilen
- [ ] Einstellungen → Speicher → „Smarte Downloads“ an, im WLAN laden die meistgehörten Songs
- [ ] Ein „(Slowed + Reverb)“-Upload: Lyrics laufen synchron

Mit Login (optional)
- [ ] Anmelden, Bibliothek und Playlists erscheinen

Start / Updates
- [ ] Frisch installiert (auch nach Einspielen eines ArchiveTune-Backups): kein „Update required“, App startet normal
- [ ] Einstellungen → Über: Version passt zum Release (1.0.x)
- [ ] Einstellungen → Updates → Suchen: findet nur Resona-Releases
- [ ] Nach dem nächsten Release: Obtainium und der In-App-Updater melden das Update

## Offen

- **KI-Sync/-Kalibrierung für Android (whisper.cpp)**: auf dem iPad läuft das
  mit WhisperKit (CoreML). Für Android bräuchte es whisper.cpp als natives
  NDK-Modul (CMake, JNI) für alle ABIs, einen Modell-Download (75–140 MB),
  das Dekodieren des Songs aus ExoPlayers Cache zu 16-kHz-PCM und den Port
  des Abgleichs (LNDS + Theil–Sen). Das ist ein eigener großer Schritt mit
  vielen CI-Durchläufen (je ~40 min); bis dahin gibt es Selbst-Sync und den
  Versatz pro Song.

## Runde 11 – Teil A (Android-Fixes)

### A1 Logo
ArchiveTunes Zeichen steckte in drei Vektoren, nicht nur im Launcher-Icon:
`about_appbar` (Logo neben „Resona“ im Kopf der Startseite), `about_splash`
(Über-Seite, Platzhalter im Mini-Player/Cover) und `small_icon`
(Benachrichtigung, Update-Benachrichtigung, Widgets, Teilen-Karten, Rückblick).
Alle drei zeigen jetzt das Resona-Glyph (Punkt + drei Bögen). Der
Android-12-Splash nutzt das adaptive Launcher-Icon (schon Resona), das
Monochrom-/Themed-Icon ebenso.

### A2 Lyrics als Text
Selbst-Sync zeigte TTML roh als XML. Neu: `ResonaLyricsText` zerlegt TTML, LRC,
Enhanced LRC, QRC und Klartext in Zeilen (ArchiveTunes Parser, bei Fehlern ein
eigener TTML-Leser). Genutzt von Selbst-Sync, Lyrics-Editor und dem Eingabefeld
der Übersetzung (dort wird TTML zu LRC mit erhaltenen Zeiten statt XML).
Teilen/Bild-Karten nutzen schon ArchiveTunes geparste Zeilen. Unit-Tests nur mit
Platzhaltertexten.

### A3 ArchiveTune-Überbleibsel (Suche über Code, Manifest, alle Sprachen)
| Fund | Sichtbar? | Maßnahme |
|---|---|---|
| Logo in `about_appbar`, `about_splash`, `small_icon` | ja | Resona-Glyph |
| Anime-Platzhalterbilder `ic_music_placeholder.png` (Eckenradius-Vorschau), `anime_blank.png` (private Playlist) | ja | entfernt; Glyph-Vorschau bzw. Schloss-Icon |
| GMS-Werbe-/Unterstützungskarte „Support ArchiveTune“ in der Bibliothek (öffnet archivetune.miuka.moe/support) | ja | aus |
| Deep-Links `archivetune://together`, `archivetune://login`, `archivetune://module-session` | ja (Links, Teilen) | `resona://…` (kein Konflikt mit installiertem ArchiveTune) |
| Log-Export `archivetune-log-….txt` | ja | `resona-log-….txt` |
| KI-Top-Mix-Prompt „music curator for ArchiveTune“ | indirekt | „for Resona“ |
| User-Agent Mitwirkenden-Abruf | nein | „Resona“ |
| Stern-/Spenden-Dialog (miuka.moe/donate) | war schon aus (v1.0.1) | – |
| News, Listen-Together-Server, Daten-Server, Update-Quelle | war schon aus/umgestellt (v1.0.1) | – |
| Texte in allen Sprachen | v1.0.1 ersetzt | geprüft: nur noch Credits, rukamoris eigener Unterstützertext (Dialog aus), Gatekeeper (aus) |
| Interne Schlüssel (`archivetune_download_format_id`, Queue-Metadaten, WebView-Profil, Theme-Namen, Paketname `moe.rukamori.archivetune`) | nein | bleiben – Umbenennen würde gespeicherte Daten/Downloads brechen |
| Backup-Dateiname | – | nutzt schon `app_name` → „Resona…“ |
| Notification-Channels, Shortcuts | – | Namen aus Strings (Resona), keine statischen Shortcuts |

### A4 Grüner „…“-Knopf auf der Startseite
Gewollt (ArchiveTune): Schnellaktionen Musikerkennung, Music Together,
Zufallswiedergabe. Kein Überbleibsel; Gestaltung wird in Teil C (Angleichung)
entschieden.
