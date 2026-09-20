# DevSwitch

A small Android app that switches **Developer options**, **USB debugging** and
**Wireless debugging** on and off, from a screen with three switches and from three
Quick Settings tiles.

Android only. iOS has no equivalent: apps cannot touch Developer Mode there.

## Why a one-time setup step is unavoidable

The three switches are values in the system settings database:

| Switch             | `Settings.Global` key           | Min. Android |
|--------------------|---------------------------------|--------------|
| Developer options  | `development_settings_enabled`  | any          |
| USB debugging      | `adb_enabled`                   | any          |
| Wireless debugging | `adb_wifi_enabled`              | 11           |

Anyone can *read* them. *Writing* them needs `WRITE_SECURE_SETTINGS`, a
`signature|privileged|development` permission that the OS never offers in a permission
dialog. Its `development` flag is the loophole: the shell user (what `adb shell` and Shizuku
run as) may grant it to any app that declares it in its manifest. Once granted it stays granted
across reboots and updates; only uninstalling the app revokes it.

So the app works like every other "toggle a secure setting" app on the market: it declares the
permission, you grant it once, and from then on it writes the settings directly. The system
reacts on its own: `AdbService` watches `adb_enabled` and `adb_wifi_enabled` and starts or stops
the daemon, and the Settings app reads `development_settings_enabled` to show or hide the
Developer options screen.

## Setup

1. Build and install (see below), then open the app. It shows a red "One-time setup needed"
   card until the permission is present.
2. Grant `WRITE_SECURE_SETTINGS` in one of three ways:

   **a. From a computer with ADB** (USB debugging must be on for this one step):

   ```sh
   adb shell pm grant com.crazyapp.devtoggle android.permission.WRITE_SECURE_SETTINGS
   ```

   The setup card has a "Copy command" button.

   **b. On the phone with [Shizuku](https://shizuku.rikka.app/)** (Android 11+, no computer):
   install Shizuku, start it through its wireless-debugging flow, then tap *Grant with
   Shizuku* in the app. The app spins up a short-lived Shizuku user service (shell uid),
   runs the same `pm grant` command, and shuts the service down again.

   **c. With root:** tap *Grant with root*. The app runs `pm grant` through `su`.

3. Reopen the app. The card turns into "Ready" and the switches become active. The tiles
   (Developer options, USB debugging, Wireless debugging) can now be added from the Quick
   Settings tile editor.

## Behaviour worth knowing

- Turning **Developer options off** also turns USB and wireless debugging off, the same as
  the master switch in the Settings app does. Turning it on changes nothing else.
- The first time wireless debugging is enabled on a given Wi-Fi network, Android shows its
  own "Allow wireless debugging on this network?" dialog, exactly as it does for the switch in
  Settings. The setting only becomes active once that is accepted; tick "Always allow on this
  network" and it will not ask again for that network.
- **Wireless debugging** needs an active Wi-Fi connection. If Wi-Fi is off or drops, Android
  flips the setting back to off by itself, and the app's switch follows because it observes
  the setting rather than remembering what it last wrote.
- Turning on USB or wireless debugging does not authorize anything: a new computer still gets
  the RSA fingerprint prompt, and wireless debugging still needs pairing.
- The tiles refuse to toggle on a locked screen and ask for unlock first, so a debugging
  channel cannot be opened from the lock screen.
- The app has no network permission and no other permissions.

## Tested

On an Android 16 (API 36) x86_64 emulator: install, the ADB grant, the permission surviving an
app update, the screen noticing a grant made while it is open, Developer options and wireless
debugging toggled from the switches and from the Quick Settings tile, the system's wireless
debugging dialog, and the cascade when Developer options is turned off (the emulator's ADB
link dropped, which is the daemon stopping). The Shizuku and root paths could not be exercised
there because the emulator image has neither.

## Known limits

- **Managed devices:** if the `DISALLOW_DEBUGGING_FEATURES` restriction is set (work profile,
  device owner), the settings provider rejects enabling any of the three, even with the
  permission.
- **Xiaomi / HyperOS:** `pm grant` over ADB only works after enabling
  *USB debugging (Security settings)* in Developer options.
- **Emulators:** turning USB debugging off on an emulator drops the ADB connection to it, which
  is exactly what the switch is supposed to do, but there is no way back without the emulator
  window.
- Shizuku started through wireless debugging stops on reboot; that does not matter here
  because it is only used for the one-time grant.

## Building

Native Kotlin + Jetpack Compose, single module.

- Android Gradle Plugin 9.3.2, Gradle 9.7.1, Kotlin 2.4.10 (AGP built-in Kotlin), compileSdk 37,
  minSdk 26, targetSdk 36.
- Needs a JDK 17 or newer to run Gradle. On this machine Android Studio's bundled JDK works:
  `JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug`
- `local.properties` points `sdk.dir` at the Android SDK (created for this machine, not
  committed).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified with R8; unsigned until a signing config is added
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

```
app/src/main/
├── AndroidManifest.xml                 permission, Shizuku provider, three tile services
├── aidl/.../IShellService.aidl         interface of the Shizuku user service
└── java/com/crazyapp/devtoggle/
    ├── DevSettings.kt                  read / write / observe the three settings
    ├── MainViewModel.kt                UI state, toggling, the three grant paths
    ├── MainActivity.kt
    ├── privilege/RootGrant.kt          `su -c pm grant ...`
    ├── shizuku/ShizukuBridge.kt        Shizuku status, permission, user-service call
    ├── shizuku/ShellService.kt         runs in Shizuku's shell-uid process
    ├── tiles/ToggleTileService.kt      Quick Settings tiles
    └── ui/                             Compose screen and theme
```
