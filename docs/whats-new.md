# In-app release notes

Android and iOS share the dialog, release catalog, and version tracking policy in
`sharednew/src/commonMain/kotlin/com/sasch/cameragps/sharednew/whatsnew`.

For a new release:

1. Add its marketing version to `ReleaseNotesCatalog.forVersion` in `ReleaseNotes.kt`.
   Android's leading `v` is removed automatically. Build numbers do not trigger the dialog.
2. Add the highlights to `sharednew/src/commonMain/composeResources/values/strings.xml`.
   Use version-specific resource names so old notes remain accurate. Translations live
   in the corresponding `values-*` folders and fall back to English when unavailable.
3. Select platform-specific highlights with `ReleasePlatform` where needed.

Existing users see the installed version's notes once, after onboarding, consent,
and other blocking startup prompts. Dismissing the dialog (including back or tapping
outside it) records that version. If the app exits before dismissal, it tries again
on the next launch. Settings provides a way to reopen the same notes.

Fresh installs establish a baseline without showing the dialog. Existing installs
that predate this feature see the current notes once. Skipping releases shows only
the installed release's notes. Downgrades never lower the saved version. Releases
without a catalog entry advance the baseline silently and have no Settings entry;
they never show stale notes under a new version number.

The shared `WhatsNewStateTest` covers these persistence and version rules. For UI
verification, check an existing installation without a saved release version, dismiss
the notes and relaunch, reopen them from Settings, and check a fresh installation.
Also check startup with consent or camera migration pending, large text, and dark mode.
