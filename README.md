# PG Access

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-1.8.2-2563eb" />
  <img alt="React Native" src="https://img.shields.io/badge/React_Native-20232A?logo=react&logoColor=61DAFB" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Wear OS" src="https://img.shields.io/badge/Wear_OS-4285F4?logo=wearos&logoColor=white" />
</p>

An Android phone app and optional Wear OS companion that display your PureGym UK entry QR. Independent personal project; not affiliated with PureGym.

## Features

- Sign in through PureGym's hosted website.
- Display the current entry code and expiry on your phone or watch.
- Keep an encrypted, unexpired watch code available offline.
- Adjust watch QR size, auto-close time and optional closing vibration from phone settings.
- See watch connection, sync confirmation and last reported installed version.
- Send the bundled watch update from the phone, with system installation confirmation.
- Automatically brighten the phone QR screen and restore normal brightness afterwards.

## Architecture

The phone uses React Native and TypeScript, with Kotlin handling authentication, encrypted storage and wearable communication. The watch uses Kotlin and Compose. Shared protocol and QR rendering code lives in `android/protocol`.

Tokens stay encrypted on the phone. Only entry-code state, timing, settings and update metadata cross the Wearable Data Layer. Viewing a cached QR does not request a replacement credential.

## Requirements

- Node.js 22.11 or newer
- JDK 17 or newer
- Android SDK 36 and the NDK pinned in `android/build.gradle`
- An Android phone and, optionally, a paired Wear OS watch

## Development

```powershell
npm.cmd ci
npm.cmd run typecheck
npm.cmd run lint
npm.cmd test -- --watch=false
```

Configure your local Android SDK path in `android/local.properties`. Signing keys are intentionally excluded. For local development, create the key expected by the Gradle signing configuration:

```powershell
keytool -genkeypair -v -storetype JKS -keystore android/app/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
.\android\gradlew.bat -p android :protocol:testDebugUnitTest :wear:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease
```

The phone release embeds the matching watch release automatically. Both modules share version settings in `android/gradle.properties`. Use matching signing certificates for phone and watch. A newly generated key cannot update an installation signed with a different key.

## Installation

Install the phone APK from `android/app/build/outputs/apk/release`. Install the watch APK from `android/wear/build/outputs/apk/release` for initial setup. Subsequent watch updates can be sent through **Settings > Update watch**; confirm installation on the watch.

Sign in on the phone, then open the watch app to sync. Offline codes stop displaying at their server-provided expiry. Generating a fresh code requires the authenticated phone and internet access.

## Privacy and security

Do not commit account details, passwords, tokens, real QR codes, signing keys or device logs. Local configuration, build output, APKs, analysis files and runtime artifacts are excluded from version control. The existing Android application identifier is retained for installation compatibility.

## Validation

Version 1.8.2 passed TypeScript, ESLint, 30 phone tests and 37 Android JVM tests. A targeted 432x432 emulator check passed status rendering and QR decoding at sizes 1-3. Physical pairing, gate scanning and live session behavior require device testing. The full instrumentation suite is not claimed as passing.
