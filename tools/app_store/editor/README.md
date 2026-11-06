# Alpha GPS screenshot editor

Created with the `app-store-screenshots` skill template. Four slides per device
in English and German cover GPS tagging, automatic reconnection, remote
shutter and Wi-Fi live view. All app images come from the Debug simulator screenshot mode.

```sh
cd tools/app_store/editor
npm install
npm run dev -- --hostname 127.0.0.1
```

Open http://127.0.0.1:3000. Select a device and language in the toolbar. Changes
save to `app-store-screenshots.json`. **Export bundle** exports the selected
device in both languages and all template resolutions.

The original capture/render tools in the parent directory remain available.
To refresh iPhone, 13-inch iPad and Duo outer captures with an existing build:

```sh
node ../capture-editor.mjs /absolute/path/to/alphagps.app
```

Requires the named simulators in `capture-editor.mjs`. Use dedicated simulators:
the script boots, installs and launches the app, retries likely blank frames,
clears its status override and shuts down each simulator before the next.
Set `CAPTURE_TARGET` to `iphone`, `ipad`, or `duo-outer/portrait` for one set.

With the editor running, export all bundles using `node export-bundles.mjs`.
Set `EXPORT_DEVICE` to export one deck. The script uses Google Chrome;
`SCREENSHOT_CHROME` overrides its executable path. Files go to
`artifacts/app-store-new/` at the repository root.

Check the Duo sets in App Store Connect's preview before submitting.

For the Duo inner display, select the Duo in Device Hub, then run
`node ../capture-duo-inner.mjs` after the outer capture. This uses the toolbar's
Closed/Open controls through macOS Accessibility, recaptures the outer English
hero, and captures the inner landscape set. Device Hub must already be open.

After exporting, run `node package-exports.mjs` to validate dimensions and
opaque RGB encoding, build the preview gallery, and zip the 32 primary PNGs.
The individual device bundles also include the template's additional sizes.

To refresh only the fourth slide, set `SCREENSHOT_SCENARIO=wifi` for both capture
scripts. The capture tool injects `../assets/wifi-preview.jpg` into the built
simulator app before installation; the sample photo is not bundled in release
apps. The preview comes from the user-provided `../DSC03702.HIF`, converted to
a JPEG for the screenshot fixture. The original HIF is preserved. Capture the outer Duo first, then its inner display.

The packaging step also creates `alpha-gps-wifi-screenshots.zip` with only the
eight new Wi-Fi slides, plus `wifi-preview.png` for review.

## Static App Store creative assets

In the iOS device menu, choose **Product page header**, **Search results**, or
**Universal creative** under **App Store creative assets**. Each has an editable
English and German design combining large GPS and Wi-Fi iPhone views with the
existing screenshot typography and lavender theme. Your DSC03702 photograph
appears inside the Wi-Fi screen.
These are static artwork placements; Apple's “app previews” are videos.

| Placement | Export dimensions |
| --- | --- |
| Product page header | 3840 × 1646 |
| Search results | 3840 × 2560 and 1920 × 1280 |
| Universal creative (either placement) | 5244 × 2950 |

Keep the headline inside the safe-area guides. The two large phone views extend
into the backdrop and can be cropped by the placement. Guides are excluded
from exports. Universal crop guides are estimates; confirm the actual crops
with App Store Connect's Preview tool before uploading. See Apple's
[asset guidance](https://developer.apple.com/app-store/asset-best-practices/)
and [creative asset specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications).

With the editor running, generate and validate all creative bundles:

```sh
npm run export:creatives
```

This checks each placement, language, image dimensions, sRGB encoding and lack
of transparency. Output goes to `artifacts/app-store-new/`:

- `alpha-gps-creative-assets.zip`: all eight PNGs and a manifest.
- `creative-preview.png`: English and German contact sheet.
- `creative-assets/index.html`: preview gallery with full-size image links.
- `creative-assets/ios/`: individual images grouped by placement, size and language.

After exporting, run `node preview-search-crop.mjs` to make
`search-crop-preview.png`, an approximate wide crop based on the supplied
App Store search preview (710 × 278 visible artwork). It is a review aid,
not an upload asset or a guarantee of Apple's crop on every device.

Use `EXPORT_DEVICE=creative-header node export-bundles.mjs` to refresh just one
placement, then `node package-creatives.mjs` to rebuild the combined package
from all three placement bundles. `EXPORT_SCOPE=all node export-bundles.mjs`
exports both the four device decks and the three creative decks.
Run `npm run test:exports` for the export validation regression tests.

To recreate the preview JPEG from the original Sony HIF on macOS:

```sh
sips -s format jpeg -Z 2048 ../DSC03702.HIF --out ../assets/wifi-preview.jpg
```
