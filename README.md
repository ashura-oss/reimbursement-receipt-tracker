# Reimbursement Receipt Tracker

Native Android receipt capture and reimbursement reporting app backed by a layered Express API.

The app lets a user photograph a receipt, enter the amount, choose a currency and classification, keep the original image, group receipts into a claim, and generate the supplied Excel format together with Word and PDF files.

## Repository layout

- `android/` — Kotlin and Jetpack Compose client with CameraX, Photo Picker, Room, WorkManager, and encrypted session storage.
- `backend/` — Express ES modules API using Drizzle ORM with SQLite/libSQL.

## Current architecture

The backend follows the BED course structure: routes mount controllers, controllers handle HTTP validation and response envelopes, models contain Drizzle queries, and services contain exchange-rate, storage, and report workflows. Admin routes are prepared but disabled by default with `ADMIN_PORTAL_ENABLED=false`.

The first release has no Luna integration. Receipt amounts are entered manually. Frankfurter supplies date-scoped exchange rates, and the exact rate used is saved with each receipt.

The backend uses SQLite by default for local development. Azure deployments can set `DATABASE_PROVIDER=postgres` and use Azure Database for PostgreSQL Flexible Server; PostgreSQL migrations and seed commands are documented in [backend/README.md](backend/README.md).

## Development

See [backend/README.md](backend/README.md) for the API setup and route list. Open `android/` in Android Studio and set `BACKEND_BASE_URL` in `local.properties` for the Android client.

Do not commit `.env`, local databases, receipt images, generated reports, Gradle files, signing keys, or credentials.

## Heroku deployment

The repository includes a root `package.json` and `Procfile` so Heroku can run the backend from the monorepo:

```text
web: npm start
```

The root start script delegates to `backend/package.json`. Configure the backend environment variables in Heroku Config Vars, including `DATABASE_URL`, `DATABASE_AUTH_TOKEN`, `JWT_SECRET`, and `ADMIN_PORTAL_ENABLED=false`. For Azure Blob Storage, also configure `STORAGE_PROVIDER=azure`, `AZURE_STORAGE_CONNECTION_STRING`, and `AZURE_STORAGE_CONTAINER`. Never place these values in Git.
