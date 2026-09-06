# CLAUDE.md

Alpha GPS — Kotlin Multiplatform app that transmits phone GPS to Sony cameras over BLE
(and offers a remote shutter). Android app + iOS app share one KMP module.

## Modules

- `:app` — Android app, package `com.saschl.cameragps` (note the double-l). Native Compose
  screens plus the `LocationSenderService` foreground-service shell. Two product flavors
  (dimension `distribution`): `gplay` (default — GMS fused location, Play in-app review,
  Sentry) and `foss` (F-Droid: platform LocationManager only, no proprietary libs).
  Flavor seams are `src/{gplay,foss}/java`: `createLocationSource()`, `CrashReporting`,
  `launchInAppReviewIfDue()`, `LOCATION_PROVIDER_SELECTABLE` — main code must never
  import `com.google.android.gms|play` or `io.sentry` directly; new proprietary
  integrations get a no-op foss counterpart behind the same signature.
- `:sharednew` — KMP module, package `com.sasch.cameragps.sharednew` (single-l — historical
  inconsistency, do not "fix" casually). Targets: android, iosArm64, iosSimulatorArm64
  (`iosX64` deliberately disabled). Exports the iOS framework `sharedKit`. Contains all
  shared logic AND the entire iOS UI (Compose Multiplatform in `iosMain`).
- `iosApp/` — Xcode project (scheme `alphagps`), not a Gradle module. Swift side is a thin
  shell: `AppDelegate` calls `IosBluetoothController.shared.ensureInitialized()`,
  `ContentView` embeds the shared `MainViewController()`.
- `website/` — Astro site (own npm project). `tools/` — standalone Python scripts, not built
  (`sony_shutter/` BLE helper, `sony_camera_sim/` fake-camera peripheral).
- `:camerasim` — test tool, package `com.saschl.cameragps.sim`: a spare Android phone acting
  as a fake Sony camera (GATT server + advertiser). Debug-only — its release variant is
  disabled so `assembleRelease` skips it. See `camerasim/README.md`.

No DI framework (manual wiring), no KMP BLE library (native transports by design).

## BLE/location architecture (post-restructure, July 2026)

See `docs/common-ble-core-restructure.md` for the full design and rationale.

All orchestration lives in `sharednew/src/commonMain/.../bluetooth/`:

- `transport/BlePeripheralTransport` — the ONLY per-platform BLE contract (initiate ops +
  event flow). Implementations: `app/.../service/transport/AndroidBleTransport.kt`
  (BluetoothGatt, absorbed the old CameraConnectionManager/BluetoothGattUtils) and
  `iosMain/.../IosBleTransport.kt` (CBPeripheral delegate, characteristic cache, pairing gate).
- `transport/BleOperationQueue` — sequential per-device operation lane (15s op timeout,
  30s discovery). **All BLE traffic must flow through it** via `session/QueuedBleGattPort`;
  never write to a characteristic directly from platform code.
- `session/CameraSessionOrchestrator` — session registry/phases (`CameraSessionRegistry`
  StateFlow), transport-event routing, pairing-retry policy (ATT 5/15), one-shot GATT-133
  config-read retry, handshake-complete → location + remote monitoring. It REUSES
  `coordinator/BleSessionCoordinator` (Sony handshake state machine) and
  `coordinator/RemoteControlCoordinator` (probe loop, shutter) — do not fork these per platform.
- `location/LocationTransmissionManager` + `LocationSource` — shared gating/periodic-send;
  platform sources: `app/.../service/location/AndroidLocationSource.kt` (fused + fallback),
  `iosMain/.../IosLocationSource.kt` (CLLocationManager).

Platform shells own only sockets and lifecycle:

- Android graph ownership: `app/.../AppServices.kt` (Application-scoped via
  `CameraGpsApplication`, manual wiring) owns transport, location source, DAO and the
  orchestrator on its own never-cancelled `Main.immediate` scope, and tracks the
  pairing-failed UI state. The UI observes `orchestrator.sessions` via
  `AppServices.from(context)`. One-time logging/Sentry/crash-handler init lives ONLY in
  `CameraGpsApplication.onCreate` — do not re-add per-component init blocks.
- `LocationSenderService` — pure foreground-lifecycle shell: notifications, sounds,
  intent routing (`ServiceCommandRouter`), and BLE teardown in `onDestroy`
  (`shutdownAll()` + `disconnectAll()`); it borrows the graph from `AppServices`.
  `orchestrator.events` is a SharedFlow that drops events with no subscriber —
  subscribers must attach before the first connect (both shells subscribe during
  construction on the main thread; keep it that way).
- iOS layering: `IosCentralShell` (CBCentralManager + delegate, restore identifier
  `com.saschl.cameragps.central`, Sony manufacturer-ID `0x012D` scan filter,
  connect/disconnect await-machinery — mechanics only) + `IosAccessoryShell`
  (AccessorySetupKit session, pickers, authorized-accessory snapshot — mechanics
  only) + `IosAccessoryCoordinator` (migration candidates/UI state, foreground
  prompt, exclusive picker operations, retry policy and migration completion) +
  `IosDeviceRepository` (device DB, legacy `IosAutoReconnectStore` for migration,
  enabled-state caches) + `IosBluetoothController` (central ownership,
  auto-reconnect decisions, app/device-enabled sweeps, pairing-failure state,
  device-list assembly; also the stable facade the shared iOS Compose UI consumes
  — keep its public surface stable). `IosAccessoryMigrationStore` adapts the
  existing repository, migration preference and reminders for the coordinator.
  The coordinator uses the controller's Main.immediate scope; construction of
  the coordinator and its adapters must remain inert. Accessory added/removed
  callbacks stay in the controller because they also update connection state.
  **Reentrancy rule:** `transport` and `repository` are declared BEFORE the
  central and everything else AFTER it; the shell's `central` is ITS last
  property — `willRestoreState` can fire synchronously during CBCentralManager
  construction, so shell callbacks on that path pass the shell as a parameter
  (`onKnownPeripheralsChanged`) and must never read the controller's `shell`
  field; `onPoweredOn`/reconnect lambdas are async-after and may.

**AccessorySetupKit (iOS 18+, deployment target 18.0):** discovery is the system
picker, NOT a CoreBluetooth scan — with the opt-in Info.plist key declared, a scan
only ever returns already-authorized accessories, so `startScan()` is a no-op and
the auto-scan setting is gone. Descriptors match Sony's company identifier
`012D`; every identifier/service used at runtime MUST also be declared in
`iosApp/alphagps/Info.plist` or the app CRASHES at the picker. Both
`NSAccessorySetupKitSupports` (Xcode template + Apple article) and
`NSAccessorySetupSupports` (Apple's Info.plist key reference) are declared,
because Apple's own sources disagree on the name.

**Picker restriction:** migration can be refused with
`ASErrorCodePickerRestricted` (550) while a `CBCentralManager` is alive.
`NSBluetoothAlwaysUsageDescription` must NOT be declared. Keep normal synchronous
central creation at launch: the maintainer confirmed existing unmigrated cameras
still work. Release the central only for an actual migration attempt. Native
deallocation is deferred, so bounded grace/retries are a workaround, not proof
that the manager has been released.

**Do NOT gate `startCentralIfNeeded` on `authorizedIdentifiers()`.** That set is
only filled when AccessorySetupKit delivers its async `activated` event, so the
guard is always empty at launch — exactly when state restoration needs the
central created synchronously inside `didFinishLaunchingWithOptions`. It looks
like a tidy safety check and it silently kills background reconnect for every
migrated camera. This has already been broken once.

**Migration lifecycle (September 2026):** the maintainer observed that
migration-only `ASMigrationDisplayItem` calls perform migration without displaying
a system picker. This is an on-device observation, not an Apple guarantee;
discovery items still use the visible system picker.
The background migration experiment was removed as unreliable. Do NOT trigger
migration on restoration launches or background connection callbacks. Existing
unmigrated cameras still work; keep normal synchronous central creation and
reconnection at launch. Candidate checks may run at launch, but migration itself
starts only after the user confirms in the foreground UI.
`IosAccessoryCoordinator.presentMigrationPicker` releases the central, runs
migration, then clears its guard and resumes connections in a visible `finally`.
The controller implements release/resume and checks the coordinator's guard in
`startCentralIfNeeded`; it alone constructs the native central. The public
migration methods and flows on the controller delegate to the coordinator.
Keep the increased waits for now:
10 seconds after release, 10 total attempts, 5 seconds between restricted retries.
If activation fails, retain the launch central and leave migration pending.
`migrationComplete` must release the picker wait even if no dismissal or completion
closure arrives, so reconnection does not depend on foregrounding the app.
Picker requests are exclusive and controller-owned; callbacks cannot create the
central while a migration operation holds the guard. Dismissing the foreground
explainer must not interrupt existing connections.
A migration call must contain ONLY `ASMigrationDisplayItem`s — mixing in a regular
display item changes the flow to discovery. Never delete a row for skipped or
failed migration; preserve per-device settings and keep manual retry available.

**Transmission notifications:** both platforms use `TransmissionNotificationCoordinator`
for counts, transmission gating and deduplication. Android's `LocationSenderService`
starts foreground synchronously with the waiting notification, then uses
`AndroidTransmissionNotificationPublisher` for updates; idle keeps the waiting
notification instead of removing it. Cancel the publishing job before service teardown
clears sessions, so shutdown cannot repost standby. Connection sounds remain event-driven.
`IosTransmissionNotifications` is app-scoped,
started by `ensureInitialized` after synchronous central creation. It owns notification
authorization, the retained notification-center delegate and one silent status request;
`TransmissionNotificationCoordinator` combines sessions, transmission and preferences.
`LocationTransmissionManager.isTransmitting` becomes true only after a location packet
is queued and resets when tracking stops — `isActive` alone also includes waiting for
the first GPS fix. Show only a positive transmitting-camera count; clear on stop,
disconnect or opt-out, and leave migration reminders untouched. The settings toggle
defaults on. Request permission only in the foreground, when transmission starts or
the user enables the setting; background restoration only checks existing permission.

**Crash reporting (Sentry, both platforms):** the rules live in
`commonMain/.../crash/CrashReportPolicy` — log-priority → breadcrumb/event
thresholds, MAC-address redaction and the consent gate (`enabled &&
consentDialogDismissed`, so an untouched default never opts anyone in). The
consent dialog and settings card are shared UI
(`ui/settings/SharedSentrySettings`); each platform only persists the answer.
Log lines go to Sentry three ways, and they are orthogonal, not alternatives:
INFO+ becomes a **breadcrumb** (context for a later event), ERROR+ becomes an
**issue**, and INFO+ is *also* mirrored into Sentry's structured **Logs** view
(`CrashReportPolicy.shouldSendAsLog`, matching Android's
`SentryTimberIntegration(minLogsLevel = INFO)`). Breadcrumbs only ever surface
attached to an event, so the Logs view is what you read when nothing crashed —
folding either rule into the other silently empties one of the two views.

`commonMain` and `androidMain` MUST stay free of any Sentry import: the
Android SDK is `gplayImplementation` only and `io.sentry:sentry-kotlin-multiplatform`
is declared in the **iosMain source set only**, which is the whole reason the
foss (F-Droid) flavor still builds without proprietary dependencies. Verify with
`./gradlew :app:dependencies --configuration fossDebugRuntimeClasspath | grep -i sentry`
(must be empty).

iOS specifics: `crash/IosCrashReporting` owns `Sentry.init` (DSN constant; blank
DSN = disabled, never a crash) and is started from
`IosBluetoothController.ensureInitialized` right after the loggers exist, so
background relaunches are covered too. `crash/SentryCrashLogger` is a `KmLogging`
`Logger` that mirrors Android's `SentryTimberIntegration`; it needs
`options.logs.enabled = true` for the Logs half to work, and it escapes `%` in
the body because Sentry runs it through `%s` template substitution even with no
arguments (a literal `%%` would otherwise collapse to `%`). **`KmLogging.setLoggers`
replaces the entire logger list**, so every reconfiguration goes through
`logging/IosLogging.install` or Sentry silently stops receiving log lines until
the next launch. `enableUnhandledCppExceptionMonitoring = false` is deliberate:
with it on, an unhandled Kotlin exception from a Compose callback is reported as
Kotlin/Native's internal `ExceptionObjHolderImpl` instead of reaching the KMP
hook that knows the real Kotlin stack trace.

Sentry Cocoa is a Swift Package (product **`Sentry-Dynamic`** — the Kotlin
framework is dynamic, and the other products either build from source or don't
exist; `SentrySPM` in particular needs `EXPERIMENTAL_SPM_BUILDS=1` and will fail
with "Missing package product"). It is pinned in `iosApp/alphagps.xcodeproj` +
`Package.resolved`.

Linking is done by the official `io.sentry.kotlin.multiplatform.gradle` plugin
(note the `.gradle` suffix — the README's shorter id does not exist). In
`sharednew/build.gradle.kts` it is configured with:

- `autoInstall.enabled = false` — **this is the foss guarantee.** By default the
  plugin injects `api("io.sentry:sentry-kotlin-multiplatform")` into commonMain,
  which lands in :sharednew's Android artifact and therefore in the F-Droid
  flavor. Off, the plugin does linking only and the dependency stays hand-declared
  in `iosMain`. Never remove this.
- `linker.xcodeprojPath` — otherwise the plugin file-walks the repo root hunting
  for a `.xcodeproj`, i.e. through `website/node_modules`.
- `linker.frameworkPath` ← `-Psentry.cocoa.frameworkPath`, the escape hatch for a
  checkout that has never been opened in Xcode.

The plugin is inert for Android work: it only touches Apple binaries, only on a
Mac host, and only when an Apple compile task is in the requested task graph
(F-Droid's Linux builders skip it entirely). It resolves the framework via
`ManualSearchStrategy` — a `find` over `~/Library/Developer/Xcode/DerivedData` —
because CLI `xcodebuild -showBuildSettings` reports `BUILD_DIR = iosApp/build`,
which its DerivedData strategy rejects. If it finds nothing it throws and fails
the **whole** Gradle invocation, including any Android tasks in the same command;
`xcodebuild -resolvePackageDependencies` (or one Xcode build) fixes that.

Bumping Sentry: the KMP SDK is compiled against one exact Cocoa version, and a
mismatch shows up as undefined-symbol link errors. Read the required version off
the artifact rather than the README's compatibility table (which lags releases):

```bash
V=0.27.0
curl -sL "https://repo1.maven.org/maven2/io/sentry/sentry-kotlin-multiplatform/$V/sentry-kotlin-multiplatform-$V-sources.jar" \
  | bsdtar -xOf - 'commonMain/io/sentry/kotlin/multiplatform/BuildKonfig.kt' | grep SENTRY_COCOA_VERSION
```

Then update `sentryKotlinMultiplatform` (drives both the SDK and the plugin),
`sentryCocoa` (documentation only — nothing reads it) and the Xcode pin together.
Renovate can bump the Gradle side alone; such a PR is incomplete until the Xcode
pin matches.

**Displayed session state rule:** per-device UI state lives in `CameraSession` only.
To surface a new field: add it to `CameraSession`, set it via a `BleGattPort` setter
where it changes (see `setRemoteFeatureActive`/`setShutterSequenceActive` — the port
writes the registry directly), and read `session.<field>` in the UI. No events, no
per-field mirrors, no `BluetoothDeviceInfo` copies. `BleSessionEvent` is only for the
handshake coordinator's phase/completion signals; `OrchestratorEvent` only for one-shot
platform side effects (sounds, notifications).

**Device list:** the list UI itself is shared — `ui/devicelist/SharedDeviceList`
(sectioned cards, swipe-to-delete, pairing/delete dialogs) fed by
`ui/devicelist/DeviceListViewModel` (`items: StateFlow<Map<String, DeviceListItem>>`,
keyed by uppercased identifier; combines sessions + transmission gate +
`CameraDeviceDAO.observeAllDevices()`; data sources `AndroidDeviceListDataSource` /
`IosDeviceListDataSource`). Platform hosts own only what differs: Android
(`AssociatedDevicesList`) maps CDM associations to `BluetoothDeviceInfo`, owns the
empty card and wires shutter intent / disassociate; iOS (`DeviceListContent`) owns the
scanning/disabled empty states and the troubleshooting overlay. New list features are
written once in the shared component; new derived per-row state = extend
`DeviceListItem`'s derivation once.

**Threading rule:** all common mutable state is confined to one
`Dispatchers.Main.immediate` scope per platform. Platform callbacks (Android binder
threads, iOS main queue) only `trySend` into Channel-backed flows — no locks in common
code; keep it that way.

## Invariants & gotchas

- The Sony handshake (config read → GPS unlock → lock → time sync) is sequence-sensitive;
  `BleSessionCoordinator` drives it — don't reorder or short-circuit.
- Android: `connectGatt(autoConnect = true)`. `AndroidBleTransport.pauseDevice` must NOT
  close the gatt (autoConnect resume depends on it); `disconnectAll` is the only close path.
  GATT write result 201 ("device busy") is treated as success on purpose.
- iOS: CBUUID canonicalizes base UUIDs to short form (`0000dd11-…` → `DD11`) — never
  string-compare characteristic UUIDs; use `IosBleTransport`'s `knownCharacteristicUuids`
  mapping.
- iOS pairing gate (subscribe to a notifiable characteristic to force pairing; 3 retries,
  3s apart) lives inside `IosBleTransport`'s discovery phase — it is the most
  regression-prone code in the app.
- `SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS = 30` is milliseconds and intentional
  despite the "30 seconds" comment — confirmed by the maintainer; do not change unasked.
- Changes to `SonyBluetoothConstants` affect BOTH platforms.

## Adding a remote command

1. Add an entry to `sharednew/commonMain/.../coordinator/RemoteCommand.kt` with its payload
   bytes. Commands needing a response-driven follow-up (like the shutter's ack-triggered
   release) also hook into `RemoteControlCoordinator.onRemoteStatusChanged`.
2. UI: iOS calls `IosBluetoothController.sendRemoteCommand(id, command)`; Android sends the
   `ACTION_SEND_REMOTE_COMMAND` intent with extras `address` + `remoteCommand` (= enum name,
   see `ServiceCommandRouter.EXTRA_REMOTE_COMMAND`).

Nothing else — transport, queueing and routing are generic. Only a command on a NEW
characteristic needs more: register the UUID in `IosBleTransport.knownCharacteristicUuids`
and route it in `BleSessionCoordinator`.

**Multi-step sequences** (press → wait for camera status → press …) are named suspend
functions in `RemoteControlCoordinator` composed from `sendAndAwaitStatus`/`awaitStatus`
(predicate waits on the remote-status notification flow) — see
`startShutterSequence`/`runShutterCycle`: half-press → focus acquired (`02 3F 20`) →
full-press → shutter active (`02 A0 20`) → releases → ready (`02 A0 00`). Steps advance on
camera status notifications; timeouts are fallbacks only (a camera that skips a status,
e.g. manual focus, must not stall the cycle — proceed with a warning). Rules: subscribe
to the status BEFORE sending the command that triggers it (`CoroutineStart.UNDISPATCHED`
waiter in `sendAndAwaitStatus`); devices in `sequenceOwners` suppress the automatic
ack-triggered shutter-up (the sequence sends its own releases); sequence jobs are
cancelled through the same `cancelProbe`/`cancelAllProbes` lifecycle as the probe loop
(i.e. on disconnect); always release pressed buttons in a `finally`; the trailing
ready-wait runs ONLY after a confirmed shutter-active (a camera that never fired won't
re-notify a state it never left, so waiting would always run out the 20s timeout); a new
shutter press during the ready-wait (`readyWaitDevices`) cancels it and starts a fresh
cycle — presses while buttons are still pressed stay dropped. Protocol bytes live
in `SonyBluetoothConstants`; command bytes were verified against
`tools/sony_shutter/intervalometer.py` — the focus-acquired status (`02 3F 20`) is from
community protocol docs, not the script, and needs real-camera confirmation.

## Build & verify

```bash
# Compile matrix (fast, catches almost everything — both :app flavors!)
./gradlew :sharednew:compileKotlinIosArm64 :sharednew:compileKotlinIosSimulatorArm64 \
          :sharednew:compileAndroidMain :app:compileGplayDebugKotlin :app:compileFossDebugKotlin

# iOS app (generic destination without ARCHS fails on x86_64 — iosX64 is disabled).
# Resolves the Sentry Cocoa Swift package on first run; `xcodebuild
# -resolvePackageDependencies` alone is enough to unblock a Gradle-only iOS link.
xcodebuild -project iosApp/alphagps.xcodeproj -scheme alphagps \
  -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build
```

- BLE behavior can only be truly validated against a real Sony camera; the maintainer does that.

## Working agreements

- Write tests to ensure behaviour stays the same (regression tests)
- Every new UI should stay in the shared module
- Use Android best practices wherever possible (viemodels, compose,...)
