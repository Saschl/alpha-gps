# iOS in-app review

The device list requests Apple's native rating dialog through
`AppStore.requestReview(in:)`. The Swift host supplies this native call; scheduling,
storage, and Compose lifecycle handling live in `sharednew`.

The schedule matches the current Android review flow:

- Start a 24-hour grace period on the first foreground visit with a saved camera.
  Existing users also get this grace period when updating to this implementation.
- Wait at least 30 days between requests, with a maximum of three requests total
  in the persisted app data.
- Request only while the app is enabled and the device list is resumed, after a
  two-second settling delay. Leaving the screen or backgrounding cancels the delay.
- If a donation, consent, migration, pairing-error, or location-permission dialog
  competes for attention, defer the review until a later foreground session.
- Require an active scene, key window, and no presented native sheet. Failure to
  find an available window does not consume an attempt. Screenshot modes bypass
  the review flow.

There is no custom rating dialog or review button. StoreKit controls whether a
request displays anything and provides no completion/rating result, so saved
counters represent **requests**, not displayed prompts or submitted reviews.

## Verification

```sh
./gradlew :sharednew:testAndroidHostTest --tests '*review*' \
          :sharednew:iosSimulatorArm64Test --tests '*review*'
```

The common tests cover the grace period, cooldown, lifetime cap, background and
camera gates, missing native window, and backward clock changes. The iOS test
round-trips request history through an isolated NSUserDefaults suite.

For a visual check, use an Xcode development build with a saved camera, complete
onboarding and permission/consent dialogs, and then background and reopen the app.
To avoid waiting a day, set `ios.review.firstEligibleAt` in the test app's
NSUserDefaults to a Unix timestamp at least one day ago, remove
`ios.review.lastRequestedAt`, and set `ios.review.requestCount` to zero. Return to
the device list without another prompt in that foreground session. A breakpoint
on the Swift `AppStore.requestReview(in:)` call verifies the native request.

Apple disables this prompt in TestFlight. Development builds display the test
prompt; App Store builds remain subject to Apple's own frequency rules. See
[Apple's requestReview documentation](https://developer.apple.com/documentation/storekit/appstore/requestreview(in:)-1q8qs).
