# Contributing to DevSwitch

Thanks for helping. Bug reports, translations and code are all welcome.

## Reporting a problem

Open an issue with the bug report form. The device, the Android version, where the app was
installed from and how the permission was granted are what usually decide whether a problem can be
reproduced, so please fill those in.

## Building

You need JDK 17 and the Android SDK.

    ./gradlew assembleDebug     # debug APK in app/build/outputs/apk/debug
    ./gradlew test              # unit tests

The app needs `WRITE_SECURE_SETTINGS`, granted once with
`adb shell pm grant app.devswitch android.permission.WRITE_SECURE_SETTINGS`.

## Translating

All text lives in `app/src/main/res/values/strings.xml`, about 80 short strings.

1. Copy that file into a folder for your language. Use one folder per language and script, named
   with a BCP 47 tag: `values-b+zh+Hans`, `values-de`, `values-pt-rBR`. Do not add a second regional
   copy of the same text; Android falls back to the language folder for every region.
2. Translate the text between the tags. Leave the `name` attributes, the placeholders such as
   `%1$s`, and names like DevSwitch, Shizuku, ADB and `WRITE_SECURE_SETTINGS` as they are. Where
   Android has its own wording for something (Developer options, USB debugging), use the wording
   your phone shows in Settings.
3. Run `python3 scripts/check-translations.py`. It reports missing strings and broken placeholders.
4. Optionally translate the store listing: copy `fastlane/metadata/android/en-US/` to your locale
   (`zh-CN`, `de-DE`) and translate `short_description.txt` (80 characters at most) and
   `full_description.txt`.

The language then appears by itself in the app's language setting on Android 13 and newer.

## Code

- Keep a change to one thing, and say in the pull request how you tested it.
- Text shown to the user goes into `strings.xml`, never into Kotlin code.
- Source files carry the SPDX header the existing ones have. Contributions are accepted under
  GPL-3.0-or-later, the project's license.
- Changing permissions or anything in the manifest needs a real launch on a device or emulator
  before it is merged. Version 1.0 shipped with a crash on launch because one was skipped.
