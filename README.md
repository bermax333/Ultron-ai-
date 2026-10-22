# ULTRON Neo Relay

A Linux PC relay and Android companion app for the DJI Fly screen and post-flight 3D reconstruction.

## No-admin local Wi-Fi setup

The PC and phone must be connected to the same **private Wi-Fi network** or a personal hotspot. Do not use public Wi-Fi for screen relay. The PC listens on the private LAN; pairing still requires the short-lived six-digit code and creates a random session token.

1. Install Node.js 20+ in your user account.
2. Extract the project folder.
3. Run `bash start-ultron-no-admin.sh`. The first run downloads Micromamba and installs FFmpeg and COLMAP into your home folder; it does not use `sudo`.
4. Open `http://127.0.0.1:8787` on the PC. The page displays a QR code for the PC's private WLAN address.
5. Scan the QR code in ULTRON Relay on the phone. Allow camera access for QR scanning, then allow Android screen sharing when you start the live preview.
6. Connect the DJI Goggles N3 to the Galaxy phone with a USB-C data cable and open DJI Fly.

The QR and phone relay use plain HTTP only to the private LAN address. Use a trusted private hotspot or Wi-Fi. For connections when the phone and PC are on different networks, use Tailscale Serve with HTTPS on both devices; do not expose the relay port through a router rule or public tunnel.

## Flight and 3D model

The relay does not control the aircraft. Flight control stays with the DJI FPV Remote Controller 3 and goggles. Android screen capture streams the DJI Fly display to the PC after the user approves the system prompt.

After the flight, use DJI Fly QuickTransfer to copy the Neo's original MP4 to the phone. In ULTRON Relay choose **Neo-Video für 3D an ULTRON senden**. FFmpeg extracts frames and COLMAP reconstructs a PLY mesh locally. The PC page shows an interactive viewer and offers the mesh for download. This is post-flight photogrammetry, not live 3D reconstruction or completion of unseen surfaces.

For a good reconstruction, film the subject slowly from multiple overlapping angles, with good light and visible texture. The relay samples one frame per second to keep the COLMAP job manageable. Smooth or moving surfaces, fast pans, and short clips reduce quality. The interactive PLY preview loads its Three.js modules from the bundled PC files and works offline.

## Requirements and manual setup

The no-admin launcher requires Node.js 20+, curl, and tar. It installs FFmpeg and COLMAP into `$HOME/.local/share/ultron-neo-env` using Micromamba. No package-manager or administrator access is needed. If Node.js is missing, install it in your user account with nvm (the project requires Node 20+).

To start manually when Node.js, FFmpeg, and COLMAP are already available on `PATH`, run:

```sh
bash start-ultron.sh
```

The dashboard is available on `http://127.0.0.1:8787`; the phone pairing QR uses the LAN address shown by the dashboard. Keep the PC server terminal open.

## Android APK

Open `android-relay` in Android Studio, sync Gradle, then choose **Build > Build APK(s)**. The **Build Android relay APK** GitHub Actions workflow produces the installable debug APK and source ZIP as the `ultron-relay-downloads` artifact.

The app requires Android 10 or later. Android asks for screen-capture permission whenever a new screen stream starts. To send sound, tap **Handy-Mikrofon an ULTRON senden**, allow microphone access, then click **PC-Mikrofon hören** on the PC dashboard. Stop either stream with **Stream stoppen**. The microphone feature sends audio from the phone microphone only; it does not record sound from the Neo or goggles. The QR scanner uses the camera only when you tap **QR-Code scannen**.

## Troubleshooting

- The QR generator and Three.js viewer are bundled and served locally by the PC relay; neither depends on an external CDN. Open the PC dashboard at `http://127.0.0.1:8787`; scan its displayed private-Wi-Fi address from the phone.
- For microphone streaming, pair first, start **Handy-Mikrofon an ULTRON senden** in the app, grant Android microphone access, then click **PC-Mikrofon hören** on the PC. Error messages now appear in the app and on the PC page. This streams the phone's microphone; it does not capture audio from the Neo or Goggles.
- For 3D processing, start the PC with `bash start-ultron-no-admin.sh`, which installs FFmpeg and COLMAP in the user account. Choose the original MP4 after DJI Fly QuickTransfer. Use a slow clip with at least eight clear, overlapping views. The result is a PLY 3D mesh, not a GPS map.

## Privacy and limitations

- The screen preview and original video are sent to the PC on the selected private connection.
- The PC stores uploaded video, extracted frames, and models under `pc-relay/media/`.
- Live view is a relayed screen preview; the app does not control DJI Fly or the Neo.
- Physical pairing with the Neo, Goggles N3, and Galaxy A55 has not been verified.
