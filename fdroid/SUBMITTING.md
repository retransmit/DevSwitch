# Submitting DevSwitch to F-Droid

This follows the same reproducible-build route as the Atrium submission: F-Droid builds the tagged
commit itself, strips the signature, and checks that it matches the APK published on the GitHub
release. `app.devswitch.yml` next to this file is the entry for F-Droid's `fdroiddata` repository.

## Once: a release signing key

    keytool -genkeypair -v -keystore /path/outside/the/repo/devswitch-release.jks \
            -alias devswitch -keyalg RSA -keysize 4096 -validity 10000

Copy `keystore.properties.example` to `keystore.properties`, fill it in, and back the keystore up.
It is gitignored. DevSwitch has its own key, created 2026-09-21; its certificate digest is already
filled into `app.devswitch.yml`.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Build with JDK 17, which is what F-Droid's build server uses, so R8 produces identical output:
   `JAVA_HOME=/path/to/jdk-17 ./gradlew clean assembleRelease`. The signed APK is
   `app/build/outputs/apk/release/app-release.apk`. Building twice and comparing the hashes is a
   cheap check that nothing on the machine leaks into the output.
3. Read the signing certificate digest and put it in `AllowedAPKSigningKeys`, lowercase and without
   colons:

       $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

4. Tag the release commit and push the tag: `git tag v1.0 && git push origin v1.0`.
5. Create the GitHub release for that tag and attach the APK renamed to `DevSwitch-1.0.apk`, which
   is the name the `binary:` line expects. Renaming does not change the file, so the comparison
   still matches.

## The merge request

1. Fork https://gitlab.com/fdroid/fdroiddata and add `metadata/app.devswitch.yml`, copied from
   `fdroid/app.devswitch.yml`. Replace `commit: v1.0` with the full hash of the tagged commit,
   as the Atrium entry does, and fill in `AllowedAPKSigningKeys`.
2. Open a merge request titled `New app: DevSwitch (app.devswitch)` and complete the inclusion
   checklist in the template. The description text, changelog, icon and screenshots come from
   `fastlane/metadata/android/en-US/` in this repository, so the YAML needs no Description.
3. Optional local check with fdroidserver installed:

       fdroid readmeta
       fdroid checkupdates app.devswitch
       fdroid build -v -l app.devswitch

Notes that matter for the reproducible check: the release build is unsigned unless
`keystore.properties` exists, so F-Droid's own build has no signature to strip; Google Play's
dependency blob is disabled; the only native libraries come from AndroidX and are packaged
unstripped (`keepDebugSymbols`), so having an NDK or not changes nothing; AGP's
`version-control-info.textproto` is switched off in the release build type, so the APK does not
depend on which commit it was built from; and F-Droid's Gradle transparency log already lists the
Gradle 9.7.1 distribution this project's wrapper uses.
