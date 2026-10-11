# Receipt Claims Android app

Native Kotlin/Jetpack Compose client for the receipt claims API.

## Configuration

Set `BACKEND_BASE_URL` in `local.properties` (or pass it as a Gradle property):

```properties
BACKEND_BASE_URL=https://your-api.example.com/api/
```

The URL must end with `/`. For a local emulator backend, use `http://10.0.2.2:3000/api/` and only enable cleartext in a debug network security configuration.

The app intentionally has no admin navigation. Admin access is a backend concern and remains disabled by the server feature flag.

## Build

Open this directory in Android Studio, sync Gradle, and run the `app` configuration. The project targets API 36 for current Google Play requirements.
