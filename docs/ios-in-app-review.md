# iOS in-app review

The device list requests Apple's native rating dialog through
`AppStore.requestReview(in:)`. The Swift host supplies this native call; scheduling,
storage, and Compose lifecycle handling live in `sharednew`.

The iOS schedule is:

- Start a 24-hour grace period on the first foreground visit with a saved camera.
  Existing users also get this grace period when updating to this implementation.
- Wait at least 30 days between requests, with no lifetime request limit.
  StoreKit controls actual presentation frequency. Previously stored lifetime
  request counts are ignored; existing request timestamps retain their cooldown.
- Request only while the app is enabled and the device list is resumed, after a
  two-second settling delay. Leaving the screen or backgrounding cancels the delay.
- If a donation, consent, migration, pairing-error, or location-permission dialog
  competes for attention, defer the review until a later foreground session.
- Require an active scene, key window, and no presented native sheet. Failure to
  find an available window does not consume an attempt. Screenshot modes bypass
  the review flow.

There is no custom rating dialog or review button. StoreKit controls whether a
request displays anything and provides no completion/rating result, so saved
timestamps represent **requests**, not displayed prompts or submitted reviews.
Even a silent StoreKit failure starts the cooldown, but it cannot permanently
exhaust review opportunities.

## Verification

```sh
./gradlew :sharednew:testAndroidHostTest --tests '*review*' \
          :sharednew:iosSimulatorArm64Test --tests '*review*'
```

The common tests cover the grace period, cooldown across more than three requests,
background and camera gates, missing native window, and backward clock changes.
The iOS tests round-trip request history through isolated NSUserDefaults suites
and verify that legacy lifetime counts no longer block requests.

### Visual check without a camera

In Xcode, open **Product → Scheme → Edit Scheme → Run → Arguments** and add the
environment variable `ALPHA_GPS_REVIEW_TEST` with value `1`. Run a **Debug** build
on a simulator or iPhone, complete onboarding and any permission/consent dialogs,
and leave the app enabled. Background and reopen the app on the device list; the
review request runs after the normal two-second settling delay.

This mode bypasses the saved-camera requirement, grace period and cooldown.
It issues at most one native request per launch and does not read or
change persisted review history. Relaunch to test again. Competing dialogs,
foreground/screen checks and native window checks still apply; if another dialog
appears, dismiss it and background/reopen again. Screenshot mode still skips reviews.
Release builds ignore the environment variable. Remove or disable it to restore
normal scheduling.

### Visual check with normal scheduling

For a visual check, use an Xcode development build with a saved camera, complete
onboarding and permission/consent dialogs, and then background and reopen the app.
To avoid waiting a day, set `ios.review.firstEligibleAt` in the test app's
NSUserDefaults to a Unix timestamp at least one day ago, remove
`ios.review.lastRequestedAt`. Return to
the device list without another prompt in that foreground session. A breakpoint
on the Swift `AppStore.requestReview(in:)` call verifies the native request.

Apple disables this prompt in TestFlight. Development builds display the test
prompt; App Store builds remain subject to Apple's own frequency rules. See
[Apple's requestReview documentation](https://developer.apple.com/documentation/storekit/appstore/requestreview(in:)-1q8qs).
