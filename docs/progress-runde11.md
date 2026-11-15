# Runde 11 – Abschluss (Android)

Details und Messwerte: `docs/progress-android.md`. iPad: `Resona/docs/progress-runde11.md`.

## Erledigt
- Glas, KI-Sync/Kalibrierung, Onboarding-Crash, Abspielen aus der Suche, Lyrics-Ruckeln mit
  Canvas (0,00 % Janky), deutsche Texte, Erkennungs-Signatur gegen den iPad-Referenzwert.
- KI-Sync schneller: Ursache waren die Threads des Whisper-Decoders (pro Token neu gestartet).
  Jetzt CTC-Forced-Alignment als Standard, wenn der Songtext bekannt ist: 10,2 s für 169 s
  (0,06-fache Songlänge), Zeilenabstand zum Anbieter im Median 0,07 s statt 2,2 s, Kalibrierung
  mit 16 Ankern. Whisper (Decoder 1 Thread) für „Lyrics aus Audio“, nicht-lateinische Texte und
  als Fallback. Vulkan gemessen und verworfen (kein GPU-Gerät gefunden, +50 MB), VAD auf Musik
  schlechter, quantisierte Modelle ungenauer.
- Fortschritt pro Abschnitt im KI-Dialog, Ergebnis bis dahin live im Songtext (nur Vorschau).
- Mess-Schalter nur in wip-Builds.

## Offen
- **iPad-Vergleichswert KI-Sync** für „Let Me Down Slowly“ (Laufzeit, Treffer) – nur auf dem
  iPad messbar.
- **CTC mit deutschen Songs** (englisches Modell, Umlaute werden gefaltet) – nicht gemessen.
- **Lyrics-Tiefe:** Blur der Bibliothek + ausgeblendeter Inhalt darunter mit laufendem Song
  messen (hoch/quer, mit/ohne Canvas, Wort-Sync, Übersetzung). Braucht hörbare Wiedergabe:
  stumm steht die Player-Uhr auf Android 17. Bis dahin ohne Zeilen-Blur.
- **Streams mit HTTP 403** (Android, 14./15.11.): nach langer Pause bzw. bei einzelnen Songs
  „Kein Stream verfügbar, HTTP 403“, „Wiederholen“ half nicht; später ging Streaming wieder.
  Vermutlich derselbe zeitweise Ausfall wie am iPad. Android hat noch kein Fehlerprotokoll wie
  das iPad (v0.8.19).
- Beobachtet, nicht geklärt: Touch-Schlucken nach schnellen adb-Eingaben (einmal), „Play Date“
  nicht verfügbar.
- Glas unter Android 12 (Rückfall ohne Blur) nicht geprüft.
