# Android

Open this directory in Android Studio. It contains the complete existing Kotlin / Jetpack Compose app, Gradle wrapper and unit tests. The application ID remains `com.luma.remote`; moving source files does not reset installed TVs, pairing, layouts or appearance.

With JDK 17 and Android SDK 34 configured, run from this directory:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Place any machine-specific SDK configuration in this directory's ignored `local.properties`, or set `ANDROID_HOME`.

The [workspace README](../README.md) contains setup and support instructions. [Shared documentation](../docs/ARCHITECTURE.md), [UI references](../docs/UI_AUDIT.md) and [test receivers](../tools/) stay outside the Android project. Run `../scripts/share-apk.sh` yourself when you want a temporary phone download URL.

The interface follows the system language (English or Russian). On Android 13 and newer, choose either language in Android Settings → Apps → OpenTV-Remote → Language. Other system languages use English. Saved device names, custom shortcut/preset names, typed text and remote protocol values are not translated.

Casting and local media sharing have been removed, including Google Cast and the local HTTP server. Wi-Fi TV discovery, direct remote control, TV app links, Bluetooth HID, motion controls and saved layouts remain available. The navigation selection is transient, so removing the Cast page needs no stored-data migration.
