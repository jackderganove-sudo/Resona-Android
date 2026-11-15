# Runde 12 – Abschluss (selbstständig, ohne Rückfragen)

Auftrag: PS5-Fix abschließen, Wiedergabefehler wie iPad, KI-Sync angleichen, CTC mit
Deutsch, Lyrics-Tiefe, Kleinkram, PS5-Stick fürs iPad. Das Pixel war während der ganzen
Runde nicht per adb erreichbar; alle Gerätetests stehen gesammelt unten. Details zu den
früheren Teilen der Runde: `docs/progress-android.md` (Abschnitte „Runde 12“).

| Punkt | Inhalt | Stand |
|---|---|---|
| 1 | PS5-Stick: Formatversion 2, MP3-Ausweichformat, Cover-Test-Ordner | Release **v1.0.13** (CI grün, ntfy gesendet); Gerätetest mit Stick offen; Cover-Test an der PS5 macht Emil |
| 2 | Wiedergabefehler wie iPad v0.8.19 | Release **v1.0.14** (CI grün, ntfy gesendet); auf dem Pixel nicht getestet |
| 3 | KI-Sync iPad = Android (CTC) | nur iPad betroffen, siehe iPad-Repo |
| 4 | CTC mit deutschen Platzhaltertexten | synthetischer Test grün; kein MMS (Entscheidung unten) |
| 5 | Lyrics-Tiefe mit laufendem Song | gemessen 15.11. (10 Varianten): Blur max. 0,32 % Janky → bleibt aus |
| 6 | Glas-Fallback unter Android 12 | CI-Job mit API-30-Emulator (nur wip): startet, kein Absturz, Glas-Fallback lesbar |
| 7 | PS5-Stick fürs iPad | vorbereitet, hinter Schalter, nicht freigegeben |

## 1. PS5-Stick (v1.0.13)

- **Formatversion 2** (eigener `M4aWriter` statt MediaMuxer): `ftyp` `M4A `, moov vor mdat,
  keine Fragmente, `stco` 32-bit, etwa ein Chunk pro Sekunde, AAC-LC, Tags nur in
  udta/meta/ilst (Titel, Künstler, Album-Künstler, Album, Tracknummer), Cover JPEG baseline
  ≤ 600×600 (`covr` Typ 13). Ältere Dateien (Manifest-Feld `format` < 2) werden beim
  nächsten Abgleich aus sich selbst neu geschrieben, ohne Download.
- **Ausweichformat** Einstellungen → Speicher → PS5-Stick → „MP3 statt M4A“: LAME 3.100
  (per CMake geladen, SHA-256 geprüft), 320 kbit/s CBR, ID3v2.3 mit TIT2/TPE1/TPE2/TALB/TRCK
  und APIC (JPEG). Ein Wechsel schreibt die Stick-Dateien beim nächsten Abgleich neu.
- **Cover-Test** (Schalter „Cover-Test-Ordner“, an): beim Abgleich mit dem ersten Song
  `Music/Cover-Test/` mit a) M4A mit `covr`, d) MP3 ID3v2.3 mit APIC, e) FLAC mit PICTURE,
  dazu `Music/Cover-Test b folder.jpg/` (M4A ohne Cover + `folder.jpg`) und
  `Music/Cover-Test c cover.jpg/` (M4A ohne Cover + `cover.jpg`). Abschalten löscht nur diese
  Dateien.
- Unit-Tests grün: `M4aWriterTest` (Box-Folge ftyp·moov·mdat, Marke, kein moof, stco statt
  co64, AAC-LC im esds, Chunk-Zahl, Tags, `covr` Typ 13, keine moov-meta, Dauer),
  `StickFormatsTest` (ID3v2.3-Header, Frames, APIC, UTF-16 für Nicht-Latin, FLAC-Blöcke mit
  PICTURE, STREAMINFO, Vorbis-Kommentar). Das Neuschreiben alter Einträge (`format` < 2) hat
  noch keinen eigenen Unit-Test, nur den Gerätetest unten.
- **Offen (Gerät):** Abgleich mit echtem Stick auf dem Pixel nach dem Update (erwartet:
  alle 37 Winter-Dateien werden einmal neu geschrieben, zweiter Abgleich schreibt nichts);
  Cover-Test an der PS5.

## 2. Wiedergabefehler (v1.0.14, gleiche Logik und Texte wie iPad v0.8.19)

- **Ursache der 403 nach langer Pause:** `PlaybackStreamRecoveryTracker` erlaubte pro Song
  nur einen automatischen neuen Versuch. Nach einer langen Pause ist die Stream-URL
  abgelaufen (erster Versuch: neu auflösen); scheiterte danach noch ein zweiter Client,
  blieb es bei „HTTP 403“. „Erneut versuchen“ setzte den Zähler nicht zurück.
- Jetzt bis zu 4 automatische Versuche pro Song; ein gescheiterter Client wird für diesen
  Song 10 min übersprungen (wie bisher), abgelaufene URLs werden neu geholt.
- „Erneut versuchen“ vergisst die gesperrten Clients und zwischengespeicherten URLs des
  Songs und löst neu auf. Fehlermeldung wie auf dem iPad: „YouTube hat den Stream für alle
  ausprobierten App-Profile abgelehnt. Das legt sich meist nach ein paar Stunden.“
- Fehlerprotokoll (`ResonaPlaybackLog`, letzte 200 Einträge): Zeit, Song-ID, Client, Stufe
  (resolve / validate / player / success), HTTP-Status, geschwärzter Text (URLs → Host +
  itag, `key=value`-Geheimnisse, E-Mail-Adressen, lange Zeichenketten). Einstellungen →
  Über → „Fehlerprotokoll teilen“.
- Unit-Tests: `ResonaPlaybackLogTest` (Schwärzung, HTTP-Status, Grenze 200, Export,
  mehrere Versuche pro Song und Zurücksetzen durch „Erneut versuchen“).

## 4. CTC mit Deutsch

- Gleicher synthetischer Test auf beiden Plattformen (`CtcAlignerTest` /
  `CtcAlignerTests`): erfundener deutscher Text mit Umlauten und ß wird exakt gefunden; das
  englische Modell sieht die gefalteten Buchstaben (ü → U, ß → SS), deutsche Lyrics gelten als
  „passend“ (≥ 80 % bekannte Buchstaben), japanische nicht.
- Ob das englische Modell echten deutschen Gesang gut trifft, kann ein synthetischer Test
  nicht zeigen, und echte Songtexte sind in Tests tabu.
- **Entscheidung: kein MMS-Modell jetzt.** Gründe: (1) Es gibt schon ein Sicherheitsnetz –
  liefert CTC kein brauchbares Ergebnis (zu wenige Treffer, Kalibrierung scheitert), läuft
  automatisch Whisper (mehrsprachig). (2) MMS (wav2vec2 mms-1b) ist ein Vielfaches größer
  (≈ 1 GB statt 95 MB) und langsamer; ohne Messung an echten deutschen Songs wäre das
  geraten. Nächster Schritt, wenn deutsche Songs schlecht synchronisiert werden: auf dem
  Pixel messen (Logzeile „CTC gave no usable result … → Whisper“ zählt mit), dann MMS als
  Option bzw. automatische Wahl nach Sprache.

## 5. Lyrics-Tiefe

Nicht gemessen: Das Pixel war nicht verbunden, und mit stummem Musik-Stream steht unter
Android 17 die Player-Uhr (siehe `progress-android.md`). Bedingung „nur bei 0 % Rucklern“
ist damit nicht belegt → der Zeilen-Blur bleibt aus. Messplan steht unten.

## 6. Glas-Fallback unter Android 12

Lokal ist kein Emulator installiert. Neuer CI-Job `glass-fallback` (nur wip, blockiert
nichts): Release-APK und Onboarding-Test auf einem API-30-Emulator, Screenshots als
Artefakt `glass-fallback-api30`. Im Code: unter Android 12 kein Haze-Blur, Glas = Tönung mit
0,94 Deckkraft, Sheets ohne Cross-Window-Blur.

**Ergebnis (Lauf vom 15.11., API 30, Release-APK v1.0.13-Stand + Debug-APK):** Start ohne
Sperre und ohne Absturz; der Onboarding-Test läuft ohne Login bis zur Startseite und öffnet
alle Tabs (dort liegen Navigationsleiste und Mini-Player im Glas-Look) – „OK (1 test)“, kein
FATAL im Logcat. Der Screenshot am Ende zeigt nur den Onboarding-Start (der Testprozess wird
beendet); ab dem nächsten wip-Lauf macht der Job zusätzlich `home-api30.png` von der
Startseite.

**Startseite auf API 30 (`home-api30.png`, Lauf vom 15.11.):** Navigationsleiste im
Glas-Fallback – helle, fast deckende Tönung (0,94), Inhalte dahinter nur schwach zu sehen,
Beschriftungen gut lesbar, kein Blur und keine Artefakte. Nebenfund: der Debug-Build hieß noch
„ArchiveTune Debug“ (`app/src/debug/res/values/app_name.xml`) → „Resona Debug“. Releases
waren nicht betroffen.

## Gerätetests, gesammelt (Pixel, sobald verbunden)

1. Update auf v1.0.14 installieren (enthält v1.0.13).
2. Stick einstecken, PS5-Stick → „Jetzt synchronisieren“: alle Dateien werden einmal in
   Formatversion 2 neu geschrieben, `Music/Cover-Test*` entsteht; zweiter Abgleich schreibt
   nichts. Danach Stick an die PS5 (Cover-Test: welche der Varianten a–e zeigt ein Cover?).
3. Wiedergabe: Song starten, App > 1 h pausiert lassen, weiterspielen → muss ohne 403
   weiterlaufen; sonst Einstellungen → Über → „Fehlerprotokoll teilen“.
4. Lyrics-Tiefe: Song leise (Lautstärke ≈ 2/25, nicht voll) laufen lassen, je 30 s
   `dumpsys gfxinfo` hochkant/quer, mit/ohne Canvas, Wort-Sync, Übersetzung; nur bei 0 %
   Janky übernehmen.
5. KI-Sync mit einem deutschen Song: Logzeile prüfen, ob CTC genommen oder an Whisper
   abgegeben wurde (nur Zahlen loggen, keine Texte).

## Entscheidungen ohne Rückfrage

- PS5: Cover-Test-Ordner standardmäßig an (damit der Test an der PS5 ohne weiteres
  Umschalten geht); M4A bleibt Standard, MP3 nur per Schalter.
- Wiedergabe: 4 automatische Versuche pro Song (iPad: Client-Kette + einmal neu auflösen;
  Android-Player-Fehler kommen anders an, 4 deckt Hauptclient + 3 Fallbacks ab).
- Kein MMS-Modell (siehe 4).
- Lyrics-Tiefe ohne Messung nicht übernommen.
- Glas-Fallback per CI-Emulator statt lokalem Emulator geprüft.
- iPad-PS5-Stick nur im Entwicklermodus hinter „PS5 stick (preview)“, kein Hinweis im
  Release-Text, bis der Cover-Test an der PS5 gemacht ist.

## EQ pro Ausgabegerät (beide Apps)

- Der EQ wird je Ausgabe gemerkt: Gerätelautsprecher („Dieses Gerät“), Bluetooth-Name
  („Soundcore Space One“, „Echo Dot“), kabelgebunden/USB, auf dem iPad auch AirPlay-Name.
  Erkennung: iPad über die AVAudioSession-Route (`AudioOutputMonitor`), Android über
  `AudioDeviceInfo` (`AudioOutputWatcher`), also dieselbe Quelle wie die Lautsprecher-Auswahl.
- Wechselt die Ausgabe, schaltet der EQ um, kurzer Hinweis „EQ: <Preset> für <Gerät>“ (iPad:
  Pille oben, Android: Toast). Kein Hinweis, wenn sich nichts ändert.
- EQ-Bildschirm oben: „Gilt für: <Gerät>“ mit Geräte-Icon, darunter „Eigener EQ für dieses
  Gerät“ oder „Standard-EQ – Änderungen gelten nur für dieses Gerät“, Schalter „Für alle Geräte
  gleich“, bei eigenem EQ „Standard-EQ verwenden“.
- Regeln (gleich auf beiden, Unit-Tests `OutputEqTests` / `OutputEqTest`):
  - Unbekannte Geräte nutzen den Standard-EQ (beim ersten Start = der bisherige EQ, für
    niemanden ändert sich etwas, bis er etwas umstellt).
  - Eine Änderung im EQ gilt für das gerade aktive Gerät und wird dessen eigener EQ.
  - „Für alle Geräte gleich“ an: alle nutzen den Standard-EQ, Änderungen ändern ihn; der
    gerade gehörte EQ wird beim Einschalten zum Standard. Eigene EQs bleiben gespeichert
    und gelten wieder, wenn der Schalter aus ist.
  - „Standard-EQ verwenden“ vergisst den eigenen EQ des Geräts.
- Entscheidungen ohne Rückfrage: Schalter „Für alle Geräte gleich“ standardmäßig **aus**
  (das Feature ist also an, wirkt aber erst nach der ersten Änderung pro Gerät); der
  Standard-EQ lässt sich nur bei eingeschaltetem „Für alle Geräte gleich“ ändern; Schlüssel
  je Gerät = Art + Name (ohne Bluetooth-Adresse, braucht keine zusätzliche Berechtigung –
  zwei Geräte mit gleichem Namen teilen sich den EQ).
- Android: Änderungen werden über die EQ-Einstellungen erkannt (alles, was der EQ-Bildschirm
  speichert); nach einem Umschalten werden die eigenen Schreibvorgänge 3 s lang nicht als
  Änderung gezählt.
- Getestet: Unit-Tests. Gerätetest mit Kopfhörer am Pixel steht aus (Emil).

## Gerätetest-Skript

`scripts/device-tests-runde12.sh [VIDEO_ID]` (nach dem adb-Connect, prüft einmal und wartet
nicht): installiert den neuesten grünen wip-Build (Mess-Build), stellt die Musiklautstärke auf
2 (danach zurück), misst die Lyrics-Tiefe mit laufendem Song (je 30 s `gfxinfo`; hoch/quer,
Canvas an/aus, Standard vs. Blur der Bibliothek mit ausgeblendetem Inhalt, Wort-Sync und
Übersetzung aus), misst KI-Sync (CTC) über die Bench-Datei, prüft die Wiedergabe nach langer
Pause (Standard 15 min) und den Export des Fehlerprotokolls. Ergebnisse als Tabelle in
`docs/progress-android.md`. Screenshots mit Songtext bleiben in `build/device-tests-r12/private/`,
nach `docs/screens-r12/` kommen nur Player und Teilen-Dialog. Neu in Mess-Builds dafür:
`canvas=0`, `words=0`, `trans=0` in `lyrics-depth.txt` und eine per adb lesbare Kopie des
exportierten Fehlerprotokolls.

## Nachtrag 15.11. (Pixel verbunden)

- PS5-Stick: Format 2 auf dem Stick geprüft (37/37 Dateien neu, Cover-Test vollständig, zweiter
  Abgleich schreibt keine Songs; Manifest wird jetzt auch nicht mehr neu geschrieben). Vergleich mit
  der iPad-Datei in `progress-android.md`. Wartet auf den PS5-Test.
- Gerätetests mit `scripts/device-tests-runde12.sh`: Ergebnisse in `progress-android.md`
  (Abschnitt „Gerätetests Runde 12“). Lyrics-Blur bleibt aus (0,32 % statt 0 %), Wiedergabe nach
  15 min Pause ok, KI-Sync CTC 9,9 s für 169 s.
- Fund im Fehlerprotokoll: derselbe abgelehnte Client (403) wurde 4× hintereinander versucht →
  behoben (wip 3057bf593).
- Gerätebesonderheiten Android 17 Beta: Lautstärke nur über `cmd audio`, Medientasten per adb
  erreichen die App nicht (Telefon-Dienst hält sie), uiautomator braucht pausierte Wiedergabe und
  sieht die Einstellungslisten nicht – im Skript berücksichtigt bzw. von Hand erledigt.
