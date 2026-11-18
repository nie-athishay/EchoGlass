# EchoGlass Native Android App

This directory contains the complete native Android Studio project (Kotlin, Jetpack Compose, Room, `NotificationListenerService` + `MediaSessionManager`).

## Building an Installable Release `.apk`

### Option 1: Android Studio
1. Open the `/android-app` folder in **Android Studio** (Ladybug or newer with JDK 17 + Android SDK 35).
2. Let Gradle sync dependencies.
3. Click **Build → Generate Signed App Bundle / APK... → APK**, create or select your keystore, choose the **release** build variant, and click **Create**.
4. Android Studio outputs the installable signed APK at:
   `app/release/app-release.apk` (or `app/build/outputs/apk/release/app-release.apk`).

### Option 2: GitHub Actions (Automatic Cloud Build)
Push the contents of `/android-app` to a GitHub repository and trigger the **Build Signed EchoGlass Release APK** workflow (`.github/workflows/build-apk.yml`). It signs and outputs `EchoGlass-release-apk` (`app-release.apk`) ready to install on any Android 10+ phone.

## Enabling Notification Access on Android 13–16
After installing `app-release.apk` on your phone:
1. Open the **Listener** tab on the bottom navigation bar and tap **Open Notification Access Settings**.
2. If Android restricts the toggle for sideloaded APKs, open **App Info → ⋮ (top right) → Allow restricted settings**, then return and enable **EchoGlass Music Tracker**.
