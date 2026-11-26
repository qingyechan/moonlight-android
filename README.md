# Moonlight Bridge (Unofficial Fork of Moonlight Android)

> **Fork & License Notice**: This repository is an unofficial modification based on [**moonlight-stream/moonlight-android**](https://github.com/moonlight-stream/moonlight-android) (`v12.2`), licensed under **GNU GPL-3.0** (see [LICENSE.txt](LICENSE.txt)).  
> In compliance with upstream maintainers' request in `app/build.gradle`, the package ID has been changed to **`com.limelight.bridge`** (`Moonlight Bridge`) so it can coexist alongside official Moonlight (`com.limelight`) without interfering with Google Play crash analytics.

---

## What Changed in This Fork

1. **Physical Keyboard System Shortcut Bridge (`StreamKeyboardService`)**
   - Uses an Android `AccessibilityService` (`canRequestFilterKeyEvents`) active **only while streaming is connected and focused**.
   - Forwards `Alt+Tab`, `Win` / `Win+*` (`Win+R/D/V/E/L/Shift+S`, etc.), `Ctrl+*` (`Ctrl+Space`, `Ctrl+Shift+Esc`, `Ctrl+Alt+Del`), `Shift+Space`, `Esc`, `F1`–`F24`, `PrintScreen`, and lock keys (`Caps/Num/Scroll Lock`) directly to the host PC instead of being intercepted by Android / OEM ROMs (tested on Lenovo ZUI / Android 16).
   - Inspired by the accessibility-service approach in [`lyc8503/moonlight-android-no-keyboard-shortcut`](https://github.com/lyc8503/moonlight-android-no-keyboard-shortcut).
2. **16:10 Tablet Resolutions**
   - Adds `1280x800`, `1920x1200`, and `2560x1600` presets for 16:10 tablets.
3. **Modern Dark UI & Accessibility Shortcut**
   - Refreshes `PcView` and `AppView` with dark rounded cards, online/unpaired/offline status indicators, and a one-tap Accessibility status pill on the home screen & settings top section.
4. **In-App OTA Updater (`AppUpdateManager`)**
   - Checks GitHub Releases (`owner/repo` in Settings, with configurable download mirror prefix) and verifies SHA-256 before launching the system installer.

## Usage

1. Install the APK (`Moonlight Bridge` can coexist with official `Moonlight`).
2. Tap the **Accessibility status pill** on the home screen (or top of Settings) and enable **Stream Keyboard Bridge** in Android Settings → Accessibility.
3. Pair with your Sunshine / GameStream host and stream.

---

# Upstream Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Building
* Install Android Studio and the Android NDK
* Run `git submodule update --init --recursive` from within `moonlight-android/`
* In `moonlight-android/`, create a file called `local.properties` with `ndk.dir=` pointing to your NDK directory
* Build the APK using Android Studio or gradle (`./gradlew assembleNonRootRelease`)
  *(Note: `build.gradle` / `gradle-wrapper.properties` include mirror URLs for faster Gradle dependency downloads.)*

## Authors (Upstream Moonlight)

* [Cameron Gutman](https://github.com/cgutman)
* [Diego Waxemberg](https://github.com/dwaxemberg)
* [Aaron Neyer](https://github.com/Aaronneyer)
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
