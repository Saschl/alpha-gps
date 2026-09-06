# App Store screenshots

Five English and five German screenshots, captured from the real iOS Compose
screens with deterministic sample cameras and rendered into reusable layouts.
No camera, Bluetooth pairing, permission dialogs, or manual screenshot editing
is needed. These are iPhone screenshots, not iPad exports.

## Generate everything

Requires macOS, Xcode with an **iPhone 17 Pro Max** simulator, Node.js, and Google
Chrome. Resolve the project's Sentry Swift package dependencies in Xcode first
if this checkout has not been built before.

```sh
cd tools/app_store
npm install
npm run capture
```

The capture command builds the Debug simulator app, installs it, captures five
scenarios in both languages, and renders the final PNGs. To reuse a build:

```sh
npm run capture -- /absolute/path/to/alphagps.app
```

Output is in `artifacts/app-store/` at the repository root:

- `en-US/` and `de-DE/`: numbered upload images, 1284 × 2778, opaque RGB PNG.
- `preview.png`: contact sheet (English above German).
- `index.html`: local gallery with links to the full-resolution images.
- `raw/`: unframed simulator captures for other layouts or reuse.

Upload the numbered PNGs to the corresponding language's **6.5-inch iPhone**
screenshot group in App Store Connect. Dimensions and transparency follow
[Apple's screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/).
Review the current UI and compatibility claims before publishing; this tool
does not upload anything or change App Store Connect.

## Change the design or text

Edit `copy.json` for captions and `render.mjs` for the HTML/CSS layout. Then:

```sh
npm run render
```

This uses the existing raw captures, so copy/design changes need no Xcode build
or simulator. The renderer checks text overflow, dimensions, transparency,
image proportions, and frame/caption boundaries. The complete raw screenshot
is scaled uniformly into a matching frame, with no cropping or stretching
(apart from the decorative rounded screen corners). Raw files are read-only
inputs to this command.

The 1320 × 2868 design canvas is scaled uniformly to fit the 1284 × 2778
export, with a few pixels of background padding at the sides. This preserves
the approved layout and phone proportions while matching the upload field.

Optional environment overrides:

- `SCREENSHOT_DEVICE`: simulator UDID (prefer the same iPhone size).
- `SCREENSHOT_CHROME`: Chrome executable path.
- `SCREENSHOT_PLAYWRIGHT`, `SCREENSHOT_SHARP`: absolute paths to existing Node
  packages, if reusing tooling already installed elsewhere.

## Safety and implementation

`ALPHA_GPS_SCREENSHOT` selects `geotagging`, `reconnect`, `remote`, `multiple`,
or `privacy`. The capture script passes it only to the launched simulator app.
Swift honors it **only in Debug simulator builds**. The normal app launch and
Bluetooth restoration path are unchanged, including on physical devices.
`StoreScreenshotViewController.kt` supplies sample state to production shared
UI with inert callbacks; it does not initialize the Bluetooth controller or
crash reporting. The settings screen may still load its normal StoreKit tip
information. No purchase action is performed.

Use a dedicated simulator: captures install the app, set light appearance,
temporarily override its status bar, and replace its running session. The
script clears the status-bar override and terminates its app on completion.
Generated exports and local build/dependency directories are gitignored.
