# Reimbursement Receipt Tracker

Native Android receipt capture and reimbursement reporting app backed by a layered Express API.

The app lets a user photograph a receipt, enter the amount, choose a currency and classification, keep the original image, group receipts into a claim, and generate the supplied Excel format together with Word and PDF files.

## Repository layout

- `android/` — Kotlin and Jetpack Compose client with CameraX, Photo Picker, Room, WorkManager, and encrypted session storage.
- `backend/` — Express ES modules API using Drizzle ORM with SQLite/libSQL.

## Current architecture

The backend follows the BED course structure: routes mount controllers, controllers handle HTTP validation and response envelopes, models contain Drizzle queries, and services contain exchange-rate, storage, and report workflows. Admin routes are prepared but disabled by default with `ADMIN_PORTAL_ENABLED=false`.

Receipt amounts are entered manually. Frankfurter supplies date-scoped exchange rates, and the exact rate used is saved with each receipt.

The backend uses SQLite by default for local development. The planned production host is Azure App Service on Linux (Basic B1), Azure Database for PostgreSQL Flexible Server (Burstable B1ms, 32 GiB), and a private Azure Blob container with managed-identity access. Deployment and PostgreSQL migration instructions are in [backend/infra/README.md](backend/infra/README.md). Container Apps artifacts remain optional experiments.

## Development

See [backend/README.md](backend/README.md) for the API setup and route list. Open `android/` in Android Studio and set `BACKEND_BASE_URL` in `local.properties` for the Android client.

For a command-line Android debug build on Windows, run `android\gradlew.bat assembleDebug` from the repository root; on macOS/Linux, run `./android/gradlew assembleDebug`. The Gradle wrapper downloads its pinned Gradle version as needed.

The Android Gradle wrapper (`gradlew`, `gradlew.bat`, and `gradle/wrapper`) is part of the source and should stay committed. Do not commit Gradle build caches, `local.properties`, `.env` files, local databases, receipt images, generated reports, signing keys, or credentials.

## Azure App Service deployment

Provision the Azure resources from `backend/infra/appservice.bicep`, then package and deploy the production backend from `backend/`:

```powershell
cd backend
./infra/deploy-appservice.ps1 -ResourceGroup receipt-claims-rg -AppName receipt-claims-api
```

The infrastructure template configures managed identity for private Blob access and sets PostgreSQL, JWT, and admin-disabled app settings. PostgreSQL ingress must be restricted to the App Service outbound IP addresses; see the deployment steps before running migrations. Keep production template workbooks in the private Blob account instead of Git. The old root `Procfile` supports an optional Heroku deployment only; it is not the primary production path.
