# Reimbursement Receipt Tracker

Native Android receipt capture and reimbursement reporting app backed by a layered Express API.

The app lets a user photograph a receipt, enter the amount, choose a currency and classification, keep the original image, group receipts into a claim, and generate the supplied Excel format together with Word and PDF files.

## Repository layout

- `android/` — Kotlin and Jetpack Compose client with CameraX, Photo Picker, Room, WorkManager, and encrypted session storage.
- `backend/` — Express ES modules API using Drizzle ORM with SQLite/libSQL.

## Current architecture

The backend follows the BED course structure: routes mount controllers, controllers handle HTTP validation and response envelopes, models contain Drizzle queries, and services contain exchange-rate, storage, and report workflows. Admin routes are prepared but disabled by default with `ADMIN_PORTAL_ENABLED=false`.

The first release has no Luna integration. Receipt amounts are entered manually. Frankfurter supplies date-scoped exchange rates, and the exact rate used is saved with each receipt.

## Development

See [backend/README.md](backend/README.md) for the API setup and route list. Open `android/` in Android Studio and set `BACKEND_BASE_URL` in `local.properties` for the Android client.

Do not commit `.env`, local databases, receipt images, generated reports, Gradle files, signing keys, or credentials.
