# Release automation

Pushing a `v*` tag runs the Android APK release workflow and the
[Build iOS workflow](../.github/workflows/build-ios.yml). The iOS workflow can also be run
manually from GitHub Actions once it is on the default branch.

The iOS job uses a GitHub-hosted `xcode-27` runner with Xcode 27.0 and JDK 21. It resolves
the pinned Sentry Swift package, supplies its framework path to Gradle, and archives the
`alphagps` scheme in Release for arm64 iOS devices. Xcode's existing build phase builds
the shared Kotlin framework. The job signs the archive, exports an IPA, and uploads the
archive to App Store Connect for TestFlight using Xcode's distribution pipeline.

Configure these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `IOS_CERTIFICATE_BASE64` | Base64-encoded Apple Distribution `.p12`, including its private key. |
| `IOS_CERTIFICATE_PASSWORD` | Nonempty password used when exporting that `.p12`. |
| `IOS_PROVISIONING_PROFILE_BASE64` | Base64-encoded App Store Connect `.mobileprovision` for `com.saschl.cameragps`, created with the same distribution certificate. |
| `APP_STORE_CONNECT_KEY_ID` | Key ID of an App Store Connect team API key. |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer ID shown on the App Store Connect API page. |
| `APP_STORE_CONNECT_PRIVATE_KEY_BASE64` | Base64-encoded `.p8` private key downloaded when creating that API key. |

Export the distribution identity from Keychain Access as a password-protected `.p12`.
Create/download the [App Store provisioning profile](https://developer.apple.com/help/account/provisioning-profiles/create-an-app-store-provisioning-profile)
with the app's Hotspot Configuration and Access Wi-Fi Information capabilities enabled.
On macOS, copy each file's encoded contents into its secret with:

```bash
base64 -i /path/to/Distribution.p12 | pbcopy
base64 -i /path/to/AlphaGPS.mobileprovision | pbcopy
```

Run each command separately and paste into the corresponding secret before copying the next.
The workflow derives the team, profile UUID and certificate fingerprint, checks expiration,
app capabilities and certificate/profile matching, and imports the identity into a temporary
keychain. It removes signing and upload credentials even when the build or upload fails.
All six secrets are required; missing credentials fail the job before the build.

Create the upload key in **App Store Connect → Users and Access → Integrations →
App Store Connect API → Team Keys**, with the **Developer** role for build uploads.
An Account Holder or Admin creates the team key. Save its Key ID and Issuer ID as the
corresponding secrets, download the `.p8` file (available only once), and encode it:

```bash
base64 -i /path/to/AuthKey_YOUR_KEY_ID.p8 | pbcopy
```

Paste that into `APP_STORE_CONNECT_PRIVATE_KEY_BASE64`. See Apple's
[API key setup](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api/)
and [build upload guide](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/).

Download `ios-signed-<commit>-<attempt>` from the workflow run for the zipped
`AlphaGPS.xcarchive` and signed `alphagps.ipa`. Logs and the Xcode result bundle are in the
diagnostics artifact, including on build failure; artifacts are retained for 14 days.
The downloadable archive and IPA retain the project's build number. The upload is a
separate Xcode distribution step with `manageAppVersionAndBuildNumber = true`, so Xcode
can increase the uploaded build number when needed, just like Organizer. For example,
the project may remain at version `1.7.0`, build `170`, while successive uploads receive
higher build numbers. Check TestFlight for the assigned number. GitHub runs are serialized
across tags to avoid overlapping uploads competing for a build number.

The marketing version stays in the Xcode project; tags trigger the workflow without
rewriting versions. Both tag pushes and manual runs upload to TestFlight. The IPA is for
App Store Connect distribution, not direct device installation.

A successful upload still needs Apple's processing before it appears in TestFlight.
For automatic delivery to internal testers, enable **automatic distribution** on the
app's TestFlight internal testing group. Outstanding export compliance questions can
require attention in App Store Connect. External testing may require Beta App Review;
this workflow uploads builds and does not submit an App Store release. See Apple's
[internal testing setup](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers/).

To extend this to store publishing:

1. For iOS, add App Store submission and the chosen release policy after TestFlight testing.
   See Apple's [distribution guide](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases).
2. For Google Play, assign an increasing `versionCode` and build a signed
   `:app:bundleGplayRelease` using the existing keystore
   secrets and grant a service account publishing access to this app. Upload the AAB to
   an internal testing track before adding production rollout. See the
   [Play Developer API setup](https://developers.google.com/android-publisher/getting_started).
