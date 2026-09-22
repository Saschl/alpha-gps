# Android Wi-Fi remote: camera testing

The experimental screen uses the shared Sony PTP/IP core in both Android flavors.
The maintainer confirmed that Android live preview works with the a6700 through
the manual connection path. Automatic setup/joining is now implemented for Android
10+ and is the next hardware test. Android 8/9 retain manual joining. Photos stay
on the camera.

## Build and open

```bash
./gradlew :app:assembleFossDebug
```

The APK is in `app/build/outputs/apk/foss/debug/`. Alternatively use
`:app:assembleGplayDebug` for the normal Google Play development flavor.

1. Keep the a6700 awake and connected to Alpha GPS by Bluetooth. Disconnect
   Creators' App and close any Python probe.
2. Open the enabled camera's details → **Wi-Fi remote (experimental)** →
   **Connect automatically**. Allow the requested permission and approve Android's
   camera Wi-Fi dialog. On Android 10–12, precise Location permission and the phone's
   Location switch are required for Wi-Fi joining.
3. Expect **Starting camera Wi-Fi**, then Android connection approval/joining,
   then **Connecting to camera**. No IP address or password should be needed.
4. Confirm the displayed connected camera name and that the preview changes when
   moving the camera. Tap **Take photo** once. Check that exactly one photo was
   saved and whether the app says **Camera confirmed capture**. For this shutter
   test, first choose still-photo, single-shot drive mode and manual focus.

If automatic setup fails, record the displayed stage/message. **Manual connection**
still exposes the proven path: start camera smartphone Wi-Fi, join it in Android
settings, enter the IP used with the Python probe, and tap **Connect**. Stay on the
network if Android warns it has no Internet access.

Automatic joining requests the exact SSID and valid optional BSSID read from the
selected BLE camera. It uses that network's IPv4 default gateway as the camera
endpoint. Missing or ambiguous routes produce an error; no fixed address is assumed.
Manual joining still relies on selecting the correct camera network/IP. Only one
Wi-Fi remote session can run at a time.

## Collect experience

- Record phone model, Android version and camera firmware; whether preview appears,
  its responsiveness, and whether it resumes after taking a photo.
- Decline the Wi-Fi join dialog once, then retry. Also cancel while camera Wi-Fi is
  starting. Neither action should open a remote session later without a new tap.
- Try automatic setup starting on home Wi-Fi and starting already on camera Wi-Fi.
  Repeat after disconnecting; Android may remember approval for this app/network.
- Try several deliberate single shots. Note actual photos versus capture confirmation.
  An uncertain outcome does not trigger an automatic retry; check the camera first.
- Rotate the phone. The session should remain connected without repeating a shot.
- Use **Disconnect**, reconnect, then leave the screen or background the app. Those
  last two actions close Wi-Fi remote; returning requires an explicit connection.
- Switch off camera Wi-Fi while connected. The screen should report connection
  loss and permit a fresh connection after rejoining.
- Check that BLE GPS still works during and after Wi-Fi use, and that Bluetooth
  shutter monitoring resumes after Wi-Fi remote closes.

Disconnect releases the app's network request after camera session cleanup. Android
chooses the subsequent phone network; the app does not change saved networks or
switch off the camera's Wi-Fi. Credentials are read anew for each attempt and never
stored in app preferences, session state or diagnostics.

If preview stops, disconnect and reconnect. This first version does not restart
the preview stream automatically. Autofocus and iOS networking remain later work.

## Local validation

```bash
./gradlew :sharednew:testAndroidHostTest :sharednew:iosSimulatorArm64Test \
  :sharednew:compileKotlinIosArm64 :app:assembleFossDebug :app:compileGplayDebugKotlin
```

Tests cover protocol fragmentation, HTTP chunking/lengths, preview framing and
cleanup, capture cancellation, one-session ownership, BLE shutter exclusion,
bootstrap readiness, join refusal/timeout/cancellation, network-request lifetime,
and dropping cancelled queued BLE operations. Simulator/native decoding uses a
synthetic JPEG. These checks do not validate the Android system dialog or camera
BLE startup; the phone test above does.

Platform behavior follows Android's [Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
and [nearby Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).
