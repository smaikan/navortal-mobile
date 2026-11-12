# Navortal for Android

Android app for [Navortal](https://www.navortal.com.tr), a daily planning and task timer web app. The app is a native shell around the web app that adds the things a browser cannot do well.

## What it does

- **Welcome screen.** Signed-out users see a native screen that works offline and shows how much of today is left. The live site loads only after they tap sign in or sign up.
- **Task timer notification.** The running task shows as a stopwatch in the notification shade. Breaks are not counted and no network request is needed each second.
- **Google sign-in.** Uses Android Credential Manager on devices with Google Play services. Google sign-in is never shown inside the WebView.
- **Push notifications.** Reminders arrive through Firebase Cloud Messaging. The app checks each message with the server before it shows it, and ignores duplicates.
- **Error handling.** Offline and slow loads show a retry screen, and the app recovers when the connection returns.

## Requirements

- JDK 17
- Android SDK 36 (`minSdk` 26)
- Gradle wrapper included, no separate install needed

## Build

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For a release bundle run `./gradlew bundleRelease`. Release signing reads `keystore.properties` (not in this repository) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file the release build is unsigned.

## Configuration

These files are not in the repository:

| File | Purpose |
| --- | --- |
| `app/google-services.json` | Firebase configuration for push notifications. Create your own Firebase project, register the package `tr.com.navortal` and download the file into `app/`. The build needs it. |
| `keystore.properties` and the keystore | Release signing. |

Google sign-in also needs an Android OAuth client in Google Cloud. It must match the package name and the SHA-1 of the key that signs the APK. If they do not match, Google reports that no account is available.

## Project layout

```
app/src/main/java/tr/com/navortal/
  MainActivity            WebView shell, error handling, navigation
  TimerNotification       Task timer notification
  TimerSnapshot           Validates timer messages from the web app
  WelcomeScreen           Native welcome screen (ring, plan card, buttons)
  GoogleSignIn            Credential Manager flow
  Push*, NavortalMessagingService
                          Device registration and push message handling
```

Pure logic such as time math, URL rules and payload validation is kept in small classes with unit tests under `app/src/test`.

## Status

This is a development build. Behavior has been checked with unit tests and an emulator, but not yet on a range of real devices. iOS is not part of this project.
