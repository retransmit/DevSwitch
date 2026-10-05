## What this changes

<!-- A sentence or two. Link the issue it belongs to, for example "Closes #12". -->

## How it was tested

<!-- Device or emulator and Android version, and what you tried. -->

## Checklist

- [ ] The app builds and still starts (`./gradlew assembleDebug`, then open it)
- [ ] `./gradlew test` passes
- [ ] No unrelated changes mixed in

For translations, also:

- [ ] `python3 scripts/check-translations.py` passes
- [ ] One strings folder per language (for example `values-b+zh+Hans`), with no duplicate regional copy
- [ ] Format placeholders such as `%1$s` are kept exactly as in the English text
- [ ] Store listing text added under `fastlane/metadata/android/<locale>/` (optional but welcome)
