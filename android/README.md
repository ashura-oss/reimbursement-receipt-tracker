# Receipt Claims Android app

Native Kotlin/Jetpack Compose client for the receipt claims API.

## Configuration

Set `BACKEND_BASE_URL` in `local.properties` (or pass it as a Gradle property):

```properties
BACKEND_BASE_URL=https://your-api.example.com/api/
```

The URL must end with `/`. For a local emulator backend, use `http://10.0.2.2:3000/api/` and only enable cleartext in a debug network security configuration. Configure the production API URL before building a release; the fallback URL is intentionally non-routable.

The app intentionally has no admin navigation. Admin access is a backend concern and remains disabled by the server feature flag.

## Build

Open this directory in Android Studio, sync Gradle, and run the `app` configuration. The project targets API 36 for current Google Play requirements.

From the repository root, run `android\gradlew.bat -p android assembleDebug` on Windows or `./android/gradlew -p android assembleDebug` on macOS/Linux.

### Signed release APK

Release builds require all four signing variables below. Keep the keystore under the ignored repository `work/` directory and set the passwords in your shell or a secret manager. Do not put keystore material or passwords in `local.properties`, Gradle files, or source control.

- `RECEIPT_RELEASE_STORE_FILE` — path to the signing keystore (from the Android project directory, for example `../work/receipt-release.jks`)
- `RECEIPT_RELEASE_STORE_PASSWORD`
- `RECEIPT_RELEASE_KEY_ALIAS`
- `RECEIPT_RELEASE_KEY_PASSWORD`

From the `android/` directory, set those variables in the current environment and run:

```powershell
.\gradlew.bat assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. With a device connected and USB debugging enabled, install or update it with:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

On macOS/Linux, use `./gradlew assembleRelease` and `adb install -r app/build/outputs/apk/release/app-release.apk`. Keep the same keystore and alias for future updates to the installed app.
