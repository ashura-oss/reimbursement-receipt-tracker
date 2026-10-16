# Reimbursement Receipt Tracker

Offline-first native Android app for recording receipts, grouping them into claims, and preparing reimbursement reports. The app runs without an account, network access, backend hosting, or Azure. Receipt images, claims, reports, and claimant settings stay in private app storage; use the in-app **Backup and restore** screen to move data between devices.

New receipt amounts use SGD by default. For another currency, enter the exchange rate to SGD manually. Receipt names and expense account choices are optional; expense accounts are separate from classifications. Optional travel, GST-included, business-unit, cost-centre, department, payment, review, approval and signature fields support the report template. Staff details stay on the device and are snapshotted when a new claim is created. Reports include an Excel workbook, a Word document with receipt images, and a combined PDF. Room migrations from version 3 through version 7 preserve existing receipts and photos. Old online-claimed rows remain marked as previously claimed until the user explicitly makes them available for a local claim.

The **Storage management** screen filters receipts by inclusive date range and classification, opens full-size receipt photos, and supports single or filtered bulk deletion. Deleting a receipt also removes its affected local claim and saved reports; other receipts remain available for reuse. The app has no arbitrary lifetime receipt quota; storage is limited by the device's available space. Individual report generation limits are shown in the app.

## Repository layout

- `android/` — Kotlin and Jetpack Compose app with CameraX, gallery selection, Room storage, local backups, and on-device report generation.
- `backend/` — optional legacy/reference Express API and deployment project. It is not required to build or use the Android app; Azure resources and deployment scripts are untouched by the local app.

The backend retains its layered structure: routes mount controllers, controllers handle HTTP validation and response envelopes, models contain Drizzle queries, and services contain exchange-rate, storage, and report workflows. Admin routes remain a separate backend feature and are disabled by default with `ADMIN_PORTAL_ENABLED=false`.

## Android build

Open `android/` in Android Studio and run the `app` configuration. No `BACKEND_BASE_URL` or service credentials are needed.

For a command-line Android debug build on Windows, run `android\gradlew.bat -p android assembleDebug` from the repository root; on macOS/Linux, run `./android/gradlew -p android assembleDebug`. The Gradle wrapper downloads its pinned Gradle version as needed.

Release APK updates retain the original application ID and require the same signing key. The QA/debug build uses a `.qa` application ID suffix so it can be installed beside a release build.

The Android Gradle wrapper (`gradlew`, `gradlew.bat`, and `gradle/wrapper`) is part of the source and should stay committed. Do not commit Gradle build caches, `local.properties`, `.env` files, local databases, receipt images, generated reports, signing keys, or credentials.

## Optional backend development and deployment

See [backend/README.md](backend/README.md) for API setup and route details. To deploy the optional backend, provision Azure resources from `backend/infra/appservice.bicep`, then package and deploy from `backend/`:

```powershell
cd backend
./infra/deploy-appservice.ps1 -ResourceGroup receipt-claims-rg -AppName receipt-claims-api
```

The infrastructure template configures managed identity for private Blob access and sets PostgreSQL, JWT, and admin-disabled app settings. PostgreSQL ingress must be restricted to the App Service outbound IP addresses; see the deployment steps before running migrations. Keep production template workbooks in the private Blob account instead of Git. The root `Procfile` supports an optional Heroku deployment for the reference backend.
