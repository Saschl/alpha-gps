# Android Wi-Fi remote: first camera test

The experimental screen uses manual Wi-Fi joining and the shared Sony PTP/IP core.
It is available in both Android flavors. It does not fetch Wi-Fi credentials or
automatically join a network yet. Photos stay on the camera.

## Build and open

```bash
./gradlew :app:assembleFossDebug
```

The APK is in `app/build/outputs/apk/foss/debug/`. Alternatively use
`:app:assembleGplayDebug` for the normal Google Play development flavor.

1. Start the a6700's smartphone Wi-Fi connection. Disconnect Creators' App and
   close any Python probe so only Alpha GPS controls the camera.
2. On the camera, choose still-photo, single-shot drive mode and manual focus.
3. Join the camera's Wi-Fi using Android settings. Stay connected if Android warns
   that the network has no Internet access.
4. In Alpha GPS, open the enabled camera's details, then **Wi-Fi remote
   (experimental)**. Enter the same camera IPv4 address used by the successful
   Python probe and tap **Connect**. Allow local-network/Nearby devices permission
   if Android asks.
5. Confirm the displayed connected camera name and that the preview changes when
   moving the camera. Tap **Take photo** once. Check that exactly one photo was
   saved and whether the app says **Camera confirmed capture**.

The selected BLE camera and manually entered Wi-Fi address are not automatically
matched yet. Use the matching camera network; the screen shows the PTP camera name
after connecting. Only one Wi-Fi remote session can run at a time.

## Collect experience

- Record phone model, Android version and camera firmware; whether preview appears,
  its responsiveness, and whether it resumes after taking a photo.
- Try several deliberate single shots. Note actual photos versus capture confirmation.
  An uncertain outcome does not trigger an automatic retry; check the camera first.
- Rotate the phone. The session should remain connected without repeating a shot.
- Use **Disconnect**, reconnect, then leave the screen or background the app. Those
  last two actions close Wi-Fi remote; returning requires an explicit connection.
- Switch off camera Wi-Fi while connected. The screen should report connection
  loss and permit a fresh connection after rejoining.
- Check that BLE GPS still works during and after Wi-Fi use, and that Bluetooth
  shutter monitoring resumes after Wi-Fi remote closes.

If preview stops, disconnect and reconnect. This first version does not restart
the preview stream automatically. Autofocus, automatic camera/network setup and
iOS networking remain outside this Android test slice.

## Local validation

```bash
./gradlew :sharednew:testAndroidHostTest :sharednew:iosSimulatorArm64Test \
  :sharednew:compileKotlinIosArm64 :app:assembleFossDebug :app:compileGplayDebugKotlin
```

Tests cover protocol fragmentation, HTTP chunking/lengths, preview framing and
cleanup, capture cancellation, one-session ownership, BLE shutter exclusion, and
network-loss handling. Simulator/native decoding uses a synthetic JPEG. These
checks do not establish Android camera compatibility; the phone test above does.
