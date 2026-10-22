# ULTRON Neo Relay

A fresh Linux PC and Android companion project for relaying the DJI Fly screen from a Galaxy phone to ULTRON on a home PC.

The PC receiver stays on loopback. Remote phone traffic is intended to reach it through **Tailscale Serve inside your private tailnet**. Do not expose the port with a router rule or Tailscale Funnel.

## How it works

1. Start the PC relay. Its local page shows a one-time six-digit pairing code.
2. Connect the Linux PC and Android phone to the same Tailscale network.
3. Run `tailscale serve 8787` on the PC and copy the private HTTPS URL it prints.
4. Enter that URL and the PC's pairing code in the Android relay app.
5. Connect the DJI Goggles N3 to the Galaxy phone with a USB-C data cable and open the DJI Fly live view.
6. In ULTRON Relay, tap **DJI-Fly-Bild freigeben** and accept Android's screen-capture prompt. Switch back to DJI Fly. The PC page displays the incoming preview.
7. After the flight, use DJI Fly QuickTransfer to copy the Neo's original MP4 to the phone. In ULTRON Relay choose **Neo-Video für 3D an ULTRON senden**. The PC runs the reconstruction locally and makes a PLY mesh available for download.

The relay does not control the aircraft. Flight control stays with the DJI FPV Remote Controller 3 and goggles.

## Start the PC relay

Requires Node.js 20 or later, FFmpeg, and a current COLMAP command-line installation (`colmap`).

```sh
cd pc-relay
npm start
```

Open [http://127.0.0.1:8787](http://127.0.0.1:8787) on the PC. Keep this process running while using the relay.

Install Tailscale on both devices, sign in to the same tailnet, then run this in another PC terminal:

```sh
tailscale serve 8787
```

Tailscale Serve prints the private HTTPS address. Enter that address in the phone app. Serve requires HTTPS to be enabled for the tailnet and makes the service available only to tailnet devices. See the [Tailscale Serve guide](https://tailscale.com/docs/features/tailscale-serve) and [Linux install guide](https://tailscale.com/docs/install/linux).

## Build the Android app

Open `android-relay` in Android Studio, let it sync the Gradle project, then choose **Build > Build APK(s)**. The repository also includes a manual GitHub Actions workflow named **Build Android relay APK** that produces a debug APK artifact.

The app requires Android 10 or later. Android asks for screen-capture permission whenever a new stream starts. The stream stops when you tap **Stream stoppen**, revoke the Android projection, or stop the PC server. Android's [MediaProjection API](https://developer.android.com/media/platform/av-capture) requires this user approval.

## Current relay limits

- The first version sends screen frames as JPEG, up to 960 pixels on the longest edge and about six frames per second. It is a low-bandwidth preview, not a full-quality DJI video recording.
- The live preview keeps only the latest frame in memory. Original videos selected for 3D processing are saved locally under `pc-relay/media/`; they are not sent to a cloud service. Files can be large (upload cap: 2 GB per video).
- Video-to-3D is post-flight photogrammetry using COLMAP Structure-from-Motion and Multi-View Stereo. It extracts one frame every two seconds, then creates a mesh; it is not real-time reconstruction and does not infer unseen surfaces. The result is an unscaled PLY mesh, not a georeferenced map. A compatible PLY viewer such as MeshLab can open it. See COLMAP's [capture guidance](https://colmap.github.io/tutorial.html) for overlap, texture, and viewpoint recommendations.
- Reconstruction needs enough disk space and may take a long time. Default repository COLMAP builds generally do not include CUDA or AMD ROCm/HIP acceleration, so the CPU may do most of the work. See [COLMAP installation options](https://colmap.github.io/install.html).
- A one-time code expires after five minutes and is limited to eight attempts. Pairing creates a random in-memory token. **Neuen Code anzeigen** on the PC revokes the old phone token.
- The live view is shown in the local PC page. The Android relay app sends only the screen frames.
- DJI lists the Goggles N3 as compatible with DJI Neo and documents a wired smartphone live view. The Galaxy A55 is not in DJI's recommended-device list, so the phone-to-goggles link needs a real-device check.

See [DJI Goggles N3 support](https://www.dji.com/support/product/goggles-n3) and [DJI Fly downloads and compatible devices](https://www.dji.com/downloads/products/goggles-n3).

## Project layout

- `pc-relay/`: local Node.js receiver, pairing code, live-view page, and video-to-3D pipeline
- `android-relay/`: Android pairing app and user-approved screen capture service
- `.github/workflows/build-relay-apk.yml`: manual debug APK build

This is a new relay project. It does not contain the earlier ULTRON desktop app or bundled model assets.
