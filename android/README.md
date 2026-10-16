# Receipt Claims Android app

Native Kotlin/Jetpack Compose app for saving receipts, creating claims, and generating reimbursement reports entirely on the device. It has no account requirement or network permission. Receipt photos, claims, and generated files are kept in private app storage; use **Backup and restore** to move a backup between devices.

New receipts use SGD by default, and receipt names and expense account choices are optional. Expense accounts are a separate selection from classifications and can be changed on unclaimed receipts. For another currency, enter the exchange rate to SGD manually. Room migrations from version 3 through version 6 preserve receipt rows and image paths, keep legacy online-claimed receipts marked as previously claimed, and normalize other old upload states to locally saved. Legacy foreign-currency rows keep an unknown SGD conversion until a rate is entered manually. The app works offline without an account, hosting, Azure, or network permission. Backend and Azure deployment files remain optional reference material; the Android app does not call them. The admin portal remains a separate backend feature.

**Storage management** filters receipts by inclusive date range and classification, opens full-size photos, and supports single or filtered bulk deletion. Deleting a receipt removes its affected local claim and saved reports while keeping other receipts available for reuse. There is no arbitrary lifetime receipt quota; storage is limited by available device space. Report-generation limits apply per claim and are shown in the app.

## Build

Open this directory in Android Studio, sync Gradle, and run the `app` configuration. The project targets API 36.

From the repository root, run `android\gradlew.bat -p android assembleDebug` on Windows or `./android/gradlew -p android assembleDebug` on macOS/Linux.

The debug application ID uses a `.qa` suffix so it can be installed beside an existing release app during checks. Release builds retain the original application ID and require the same signing key used by previous releases.

### Signed release APK

Release builds require all four signing variables below. Keep the keystore under the ignored repository `work/` directory and set the passwords in your shell or a secret manager. Do not put keystore material or passwords in `local.properties`, Gradle files, or source control.

- `RECEIPT_RELEASE_STORE_FILE` — path to the signing keystore (from the Android project directory, for example `../work/receipt-release.jks`)
- `RECEIPT_RELEASE_STORE_PASSWORD`
- `RECEIPT_RELEASE_KEY_ALIAS`
- `RECEIPT_RELEASE_KEY_PASSWORD`

From the `android/` directory, set those variables in the current environment and run `./gradlew assembleRelease` (or `.\gradlew.bat assembleRelease` on Windows).

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Install or update it with `adb install -r app/build/outputs/apk/release/app-release.apk`. Keep the same keystore and alias for future updates.
