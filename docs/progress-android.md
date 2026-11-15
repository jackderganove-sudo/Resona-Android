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

## Runde 11 – Lyrics-Scroll ruckelt (nur mit Spotify-Canvas)

Gemessen auf dem Pixel 11 Pro (Panel 120 Hz), wip-Build (Release mit R8),
Songtext im Standardmodus „Enhanced“ (accompanist lyrics-ui), Zeilen-Blur an,
Farbverlauf-Hintergrund an, Song mit Canvas. Nur Zahlen, keine Liedtexte.

### Vorher
`dumpsys gfxinfo` nach `reset`, 30 s Songtext:

| Wert | vorher |
|---|---|
| Frames | 1726 |
| Janky Frames | 489 (28,3 %) |
| 50./90./95./99. Perzentil | 12 / 61 / 65 / 69 ms |
| Missed Vsync / Slow UI thread / Slow bitmap uploads | 387 / 303 / 480 |
| GPU 50./99. Perzentil | 3 / 13 ms |

Perfetto-Trace (12 s, `gfx view sched freq`), Auswertung per trace_processor:
- Hauptthread unauffällig (Recomposition ≤ 3 ms, `Lyrics.drawRow` ≤ 0,6 ms) –
  er wartet in `postAndWait` auf den RenderThread.
- RenderThread: `syncFrameState`/`prepareTree` bis 41 ms, darin
  `allocateImageMemory` ≈ 21× pro Frame.
- **Ursache:** in 9,7 s mehrere Tausend `Texture upload` der gerasterten
  Songtext-Zeilen (≈ 2000×310 px, ≈ 0,5 ms je Stück, 5–6 pro Frame) und 174
  Uploads eines 1080×1080-Bildes. Die Zeilen-Raster sind gecacht, wurden aber
  aus dem GPU-Textur-Cache verdrängt und jeden Frame neu hochgeladen.
- Auslöser (Hinweis aus dem Test: „nur wenn Canvas aktiv“): Der Canvas-Clip
  lief als Video im Cover des Players **unter** dem Songtext weiter (der
  Songtext ist ein Overlay über dem Player). Jedes Video-Frame erzwang ein
  neues Bild des ganzen Fensters, und Video plus Offscreen-Ebenen sprengten
  den Textur-Cache.

### Weitere Messungen (Pixel 11 Pro, je 30 s `gfxinfo`, 12 s Perfetto)
Gleicher Song mit Canvas, wo nicht anders genannt. Nur Zahlen.

| Build / Lage | Janky | 90. / 99. Perzentil | Textur-Uploads |
|---|---|---|---|
| alt, quer | 41,98 % | 105 / 133 ms | 17.843 |
| Canvas-Fix, quer | 0 % | 12 / 16 ms | 9 |
| Canvas-Fix + Glas + Feder, hochkant | 66,41 % | 133 / 150 ms | 13.832 |
| dieselbe Build, hochkant, Song ohne Canvas | 3,44 % | 16 / 81 ms | 9.116 |
| dieselbe Build, hochkant, Zeilen-Blur aus | 2,03 % | 13 / 97 ms | 10 |

### Ursachen
1. Das Canvas-Video lief unter dem Songtext weiter (jedes Video-Bild ein neues
   Fenster-Bild).
2. Hauptursache hochkant: Der Blur pro Zeile der Bibliothek (RenderEffect) läuft
   jeden Frame. `gfxinfo`: GPU-Cache 273 MB, 0 Byte freigebbar, 213 temporäre
   Oberflächen (169 MB). Läuft der Cache über, wird jede gerasterte Textzeile
   jeden Frame neu hochgeladen.

### Fix
- Canvas-Video läuft nur, wenn es sichtbar ist.
- Zeilenwechsel als eine Feder wie auf dem iPad (Dämpfung 0,78, Response 0,5 s).
- **Kein Zeilen-Blur** im Enhanced-Modus. Versucht wurde ein einziger Durchgang über
  die ganze Liste (Runtime-Shader, zwei Blur-Stufen): hochkant 100 % Janky, 50./99. Perzentil 73/85 ms, GPU-Cache 317 MB, 0 freigebbar – schlechter
  als Blur pro Zeile. Das stoppte auch den ersten Release-Lauf von v1.0.8.

### Nachher (Release v1.0.8, Pixel 11 Pro, hochkant, 30 s, 120-Hz-Panel)
| | vorher (Blur pro Zeile) | v1.0.8 mit Canvas | v1.0.8 ohne Canvas |
|---|---|---|---|
| Janky | 66,41 % | **0,00 %** (0 von 2.661) | 0,15 % (4 von 2.606) |
| 50./90./95./99. Perzentil | 77/133/150/150 ms | 8/10/11/14 ms | 9/11/12/18 ms |
| Langsame Bitmap-Uploads | 504 | 0 | 0 |
| Textur-Uploads im 12-s-Trace | 13.832 | 4 | 4 |
| GPU-Cache (davon freigebbar) | 273 MB (0) | 286 MB (37 MB) | 276 MB (43 MB) |

Ziel „0 % Janky beim normalen Zeilenwechsel“ mit Canvas erreicht. Wort-Sync und
Übersetzung wurden dabei nicht gezielt geprüft.

### Entscheidung
Der Standardmodus bleibt ohne Zeilen-Blur („sieht clean aus“, Rückmeldung nach dem
Test); deshalb weicht er hier bewusst vom iPad ab. Der Schalter „Textzeilen
verwischen“ erscheint nur noch im V2-Modus, der weiter pro Zeile verwischt und nicht
gemessen wurde. Der Code des Ein-Durchgang-Versuchs ist entfernt.

## Runde 11 – weitere Funde beim Gerätetest
- **Crash Onboarding (v1.0.1–v1.0.7):** letzte Seite suchte die entfernte
  Spendenaktion per `.first`. Behoben; `pm clear` + Onboarding ohne Login + Start,
  Suche, Abspielen, Bibliothek, Einstellungen auf dem Pixel ohne Absturz.
  Instrumentation-Test `OnboardingWithoutLoginTest` läuft in der CI auf dem Emulator.
- **Abspielen aus der Suche (mit Konto):** Eine leere Queue-Antwort leerte den
  Player. Der Song läuft jetzt weiter, Empfehlungen füllen auf (geprüft: 18 Songs).
  Vermutete Ursache der leeren Antwort: Antwortformat bei Konten, das der
  Parser nicht kennt (nicht belegt).
- **Rote Navigation:** Glas mischte ohne Song die eingebaute Standardfarbe bei.
- **KI-Sync, erster Lauf:** 169 s Audio in 116,1 s (Schnell, 6 Threads), 0 Wörter.
  Schwelle „keine Sprache“ aus, Sprache aus dem Songtext, zweiter Versuch mit
  genauem Modell. Danach: 207 s Audio in 905,2 s (Schnell, Sprache en, 6 Threads,
  Akku-Betrieb, Gerät warm), 244 Wörter (je 30 s: 0, 66, 18, 61, 15, 64, 20),
  Kalibrierung 0,9985× / +0,22 s / 18 Anker. Erkennung geht, aber zu langsam →
  keine Temperatur-Wiederholungen mehr (`temperature_inc = 0`). Danach am Strom, mit
  Musik und Canvas im Vordergrund: 169 s Audio in 407,5 s (2,4-fache Songlänge),
  497 Wörter (je 30 s: 0, 83, 76, 165, 124, 49), 55 % der Songtext-Wörter getroffen,
  Ergebnis „Fertig“. Noch deutlich langsamer als Echtzeit; Qualität des
  gespeicherten Timings noch nicht beurteilt.
- **KI-Sync, Compiler-Flags:** `ggml-cpu` jetzt für armv8.2-a+dotprod+fp16 gebaut. Gleicher
  Song, gleiches Ergebnis (497 Wörter, 55 %), aber nicht schneller: 606,3 s mit 5 Threads
  auf Akku, warm (vorher 407,5 s mit 6 Threads am Strom). Thread-Änderung zurückgenommen,
  Zeiten pro Fenster im Log, um Encoder und Decoder zu trennen. Mit 6 Threads am Strom:
  343,9 s, gleiche 497 Wörter. Ein Encoder-Lauf ≈ 1 s, ein Fenster aber 25–125 s, weil
  whisper bei Musik nur wenig vorrückt und neu rechnet; ein Decoder-Schritt schwankt
  zwischen 19 und 911 ms (vermutlich Kern-Wechsel/Drosselung, nicht belegt). Deshalb
  `single_segment`: ein Durchgang pro Fenster. Ergebnis, gleicher Song, am Strom, mit Musik
  und Canvas: **130,5 s (0,77-fache Songlänge), 476 Wörter, 52 % Treffer** (vorher 343,9 s,
  497 Wörter, 55 %). Qualität des gespeicherten Timings weiter nicht beurteilt.
- **Beobachtet, nicht geklärt:** Einmal schluckte die laufende App alle Berührungen
  (Tasten gingen, ein zweites Resona-Fenster war offen, die Benachrichtigungsleiste
  hatte den Fokus); erst ein Neustart half. Nicht reproduziert. Trat auf, als das
  Menü-Sheet nach schnellen adb-Eingaben offen blieb.
- **„Radio starten“** kam in demselben Zustand nicht an; davor funktionierte es.
- „Play Date“ (Melanie Martinez): „Dieses Video ist nicht verfügbar“, reproduzierbar
  nur bei diesem Titel.
- **Glas:** eine Komponente für Miniplayer und Navigation; alle `AlertDialog`s über
  eine Hülle; Fenster-Blur ab Android 12; Sheets mit Tönung. Auf dem Pixel gesehen:
  Menü-Sheet, KI-Dialog, Einstellungs-Dialog, Navigation ohne Song neutral
  (`screens-r11/d2-*.png`). Nicht geprüft: Android unter 12 (Rückfall ohne Blur).
- **Lyrics-Messung:** Der Testsong hat Wort-Sync; Übersetzung war nicht aktiv (eine
  KI-Übersetzung ist ohne API-Schlüssel nicht testbar).

## Runde 11 – KI-Sync schneller (Messungen auf dem Pixel 11 Pro)

Testsong 169 s, 59 Zeilen mit Zeit (Englisch). „Treffer“ = Anteil der Songtext-Wörter, die
der Aligner findet; „Zeilenfehler“ = Median des Abstands zur Zeile des Anbieters. Nur
Zahlen, kein Text. Messmodus: Datei `aisync-bench.txt` auf dem Gerät (nur in wip-Builds).

### Ursache der Langsamkeit
Der Encoder braucht pro 30-s-Fenster nur 0,8–2 s. Die Zeit ging fast ganz in den Decoder:
150–650 ms pro Token statt ~10 ms. ggml startet ohne OpenMP für jeden Graphen neue
Threads; der Decoder rechnet einen kleinen Graphen pro Token, die Threads starten kalt auf
langsamen Kernen (Tensor G6: 2× 2,65 GHz, 4× 3,38 GHz, 1× 4,11 GHz). Mit einem Thread:

| Variante (Modell base, 6 Fenster à 30 s) | Laufzeit | × Songlänge | Wörter | Treffer | Zeilenfehler | Temp. |
|---|---|---|---|---|---|---|
| bisher, 6 Threads (Akku) | 147,1 s | 0,87 | 476 | 52 % | 2,22 s | 25,8→32,0 °C |
| 5 Threads (Strom) | 268,3 s | 1,59 | 476 | 52 % | 2,22 s | 32,0→34,9 °C |
| 4 Threads (Strom) | 356,7 s | 2,11 | 476 | 52 % | 2,22 s | 34,9→36,7 °C |
| q5_1, 4 Threads | 608,7 s | 3,60 | 581 | 62 % | 3,02 s | 36,7→38,4 °C |
| q8_0, 4 Threads | 112,5 s | 0,66 | 472 | 58 % | 3,07 s | 38,4 °C |
| **1 Thread** | **22,6 s** | **0,13** | 476 | 52 % | 2,22 s | 35,9 °C |
| 2 Threads | 44,0 s | 0,26 | 476 | 52 % | 2,22 s | 35,9→37,1 °C |
| Vulkan-GPU, 1 Thread (erster Lauf, Shader-Aufbau) | 47,5 s | 0,28 | 475 | 52 % | 2,22 s | 37,1→38,1 °C |
| Vulkan-GPU, 4 Threads | 30,8 s | 0,18 | 475 | 52 % | 2,22 s | 38,1 °C |

Ergebnis gleich, nur die Zeit ändert sich: **7,5-fach schneller als der Song** (22,6 s für
169 s) mit einem Thread – das Ziel „3–5× schneller“ ist damit erreicht. Eingebaut: whisper.cpp
bekommt für den Decoder eine eigene Thread-Zahl (1), der Encoder bis zu 4 (Patch beim Bauen,
`app/src/main/cpp/CMakeLists.txt`). **Diese Kombination ist noch nicht gemessen**; die
22,6 s gelten für einen Thread insgesamt.

- **Quantisierte Modelle (q5_1/q8_0):** nicht übernommen – mehr Wörter, aber größerer
  Zeilenfehler (3,0 s statt 2,2 s); q5_1 war mit dem alten Thread-Problem sogar langsamer.
- **Vulkan (GPU, PowerVR):** gemessen und wieder ausgebaut. ggml fand auf dem Pixel kein
  GPU-Gerät (die Läufe liefen also auf der CPU), die APK wurde 50 MB größer und hätte unter
  Android 10 gar nicht geladen.
- **VAD (Silero) und Fenster entlang der Lyrics-Zeilen:** Die Messreihe lief auf einem nur
  teilweise geladenen Song (111 s statt 169 s, 31 Zeilen) und ist deshalb nicht mit der
  Tabelle vergleichbar. Tendenz: VAD erkannte nur 21 s Gesang (17 % Treffer) – auf Musik
  ungeeignet, nicht übernommen. Lyrics-Fenster: 0,91 s Zeilenfehler, 61 % innerhalb 1 s
  (bester Wert), Wiederholung auf dem vollständigen Song steht aus.
  Teilweise geladene Songs werden jetzt nicht mehr synchronisiert („nicht im Cache“).
- **CTC-Forced-Alignment (wav2vec2, ONNX):** eingebaut (Viterbi mit synthetischen Tests,
  gleicher Aligner), der erste Lauf stürzte im Release ab (R8 entfernte Klassen von ONNX
  Runtime, behoben). Messung siehe unten.

### Zweite Messreihe (decoder-Patch drin, gleicher Song, Akku, 36 °C)

| Variante | Laufzeit | × Song | Wörter | Treffer | Zeilenfehler | ≤ 1 s | Kalibrierung |
|---|---|---|---|---|---|---|---|
| Whisper, Encoder 4 / Decoder 1 Thread | 11,2 s | 0,07 | 476 | 52 % | 2,22 s | 32 % | keine |
| Whisper, Encoder 2 / Decoder 1 | 13,8 s | 0,08 | 476 | 52 % | 2,22 s | 32 % | keine |
| Whisper, Encoder 1 / Decoder 1 | 18,9 s | 0,11 | 476 | 52 % | 2,22 s | 32 % | keine |
| Whisper, Encoder 6 / Decoder 1 | 12,5 s | 0,07 | 476 | 52 % | 2,22 s | 32 % | keine |
| Whisper, Fenster entlang der Lyrics-Zeilen | 17,2 s | 0,10 | 471 | 72 % | 1,19 s | 44 % | 1,0144 / −0,20 s / 9 |
| Whisper + VAD (Silero) | 70,4 s | 0,42 | 265 | 59 % | 1,63 s | 37 % | 0,9872 / +1,08 s / 14 |
| **CTC wav2vec2 int8, CPU 4 Threads** | **10,2 s** | **0,06** | 281 | 72 % | **0,07 s** | **79 %** | **0,9979 / +0,07 s / 16** |
| CTC über NNAPI | 12,7 s | 0,08 | 275 | 70 % | 0,08 s | 91 % | 0,9980 / +0,03 s / 21 |
| Whisper E4/D1 am Ende (Drift) | 22,3 s | 0,13 | 476 | 52 % | 2,22 s | 32 % | keine |

„Zeilenfehler“ misst den Abstand zu den Zeiten des Anbieters (die einzige Referenz), nicht
zur echten Gesangszeit. Entscheidung: **CTC ist Standard**, wenn der Songtext bekannt und in
lateinischer Schrift ist (≥ 80 % der Buchstaben kennt das Modell, Umlaute werden zu a/o/u);
Modell 95 MB, einmal geladen. Liefert CTC keine Kalibrierung bzw. weniger als 30 % Treffer,
läuft Whisper (base, Encoder ≤ 4 Threads, Decoder 1 Thread). Whisper bleibt außerdem für
„Lyrics aus Audio“. VAD ist auf Musik langsamer und schlechter – nicht übernommen. NNAPI ist
etwas genauer (91 % ≤ 1 s), aber langsamer und auf anderen Geräten unsicher – CPU bleibt.
Nicht gemessen: deutsche Songs (das Modell ist englisch), andere Geräte.
- **Fortschritt und Teilergebnisse:** Der Dialog zeigt „Abschnitt x von y · n Wörter“; das
  Ergebnis bis dahin erscheint live im Songtext (nur Vorschau, gespeichert wird erst mit
  „Speichern“).
- iPad-Vergleich: Das iPad nutzt WhisperKit `openai_whisper-base` auf der Neural Engine; ein
  Messwert für denselben Song liegt noch nicht vor.

## Runde 12 – Lautsprecher, Downloads als Dateien, PS5-Stick (Release v1.0.12)

### Lautsprecher-Auswahl
Knopf in der unteren Player-Leiste zwischen Sleep-Timer und Songtext (wie auf dem iPad);
bei externer Ausgabe Pille mit Icon + Name, im Mini-Player Icon + Name. Sheet im Glas-Look:
aktuelle Ausgabe, „Dieses Gerät“, gekoppelte Bluetooth-Audiogeräte (Verbunden / In Reichweite
/ Gekoppelt), „Ausgabe wählen“ (System-Ausgabeauswahl), „In der Nähe suchen“ (Bluetooth-Suche,
nur Audio-Geräte), Antippen koppelt (`createBond`) und verbindet. Die Bluetooth-Erlaubnis wird
beim ersten Öffnen mit kurzer Erklärung erfragt; ohne sie geht alles außer Liste und Suche.
Direkt verbinden (`BluetoothA2dp.connect`) ist seit Android 13 System-Apps vorbehalten – dann
öffnet Resona die System-Ausgabeauswahl, wo ein Tipp verbindet. Shizuku (auf dem Pixel
installiert) wurde geprüft: Es gibt keinen Shell-Befehl zum Verbinden; der Weg über interne
Bluetooth-Schnittstellen ist nicht eingebaut.
Auf dem Pixel geprüft (ohne Lautsprecher): Sheet, „Bluetooth ist aus / Einschalten“,
System-Ausgabeauswahl öffnet sich. **Nicht geprüft:** Suche, Koppeln, Verbinden mit einem
echten Lautsprecher/Echo Dot.

### Downloads als Dateien
- Fertige Downloads werden zu .m4a: AAC-Quellen werden nur umverpackt (keine Verluste,
  YouTube liefert AAC mit 128 kbit/s), Opus/WebM wird mit MediaCodec zu AAC-LC 256 kbit/s.
  Tags: Titel, Künstler, Album, Tracknummer (wenn bekannt), Cover.
- Ordner: komplett heruntergeladene Playlists als `<Playlist>/NN - Künstler - Titel.m4a` mit
  `playlist.m3u8`, alle anderen Songs in `Songs/`. Jeder Song liegt nur einmal (in einer
  weiteren Playlist steht er in deren m3u8 als `../…`).
- Speicherort: `Android/media/dev.jackderganove.resona/Resona` (in der Dateien-App unter
  Android → media sichtbar). `Music/Resona` geht nicht: Android lässt Apps dort nur
  Audiodateien anlegen, keine `.nomedia`. Wer `Music/Resona` will, wählt den Ordner unter
  „Speicherort“ (Ordnerauswahl des Systems, dort ist `.nomedia` erlaubt).
- Nicht in der Mediathek: `.nomedia` vor der ersten Datei. Geprüft mit
  `content query --uri content://media/external/audio/media --where "_data LIKE '%Resona%'"`
  → „No result found“.
- Manifest `.resona-manifest.json` (Song-ID ↔ Datei); nur Dateien aus dem Manifest werden
  verschoben oder gelöscht. Download löschen → Datei weg; aus Playlist entfernen / Playlist
  umbenennen → Datei bzw. Ordner folgt (Unit-Tests für den Plan).
- Wiedergabe aus der Datei (auf dem Pixel belegt: AAC-Decoder mit 48 kHz statt Opus-Stream),
  die Cache-Kopie wird erst gelöscht, wenn die Datei geschrieben und im Manifest ist; fehlen
  Datei und Cache, wird der Song neu geladen. KI-Sync liest die Datei.
- Einstellungen → Speicher → „Downloads als Dateien“: Schalter (Standard an), Speicherort,
  Fortschritt; bestehende Downloads werden beim ersten Start übernommen (Benachrichtigung
  „x von y Songs“).
- „Nur Downloads“: Chip in Playlists (lokal und online) und in der Suche; eingeschaltet
  zeigen Listen nur heruntergeladene Songs und die Suche sucht in der Bibliothek (offline).
  Download-Symbol an jedem Song gab es schon.
- Beim Test gefunden und behoben: Der erste Build hängte Endungen doppelt an
  (`.nomedia.bin`, `….m4a.m4a`) und löschte die Cache-Kopie zu früh – der Testsong fiel
  dadurch auf Streaming zurück. Ein Song in mehreren, nur teilweise geladenen Playlists landete
  im Ordner der ersten.

### PS5-Stick
- Einstellungen → Speicher → „PS5-Stick“: Stick wählen (Ordnerauswahl, Berechtigung bleibt),
  Playlists wählen, „Flache Struktur“, „Jetzt synchronisieren“ (Fortschritt, Abbrechen).
- `Music/<Playlist>/<Nr> - <Künstler> - <Titel>.m4a`, Namen FAT32-sicher (verbotene Zeichen,
  Länge, gleiche Namen bekommen „(2)“), Manifest `Music/.resona-sync.json` im selben Format.
  Fehlende Songs werden vorher geladen und umgewandelt; Platzprüfung; abgewählte Playlists
  werden nur nach Rückfrage entfernt; fremde Dateien werden nie angefasst; keine `.nomedia`.
- Steckt der bekannte Stick, kommt „PS5-Stick synchronisieren?“ mit Knopf.
- **Auf dem Pixel getestet** (USB-Stick FAT32, Playlist „Winter“, 37 Songs): 36 fehlende Songs
  geladen und umgewandelt, danach 37 Dateien (189 MB) in `Music/Winter/` plus Manifest, Hinweis
  zum sicheren Entfernen. Zweiter Abgleich ohne Änderung: 10 s, keine Datei neu geschrieben
  (Änderungszeiten gleich). Vorher wurden auf Wunsch die 58 alten MP3s in `Music/` und 6
  Fragmente in `LOST.DIR` gelöscht.
- **Offen:** Test auf der PS5 (zeigt sie Unterordner als Playlists/Alben? sonst „Flache
  Struktur“); Benachrichtigung beim Anstecken nicht getestet (Stick steckte schon).

### Lyrics-Tiefe
Mit laufendem Song nicht messbar, ohne Ton zu spielen: Ist der Musik-Stream stumm, legt
Android 17 die Ausgabe in Standby und die Player-Uhr steht. Im Stand (Songtext offen, Song
pausiert, 25 s, hochkant, mit Canvas): ohne Blur 0,09 %, ohne Blur + Inhalt darunter
ausgeblendet 0,00 %, Blur der Bibliothek 0,09 %, mit Ausblenden 0,05 %, eigener Shader (eine
Blur-Stufe) 35 %, zwei Stufen 83 % Janky. Die Shader-Varianten sind damit raus; der Blur der
Bibliothek mit ausgeblendetem Inhalt darunter muss noch mit laufendem Song gemessen werden
(früher: 66 % Janky ohne Ausblenden). Bis dahin bleibt der Standardmodus ohne Zeilen-Blur.

### Mess-Schalter
`aisync-bench.txt` und `lyrics-depth.txt` wirken nur in wip-Builds (`BuildConfig.RESONA_MEASURE`,
gesetzt nur für den Branch wip); Releases ignorieren sie.

## Runde 12 – PS5 spielt die Stick-Dateien nicht ab

Befund (Test an der PS5): Ordner und Songs erscheinen, Abspielen meldet „Etwas ist
schiefgelaufen“; eine iPad-Datei im selben Ordner läuft. Kein Cover, auch nicht beim iPad.
Ein Musikplayer auf dem Handy zeigte den Dateinamen als Titel.

### Vergleich (gleicher Song, ffprobe + Box-Dump)

| | iPad (ffmpeg, läuft) | Android v1.0.12 (MediaMuxer, läuft nicht) |
|---|---|---|
| ftyp | `M4A `, minor 512, `M4A isom iso2` | `mp42`, minor 0, `isom mp42` |
| Box-Folge | ftyp · free · mdat · moov | ftyp · free (3192 B) · mdat · moov |
| Chunk-Offsets | `stco` 32-bit, 2 Chunks (2821 + 2430 Frames) | **`co64` 64-bit, 1 Chunk mit allen 5716 Frames** |
| zusätzlich in moov | – | **`meta` (QuickTime-Keys, `com.android.version=17`), creation_time** |
| Audio | AAC-LC, 44,1 kHz, Stereo, 128 kbit/s (umverpackt) | AAC-LC, 48 kHz, Stereo, 256 kbit/s (aus Opus kodiert) |
| Edit-List / Rollgruppen | `elst`, `sgpd`/`sbgp` | – |
| Tags | `©nam`, `©ART`, `©too` in udta/meta/ilst | `©nam`, `©ART`, `aART`, `©alb` in udta/meta/ilst **und** Android-meta in moov |
| Cover | JPEG baseline 1200×1200, `covr` Typ 13 | JPEG baseline 1080×1080, `covr` Typ 13 |

Fragmente (moof) hatte keine der Dateien; moov liegt bei beiden am Ende. Wahrscheinliche
Ursachen: 64-Bit-Offsets mit einem einzigen Chunk und die zusätzliche Android-meta-Box (ein
Player, der sie zuerst liest, findet dort keinen Titel → Dateiname).

### Neu (Formatversion 2)
Eigener MP4-Schreiber (`M4aWriter`) statt MediaMuxer: `ftyp` `M4A ` (wie iPad), moov **vor**
mdat, keine Fragmente, `stco` 32-bit, etwa ein Chunk pro Sekunde, eine AAC-LC-Spur, Tags nur
in udta/meta/ilst (Titel, Künstler, Album-Künstler, Album, Tracknummer), Cover als JPEG
baseline ≤ 600×600 (`covr` Typ 13). AAC wird weiter nur umverpackt, Opus zu AAC-LC 256 kbit/s.
Die Manifest-Einträge tragen `format`; ältere Dateien werden beim nächsten Abgleich aus sich
selbst neu geschrieben (kein Download) und auf dem Stick ersetzt. Unit-Test
`M4aWriterTest` prüft die Eigenschaften der iPad-Datei.

### Gerätetest Formatversion 2 (15.11., Pixel 11 Pro, v1.0.14, Stick 8A91-18FF)

Befund an der PS5 vorher: iPad-Datei (`Music/XXXTENTACION - changes [2yoIPB8sowA].m4a`) läuft mit
Titel und Künstler, Ordner Winter (Android, Format 0) „Etwas ist schiefgelaufen“.

- Erster Abgleich: alle 37 Winter-Dateien neu geschrieben (aus sich selbst, ≈ 6 s), Manifest-
  Einträge `format` 0 → **2** (37/37; das Feld `version` des Manifests selbst bleibt 1 – das ist
  die Version des Manifest-Aufbaus, nicht des Dateiformats). Alle 37 Dateien per adb geprüft:
  `ftyp · moov · mdat`, Marke `M4A `, kein `moof`, `stco` statt `co64`, `covr` vorhanden.
- Cover-Test angelegt: `Music/Cover-Test/` (a M4A mit `covr`, d MP3 ID3v2.3 APIC, e FLAC
  PICTURE), `Music/Cover-Test b folder.jpg/` (M4A ohne Cover + `folder.jpg`),
  `Music/Cover-Test c cover.jpg/` (M4A ohne Cover + `cover.jpg`).
- Zweiter Abgleich: keine Song- oder Cover-Test-Datei geschrieben (Größen und Zeiten gleich),
  aber das Manifest wurde mit gleichem Inhalt neu geschrieben → behoben (wip a9e434ae3:
  unverändertes Manifest wird nicht mehr geschrieben).

Vergleich derselbe Song („changes“), iPad-Datei gegen neu geschriebene Android-Datei:

| | iPad (läuft auf der PS5) | Android Format 2 (neu) | Android Format 0 (alt) |
|---|---|---|---|
| ftyp | `M4A `, minor 512, `M4A isom iso2` | **gleich** | `mp42`, minor 0, `isom mp42` |
| Box-Folge | ftyp · free · mdat · moov | ftyp · moov · mdat | ftyp · free · mdat · moov |
| mvhd-Zeitbasis | 44100 | 1000 | 10000 |
| moov-Ebene | mvhd, trak, udta | mvhd, trak, udta | mvhd, **meta (Android)**, trak, udta |
| Edit-List | `edts/elst` (Encoder-Vorlauf) | – | – |
| hdlr | `soun` (langer Name) | `soun` „SoundHandler“ | `soun` |
| Audio | AAC-LC, 44,1 kHz, Stereo, 128 kbit/s (ASC `1210…`) | AAC-LC, 48 kHz, Stereo, 256 kbit/s (ASC `1190`) | wie neu |
| esds Objekttyp | 0x40 | 0x40 | – |
| stts | 2 Einträge | 1 | 1 |
| Chunks | `stco`, 2 Chunks (2821 + 2430 Frames) | `stco`, 125 Chunks (≈ 1 s, 46 Frames) | **`co64`, 1 Chunk (5716 Frames)** |
| Rollgruppen | `sgpd`/`sbgp` | – | – |
| Tags (udta/meta/ilst) | `©nam`, `©ART`, `©too`, `covr` | `©nam`, `©ART`, `aART`, `©alb`, `covr` | wie neu, plus Android-meta in moov |
| Cover | JPEG baseline 1200×1200 (340 KB), Typ 13 | JPEG baseline 600×600 (78 KB), Typ 13 | JPEG 1080×1080 (197 KB) |
| Tracknummer `trkn` | – | – (Song ohne Album-Tracknummer) | – |

Übrig gebliebene Unterschiede zur iPad-Datei: moov vorn statt hinten (so gewollt), 48 kHz/256 kbit/s
(Opus-Quelle, umkodiert) statt 44,1 kHz/128 kbit/s, keine Edit-List/Rollgruppen (nur für den
Encoder-Vorlauf, Player ignorieren sie meist), kleineres Cover. Die beiden wahrscheinlichen Ursachen
von vorher (64-Bit-Offsets mit einem einzigen Chunk, zusätzliche Android-meta-Box) sind weg.
**PS5-Test (Emil, 15.11.):** Winter (Format 2) läuft auf der PS5. Cover zeigt die PS5 bei **keiner**
Variante (a M4A `covr`, b `folder.jpg`, c `cover.jpg`, d MP3 ID3v2.3 APIC, e FLAC PICTURE) – die
PS5 zeigt bei USB-Musik keine Cover an. Folgen: Cover-Test-Schalter standardmäßig aus (neuer
Schlüssel, die Test-Ordner verschwinden beim nächsten Abgleich), M4A bleibt Standard, Cover werden
weiter eingebettet (für andere Player), Hinweis im PS5-Stick-Bildschirm „Die PS5 zeigt bei
USB-Musik keine Cover an.“

<!-- device-tests-r12:start -->
### Gerätetests Runde 12 (15.11.2026 16:18, `scripts/device-tests-runde12.sh`)

Build: wip c2b2be1cb (Mess-Build, Version 1.0.15), Pixel 11 Pro, Android 17, am Ladegerät,
Musiklautstärke 2/25 (Telefonlautsprecher). Testsong „Let Me Down Slowly“ (169 s, Wort-Sync,
Canvas – derselbe wie in Runde 11), je 30 s `gfxinfo` mit laufendem Song.

| Lage | Canvas | Variante | Wort-Sync | Übersetzung | Janky | Frames | 90./99. Perzentil |
|---|---|---|---|---|---|---|---|
| hoch | an | standard | an | an | 0.04 % | 1/2554 | 9/11 ms |
| hoch | aus | standard | an | an | 0.04 % | 1/2565 | 9/16 ms |
| hoch | an | blur | an | an | 0.12 % | 3/2594 | 12/18 ms |
| hoch | aus | blur | an | an | 0.08 % | 2/2574 | 12/18 ms |
| hoch | an | blur | aus | an | 0.32 % | 10/3129 | 12/18 ms |
| hoch | an | blur | an | aus | 0.08 % | 2/2577 | 12/19 ms |
| quer | an | standard | an | an | 0.04 % | 1/2600 | 9/16 ms |
| quer | aus | standard | an | an | 0.00 % | 0/2585 | 9/11 ms |
| quer | an | blur | an | an | 0.12 % | 3/2585 | 10/14 ms |
| quer | aus | blur | an | an | 0.00 % | 0/2567 | 10/14 ms |

Entscheidung Lyrics-Blur: Blur schlechtester Wert 0.32 % Janky → **bleibt aus** (Bedingung 0 %).
Einordnung: Der Blur der Bibliothek mit ausgeblendetem Inhalt darunter ist nah dran (0–0,12 %,
90. Perzentil 10–12 ms statt 9 ms; früher ohne Ausblenden 66 %), nur ohne Wort-Sync 0,32 %. Auch
der Standardmodus hat 0,04 % (1 Frame in 30 s) – „0 %“ erreicht nur Querformat ohne Canvas.

Wiedergabe nach 15 min Pause: läuft weiter (+20 s in 20 s); Fehlerzeilen im Logcat: 0
Fehlerprotokoll: von Hand exportiert (Einstellungen → Über → „Fehlerprotokoll teilen“; die
Einstellungslisten sind für uiautomator unsichtbar). Datei „Resona-Fehlerprotokoll.txt“ mit Kopf
„Resona 1.0.15 · Google Pixel 11 Pro Android 17“, 8 Einträge – alle **HTTP 403, Client
WEB_CREATOR, Stufe player, je 4 in 15 s für denselben Song** (14:55 und 15:50, also vor dem
Pausentest; der Pausentest selbst: keine Einträge). **Ursache gefunden:** nach jedem abgelehnten
Stream setzt `recoverFromBadStreamPlayerResponse` die Sitzung zurück und löschte dabei die Liste
der gescheiterten Clients; außerdem enthielt deren Schlüssel den Auth-Fingerabdruck, der sich dabei
ändert → derselbe Client wurde bei jedem der 4 Versuche wieder genommen. Behoben in wip 3057bf593
(Sperre bleibt 10 min pro Song, unabhängig vom Fingerabdruck; nur „Erneut versuchen“ hebt sie auf;
sind alle Clients gesperrt, eine Runde mit allen), Unit-Test `FailedStreamClientTest`.

KI-Sync CTC (Kalibrieren mit Bench-Datei, Song ganz im Cache, Akku-Modus 31 °C): **9,9 s für 169 s
(0,06 × Songlänge)**, davon Inferenz 9,8 s (8456 Frames, 4 Threads), 282 Wörter, Treffer 72 %,
Zeilenfehler Median 0,10 s, 81 % der Zeilen innerhalb 1 s, Kalibrierung Tempo 1,0008 / Versatz
−0,01 s / 17 Anker – wie in Runde 11 (10,2 s, 0,07 s). (Das Skript las die Zeile wegen eines
logcat-Filterfehlers nicht ein – behoben; Wert aus logcat übernommen.)

Songtext-Screenshots nur lokal (`build/device-tests-r12/private/`), nicht in docs/.
<!-- device-tests-r12:end -->
