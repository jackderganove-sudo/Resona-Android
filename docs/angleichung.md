# Angleichung iPad ↔ Android (Runde 11, Teil C)

Gleiche Datei in beiden Repos (`Resona/docs/angleichung.md`,
`Resona-Android/docs/angleichung.md`). Grundlage: Pixel-Screenshots (v1.0.6 /
wip 1.0.7) und iPad-Simulator-Screenshots des Testlaufs v0.8.15.
Regel: bessere Lösung gewinnt, die andere App wird angepasst. Formfaktor-
Unterschiede (iPad-Seitenleiste statt Tab-Leiste, Seitenpanels statt Sheets)
bleiben bewusst.

| Element | Gewinner | Begründung | erledigt |
|---|---|---|---|
| App-Logo im Kopf/Rail | iPad (Resona-Glyph) | ArchiveTune-Zeichen auf Android war ein Überbleibsel | ja – Android v1.0.7 |
| Navigation | beide | Tab-Leiste (Telefon) / Seitenleiste (iPad) je nach Formfaktor | – |
| Chips-Zeile Startseite | gleich | beide YouTube-Music-Chips | ja |
| Quick-Picks-Hero-Karussell | gleich | beide | ja |
| Featured-Playlist-Karten | gleich | iPad seit v0.8.12, Android nativ | ja |
| Gestaffelte Genre-Kacheln | gleich | iPad seit v0.8.12 (nach ArchiveTune), Android nativ | ja |
| Grüner „…“-Schnellaktionsknopf (Musikerkennung, Together, Shuffle) | Android | einziger Zugang zur Musikerkennung; iPad hat keine Musikerkennung | Android bleibt; iPad: offen (Musikerkennung fehlt) |
| Bibliothek-Kacheln | iPad-Namen | „Lieblingssongs“ statt „Songs, die ich mag“ | ja – Android |
| „Was ist neu“ | iPad-Begriffe | Android war auf Deutsch unübersetzt; jetzt „Neu in Resona“, Neu/Verbessert/Behoben | ja – Android |
| Player Standard-Stil | gleich | Android-Player (ArchiveTune V-Design) und iPad sehen schon gleich aus: Cover, Titel + Teilen/Like/…, Wellen-Fortschritt, Pillen-Steuerung, Warteschlange/Sleep/Songtext unten | ja |
| Player Editorial/Immersiv | iPad | auf Android vorhanden als V10 Editorial; Immersiv nur iPad | offen (Android) |
| Codec-Zeile | iPad | „Opus · WEBM • kbps • kHz • MB“ ist genauer als Androids „WEBM • kbps • MB“ | offen (Android) |
| Mini-Player | gleich | Cover, Titel/Künstler, Zurück/Play/Weiter | ja |
| Lyrics-Ansicht | gleich | Kopf mit Cover, ✕ und …, Fortschritt + Steuerung unten | ja |
| Warteschlange | gleich | beide mit Empfehlungs-Chips, Sperren, Auswahl | ja |
| Einstellungen | iPad-Gruppierung | Konto/Verlauf · Aussehen/Wiedergabe/Canvas/Songtext/Inhalt · Integration/Equalizer/Speicher/Über – Android hat mehr Unterseiten | offen (Android) |
| Glas-Look (Mini-Player, Leisten, Sheets, Player) | iPad | Teil D2 | offen |
| KI-Sync/-Kalibrierung | iPad | Teil D1 | offen |
