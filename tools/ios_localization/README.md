# iOS String Catalog ↔ Weblate

The app keeps `iosApp/alphagps/InfoPlist.xcstrings`. Weblate translates the bilingual
XLIFF files in `localization/ios/`; the bridge imports them back into that catalog.
There are no maintained or generated `.strings` files in this workflow.

This bridge is scoped to the **InfoPlist catalog**. Compose UI translations continue
through the existing Weblate components.

## Requirements

- macOS with Xcode selected by `xcode-select`, and Python 3.9 or newer.
- No Python packages, signing setup, Kotlin framework build or Sentry package resolution.
- To select a different Xcode for one command, set `DEVELOPER_DIR` to that installation's
  `Contents/Developer` directory.

## Export for Weblate

From the repository root:

```bash
python3 tools/ios_localization/bridge.py export --language de
```

This produces `localization/ios/de.xliff`, containing English source text, translator
comments and existing German translations. Commit this file so Weblate can read it.
The real catalog and Xcode project are untouched by export.

For another language, export its file explicitly; Xcode supplies the English source
and any existing translations:

```bash
python3 tools/ios_localization/bridge.py export --language fr
```

`--language` can be repeated. Use Xcode language identifiers such as `de`, `pt-BR`
or `zh-Hans`. The English source is embedded in every XLIFF; do not create `en.xliff`.

## Weblate component

Create a separate component in the existing Alpha GPS project, using the same
repository and branch as the Compose components:

| Setting                        | Value                                               |
|--------------------------------|-----------------------------------------------------|
| File format                    | **XLIFF 1.2 with Apple extensions** (`apple-xliff`) |
| File mask                      | `localization/ios/*.xliff`                          |
| Source language                | English                                             |
| Monolingual base language file | Empty — these files are bilingual                   |
| Template for new translations  | Empty — export each new language with the bridge    |

Keep source-key creation/removal in Xcode. Enable the Weblate review workflow if you
want translators to distinguish reviewed translations from ones needing attention.
The checked-in German file is ready for this component; the bridge itself does not
connect to Weblate or publish anything.

## Import translations

Pull/merge Weblate's changes, then run:

```bash
python3 tools/ios_localization/bridge.py import localization/ios/*.xliff
```

Review and commit the resulting `InfoPlist.xcstrings` diff. The bridge uses Xcode's
native importer, retaining catalog metadata and other languages. A no-op import
leaves the file byte-for-byte unchanged. It validates **all** incoming files first
and only saves the catalog after every import succeeds.

Missing targets are allowed and remain untranslated. Targets explicitly marked as
needing edits/review must be resolved before import: Xcode silently skips Weblate's
`needs-translation` targets, so the bridge rejects them visibly rather than reporting
a successful import that would let a later refresh discard those drafts. Reviewed
and ordinary completed translations are accepted; Weblate may omit the state field.

For a newly supported language, also add that locale to `knownRegions` in
`iosApp/alphagps.xcodeproj/project.pbxproj` (or the project's Localizations in Xcode).
The temporary bridge project cannot update the real app's language list for you.

## Refresh after source changes

Import pending Weblate translations **before editing their English source**. Once
translations have been imported and any source changes made in Xcode, refresh:

```bash
python3 tools/ios_localization/bridge.py export --language de --replace
```

Repeat `--language` for each maintained target language. Export refuses to overwrite
existing XLIFF unless `--replace` is explicit: replacing a file before importing it
would discard translations that only exist in Weblate's XLIFF. Keep the catalog and
refreshed XLIFF in the same commit, and mark translations needing review when their
English meaning changes.

Import rejects changed English sources, unknown keys, duplicate keys and unrelated
catalog files. If the English source has already changed while a translation was
pending, retain the incoming file and export a fresh copy separately:

```bash
python3 tools/ios_localization/bridge.py export --language de --output-dir /tmp/alpha-gps-xliff-review
```

Review the pending translation against the new source, apply it to the freshly
exported file, then import that file. Do not bypass the source check by blindly
replacing the old English text.

## Why a temporary Xcode project?

Exporting the real app invokes Swift string extraction, which requires the Kotlin
framework to be built for the selected SDK. These InfoPlist messages already exist
in the catalog, so that build is unnecessary.

The script creates a temporary macOS resource-bundle project containing just a copy
of the catalog. It runs `xcodebuild -exportLocalizations` / `-importLocalizations` and
keeps only the XLIFF exchange files or updated catalog. In that copy, extraction
states are temporarily set to `manual` so Xcode cannot prune keys merely because the
app's source/Info.plist is absent. Original extraction metadata is restored before
saving an imported catalog. The real project's build settings are never changed.

Use the bridge for both directions: exported XLIFF paths refer to this temporary
project, not the app's directory layout. Do not import these files directly into the
real app project with Xcode's menu.

## Tests

Fast validation tests (any OS):

```bash
python3 -m unittest discover -s tools/ios_localization -v
```

Include actual Xcode export/import tests (macOS):

```bash
RUN_XCODE_LOCALIZATION_TESTS=1 python3 -m unittest discover -s tools/ios_localization -v
```

The native tests cover no-op preservation, comments and extraction metadata, missing
targets, a new language, Unicode/escaping, Weblate targets without state attributes,
rejection of stale source text, and explicit rejection of unfinished translation drafts. They use
fixture catalogs in temporary folders.

References: [Apple's export/import workflow](https://developer.apple.com/documentation/xcode/editing-xliff-and-string-catalog-files),
[Apple export commands](https://developer.apple.com/documentation/xcode/exporting-localizations),
[Weblate XLIFF support and translation states](https://docs.weblate.org/en/latest/formats/xliff.html).
