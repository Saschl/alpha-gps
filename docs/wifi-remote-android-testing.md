# Android Wi-Fi remote: camera testing

The experimental screen uses the shared Sony PTP/IP core in both Android flavors.
The maintainer confirmed that Android live preview works with the a6700 through the manual
connection path and subsequently confirmed automatic setup/joining and taking pictures on the tested
phone/a6700 combination. Automatic joining supports Android 10+; Android 8/9 retain manual joining.
Photos stay on the camera.

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
- Leave preview untouched beyond the phone's normal screen timeout. The remote screen now stays
  awake while connecting or connected. Disconnecting or leaving restores the normal timeout.
  Deliberately locking the phone still closes remote.
- Use **Disconnect**, reconnect, then leave the screen or background the app. Those
  last two actions close Wi-Fi remote; returning requires an explicit connection.
- Switch off camera Wi-Fi while connected. The screen should report connection
  loss and permit a fresh connection after rejoining.
- Check that BLE GPS still works during and after Wi-Fi use, and that Bluetooth
  shutter monitoring resumes after Wi-Fi remote closes.

Normal disconnect now stops preview/releases shutter controls, reads whether the camera allows Wi-Fi
shutdown, and sends its supported shutdown control before closing sockets and releasing Android's
network request. The screen reports whether the request was accepted, unavailable or unconfirmed. An
unexpected network loss does not trigger another shutdown attempt. This shutdown behavior still
needs an a6700 hardware test: tap **Disconnect**, check that camera Wi-Fi ends, then try **Connect
automatically** again and confirm BLE GPS continues to work.

Android chooses the subsequent phone network; the app does not change saved networks. Credentials
are read anew for each attempt and never stored in app preferences, session state or diagnostics.

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

## Browse existing camera photos (new; hardware validation pending)

For cameras advertising the version-3 PTP media-server capability, also test the
separate MTP session flow (added 2026-10-18): connect for remote shooting, open
**Browse camera photos**, download one existing JPEG, then return with **Back to
remote shooting**. Logs should show `RemoteShooting → ImageTransfer → RemoteShooting`
initialization, while the phone remains on the camera network. Confirm live view
and shutter work again. Repeat browser entry and disconnect while browsing; final
disconnect must not reopen a shooting session. Saved-photo markers reset when
leaving this browser because handles can change between MTP sessions. If entry or
exit fails, retry or return to shooting; preview must not start while still in
transfer mode. This does not establish which transfer backend the ZV-1 advertises.

1. Connect to the a6700 normally. Tap **Browse camera photos**. Live view should stop while the
   camera's card is browsed; this must not take a photo.
2. Check that the newest photos appear first, and compare filenames and thumbnails against existing
   photos on card slot 1. Try **Next**, **Previous**, and **Refresh** with more than 100 captures on
   the card. RAW/JPEG and RAW/HEIF pairs should each have one thumbnail, using the RAW preview. Tap
   a thumbnail to see its capture time and each original's filename, format and size. Check that
   pairs stay together on page boundaries and that missing previews still leave a selectable tile.
   Movie records must not corrupt parsing or appear as photo downloads.
3. Select JPEG in the details sheet and download it. The other format in a pair must remain
   downloadable after JPEG is marked Saved. Check the JPEG in the phone's
   `Pictures/Alpha GPS` album and compare its file size and hash with the card original. Repeat with
   ARW and HEIF where available (phone gallery support varies). Originals must remain on the camera.
4. Tap Download repeatedly: only one download should run. A completed item is marked Saved for this
   connection. A new connection can download another copy; existing phone files must not be
   overwritten.
5. Cancel a large download midway, then download again. Check that no incomplete image appears in
   the gallery and that the connection remains usable. Repeat with insufficient phone storage,
   camera network loss, leaving the screen and intentional backgrounding. On Android 8/9, deny
   storage permission once, then grant it and retry. Android 10+ should not request broad
   photo/storage access.
6. Rotate during browsing and downloading. No new download or connection should start. Return with
   **Back to remote shooting**; live view should restart and a deliberate shutter press should still
   work. Disconnect afterward and verify camera Wi-Fi shutdown and BLE GPS continuity.
7. If possible, change/remove the card between browsing and downloading. Refresh after an error; the
   app must not publish an image from stale metadata.

Current bounds: 20 photos/page, at most 100,000 cached records, 512 MiB/file, 512 KiB transfer
chunks, five-minute download deadline. The version-4 browser currently targets card slot 1. No
background transfer, batch download, automatic post-capture saving, or iOS networking/storage
integration is included.
