# ULTRON Neo — Handy-App und optionale PC-Relay

Die Android-App kann ein DJI-Fly-Video direkt auf dem Handy in eine farbige 3D-Punktwolke umrechnen und anzeigen. Für diesen Schritt braucht sie weder PC, WLAN noch Administratorrechte. Die bestehende PC-Relay bleibt optional für Live-Bild, Mikrofon und das Übertragen großer Dateien.

## Auf dem Handy verwenden

1. Installiere die ULTRON-APK auf Android 10 oder neuer.
2. Übertrage nach dem Flug das Originalvideo aus DJI Fly auf das Handy.
3. Öffne ULTRON und tippe auf **DJI-Fly-Video auf Handy in 3D umwandeln**.
4. Wähle das MP4-Video aus. ULTRON verarbeitet die Bilder lokal und zeigt den Fortschritt in der App.
5. Nach der Berechnung kannst du die Punktwolke mit einem Finger drehen und mit zwei Fingern zoomen. Die PLY-Datei wird unter **Downloads/ULTRON** gespeichert.
6. Kopiere die PLY-Datei bei Bedarf per USB-Kabel auf den PC oder teile sie über die Android-Teilen-Funktion. Am Handy kannst du sie später mit **Gespeicherte 3D-Punktwolke öffnen (.PLY)** wieder öffnen.

Die App nutzt OpenCV direkt auf Android. Für die Berechnung werden bis zu 60 verkleinerte Videobilder ausgewertet. Eine Internetverbindung ist für die Berechnung nicht nötig; sie wird nur zum Herunterladen/Installieren der App gebraucht.

## Aufnahme für bessere Ergebnisse

- Fliege langsam und filme dasselbe Objekt aus mehreren überlappenden Blickwinkeln.
- Halte Abstand und Bewegung möglichst gleichmäßig und vermeide schnelle Schwenks.
- Strukturen und Kanten helfen der Berechnung. Wasser, Glas, glatte Flächen, wenig Licht und bewegte Motive liefern wenig stabile Punkte.
- Eine gerade Vorwärtsfahrt oder reine Drehung liefert oft zu wenig Tiefeninformation.

Die lokale Handy-Version erstellt eine **sparse farbige 3D-Punktwolke** aus erkannten Bildmerkmalen. Sie ist drehbar und als PLY in Blender und anderen 3D-Programmen importierbar. Sie ist keine geschlossene, texturierte Mesh-Oberfläche und keine GPS-Karte. Die rekonstruierten Maße sind relativ und nicht in Metern kalibriert.

## Android APK bauen

Öffne `android-relay` in Android Studio, synchronisiere Gradle und wähle **Build > Build APK(s)**. Der GitHub-Actions-Workflow **Build Android relay APK** baut die Debug-APK und ein Quellcode-ZIP als `ultron-relay-downloads`-Artefakt.

Die lokale 3D-Berechnung verwendet `org.opencv:opencv:4.10.0`; die native Android-Bibliothek wird mit der APK ausgeliefert. Die App verwendet `MediaMetadataRetriever`, um verkleinerte Bilder direkt aus dem gewählten Video zu lesen.

## Optionale PC-Relay

PC und Handy müssen für Relay-Funktionen im selben privaten WLAN oder persönlichen Hotspot sein. Auf dem PC werden Node.js 20+ und `bash start-ultron-no-admin.sh` verwendet; der Launcher richtet FFmpeg und COLMAP im Benutzerkonto ein, ohne `sudo`.

Öffne `http://127.0.0.1:8787` auf dem PC und kopple die ULTRON-App per QR-Code. Die PC-Kopplung wird nur für den DJI-Fly-Bildschirmstream, das Handy-Mikrofon und optionalen Video-Upload gebraucht. Sie ist nicht Teil der lokalen 3D-Berechnung.

Screenstreaming überträgt nach Android-Freigabe den Bildschirm von DJI Fly. Das Mikrofon sendet das Handy-Mikrofon; es nimmt keinen Ton direkt aus Neo oder Goggles auf. ULTRON steuert die Drohne nicht. Flugsteuerung bleibt bei der DJI-Fernsteuerung.
