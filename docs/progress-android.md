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
| Smarte Downloads (WLAN, meistgehört 30 Tage + Likes, max. 150) | im nächsten Release – Einstellungen → Speicher |
| Slowed/Sped-up-Erkennung (Lyrics-Zeiten skalieren, LRCLIB-Originallänge) | im nächsten Release – automatisch |
| Crossfade, Sleep-Timer-Fade | gibt es in ArchiveTune schon (Einstellungen → Player) |
| Mehrfachauswahl, Übersetzung, zusätzliche Lyrics-Anbieter | gibt es in ArchiveTune schon |
| Frosted Glass | ArchiveTune hat Haze-Blur/Acryl; kein eigener Port nötig |
| Versionsbewusstes Lyrics-Ranking | teilweise: ArchiveTunes Anbieter liefern nur Text, keine Kandidaten mit Titel/Dauer; Plausibilitäts-/Tempoprüfung über das Slowed-Modul |
| Selbst-Sync (Tippen im Takt) | offen |
| KI-Sync/-Kalibrierung (whisper.cpp) | offen – braucht ein natives whisper.cpp-Modul (NDK) und ein Modell-Download; großer Umbau |

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
