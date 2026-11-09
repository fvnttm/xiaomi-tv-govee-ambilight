# Camera-free ambilight: Xiaomi Google TV + WLED

A Google TV app that drives an addressable LED strip behind the TV from the picture that is actually on screen:
no camera, no PC, no HDMI capture box. The strip is run by [WLED](https://kno.wled.ge) on an ESP32, and the app
sends it colours over the local network with DDP.

Instead of taking screenshots, the app reads pixels from the **MediaTek display pipeline** (the `IRender` HAL).
That is the finished picture going to the panel: apps, 4K video and **HDMI inputs** (a console, for example). It
costs no GPU time, so playback doesn't slow down.

## Hardware it was tested on

| | |
|---|---|
| TV | Xiaomi TV S Pro Mini LED 65 (2026, "ladybird"), MediaTek MT9689, Google TV / Android 14 |
| Controller | ESP32-C3 running WLED 16.0.1 |
| Strip | 72 addressable LEDs, mounted on all 4 sides |

Other TVs with MediaTek chips and the `vendor.mediatek.hardware.render.IRender` service may work, but haven't
been tried.

## How it works

1. **Starts on its own.** `BootReceiver` starts `AmbilightService` when the TV boots and after app updates. When
   the TV goes into standby, the service sends a black frame and switches WLED off; when the TV wakes, it carries on.
2. **Permissions without a PC.** The service connects to the TV's own ADB on `127.0.0.1:5555` (with
   [dadb](https://github.com/mobile-dev-inc/dadb)) using its own key, "Ambilight@TV", and starts `PixelDaemon` as
   the `shell` user with `app_process`. Only a process like that may call the render HAL.
3. **Reading the picture.** `PixelDaemon` calls `IRender`:
   - `OpenController` (code 69);
   - `GetPixelInfo` (code 49) for points near the edges, one point in the middle of each of 14 zones round the
     picture;
   - `CloseController` (code 5).
4. **Colours.** `AmbilightService` turns the 14 zone colours into the strip's colours:
   - a little extra saturation and a gamma lift for dark scenes;
   - an optional subtitle filter for the two middle zones of the bottom edge;
   - temporal easing, faster when brightening than when dimming;
   - smooth (Hermite) interpolation between neighbouring zones for every LED.
5. **Sending.** `Ddp` sends one DDP packet per new reading (about 45–50 per second) to UDP port 4048. It switches
   WLED on and off through its JSON API (`POST /json/state`).

### The catch in `GetPixelInfo`

The hardware **latches the requested position once per displayed frame**. It returns the pixel at the position
latched at the last vsync, which is usually the one from the *previous* call. So every point is asked for twice,
one frame plus 2 ms apart: the second answer always belongs to that point. That allows about 45–50 points per
second. More controllers don't help, because the HAL hands out the same handle and the same latch.

Letterbox and pillarbox bars (2.39:1 films, 4:3 pictures) are detected and skipped. The probes for them are spread
over the normal readings, one per round, every 20 s, so they never stall the stream.

### Why not ordinary screen capture

Measured on this TV with SurfaceFlinger statistics, 8 s per method:

| Method | 1080p video | 4K video | HDMI |
|---|---|---|---|
| MediaProjection / scrcpy (screen mirroring) | every frame goes through the GPU, late frames | **screen drops to ~4 fps** | black picture |
| Small `ScreenCapture.captureDisplay` 64×36 | no impact, 25/s | ~270 ms per capture, screen ~9 fps | black picture |
| `PixelCopy` in your own player | no impact | no impact, ~3/s | — |
| **`IRender.GetPixelInfo` (this app)** | **no impact** | **no impact** | **works** |

## Setup

### WLED

1. Flash WLED to the ESP32 and set the LED count and data pin under *Config → LED Preferences*.
2. Under *Config → Sync Interfaces → Realtime*, leave **Receive UDP realtime** on (it is on by default). DDP
   arrives on port 4048.
3. Give the ESP32 a fixed address (a DHCP reservation on the router).

### The app

The defaults are in `app/src/main/java/local/ambilight/Config.kt`: WLED at `192.168.0.189`, 72 LEDs, offset 3.
There are no screens for the address and the LED count, so **change them there before building** if your setup is
different.

1. On the TV, turn on **Developer options** (Settings → System → About → press "Build" 7 times), then **USB
   debugging and network debugging** (ADB on port 5555).
2. Build and install the app:
   ```bash
   ./gradlew assembleRelease
   adb connect <tv-ip>:5555
   adb install app/build/outputs/apk/release/app-release.apk
   ```
   The release build is signed with your computer's debug key.
3. Open **Ambilight** from the app list. The TV asks whether to allow debugging for **Ambilight@TV**: tick
   **"Always allow"** and press OK. The app grants itself the notification permission through ADB.

## Calibration

The app's screen shows a preview of the colours round the screen and the state of the picture reading, the strip
and ADB. The app's labels are in Polish.

- **Test segmentów** (segment test, 60 s) lights picture zone 1 red, zone 2 yellow (it shows the direction),
  zone 8 green and zone 14 purple; the rest of the strip glows dim blue. The pattern goes through the same
  direction and offset as the picture, so when the settings are right, the colours appear next to the numbers 1, 8
  and 14 on the preview. Zone 1 is the bottom of the left edge.
- **Obraz testowy** (test picture) has coloured edges: left red, top green, right blue, bottom yellow.
- **Kierunek taśmy** (strip direction), **Układ** (3 or 4 sides) and **Przesunięcie** (offset, ±1 LED) match the
  mapping to how the strip is mounted.
- **Filtr napisów** (subtitle filter) stops white and yellow subtitles from lighting the middle of the bottom edge.
  It works with 4 sides only.

## Limitations

- **Speed.** About 45–50 picture points per second (a hardware limit), so each of the 14 zones is read about 3
  times a second. The easing in between keeps changes smooth; scene cuts show after roughly 0.2–0.4 s.
- **ADB.** Network debugging has to stay on. This TV turns it back on by itself after a restart.
  - Android withdraws the approval for a key after 7 days without use. If the TV hasn't been on for over a week,
    the approval dialog comes back.
  - You can turn that expiry off like this (weaker security):
    `adb shell settings put global adb_allowed_connection_time 0`.
- **Security.** Port 5555 is open on the local network; a new device always needs approval on the TV screen. The
  WLED JSON API is plain HTTP on the local network, so the app allows unencrypted traffic.
- **UDP.** A lost packet is simply replaced by the next one, about 20 ms later. If the stream stops, WLED goes
  back to its own state after its realtime timeout (2.5 s by default); the app switches WLED off in standby, when
  Ambilight is turned off and when the picture can't be read.
- **Strip length.** Frames longer than 480 LEDs are split into several DDP packets; only 72 LEDs have been tried.
- **Not tested:** apps with DRM (Netflix, Disney+), HDR and Dolby Vision content.
- **Undocumented interfaces.** The MediaTek interfaces aren't public. A TV software update may change them; the
  strip then stays off and the app shows the problem.

## Diagnostics

```bash
adb logcat -s Ambilight          # state: picture reading, strip, ADB, problems
```

`PixelProbe` reads the given points with the given delay, which helps with another TV model. Turn Ambilight off in
the app first, so the daemon isn't using the same latch:

```bash
APK=$(adb shell pm path local.ambilight | cut -d: -f2)
adb shell "CLASSPATH=$APK app_process /system/bin local.ambilight.PixelProbe 50 3 100,540 3700,1080"
```

## DDP packet (as used here)

UDP to port 4048. A 10-byte header, then 3 bytes (R, G, B) per LED.

| Byte | Value |
|---|---|
| 0 | `0x41`: version 1, push (`0x40` on all but the last packet of a frame) |
| 1 | sequence number, 1–15 |
| 2 | `0x01`: RGB, 8 bits per channel |
| 3 | `0x01`: destination, the display |
| 4–7 | data offset in bytes, big-endian |
| 8–9 | data length in bytes, big-endian |

## History

The first version drove a Govee H605C (Envisual TV Backlight T2) over Bluetooth. Its BLE protocol topped out
at about 45 commands per second for 14 segments. WLED and DDP support was contributed by
[krzocha1119](https://github.com/krzocha1119), and the project now targets WLED only. The Govee version is in
commit `a3b339d`.

## License

MIT, see [LICENSE](LICENSE). An unofficial project, not affiliated with Xiaomi, MediaTek or WLED. Use at your own
risk.
