# Receipt Claims API

The Receipt Claims API is the backend for a native Android expense reimbursement app. It lets authenticated users save receipt details and images, group receipts into claims, apply exchange rates, and generate reimbursement reports.

The project follows a layered Express structure used in the BED backend conventions:

```text
routes → controllers → models → Drizzle/libSQL
                 ↘ services
```

Controllers handle HTTP validation and response envelopes. Models contain database access. Services contain workflows such as exchange-rate lookup, local file storage, and report generation.

## Stack

- Node.js and Express using ES modules
- Drizzle ORM with `@libsql/client`
- SQLite locally, with Azure Database for PostgreSQL Flexible Server as the primary production database
- Azure Database for PostgreSQL Flexible Server through `postgres-js` when `DATABASE_PROVIDER=postgres`
- bcrypt and JSON Web Tokens for authentication
- Multer for multipart receipt uploads
- Frankfurter for free currency exchange rates
- ExcelJS, docx, and pdf-lib for report files

Receipt amounts are entered manually by the user, and the entered values are saved with each claim.

## Project structure

```text
backend/
  index.js                 # Express wiring and route mounting
  drizzle.config.js
  src/
    constants/
    controllers/
    db/                     # schema, client, seed script
    middlewares/
    models/                 # database access
    routes/
    services/               # rates, storage, report generation
    utils/
  storage/                 # local development storage, ignored by Git
```

## Local setup

Use Node.js 22 LTS for production and Node.js 18 or newer for local development.

```powershell
cd backend
npm install
Copy-Item .env.example .env
npx drizzle-kit push --force
npm run db:seed
npm run dev
```

The API listens on `http://localhost:3000` by default. Check the service with:

```text
GET /health
```

For local development, SQLite files and receipt images live under ignored local paths. Production uses PostgreSQL and private Azure Blob Storage through the App Service managed identity; see [infra/README.md](infra/README.md).

## Configuration

`.env` is local-only and is never committed. The available values are documented in `.env.example`:

| Variable | Purpose |
| --- | --- |
| `PORT` | Express listening port |
| `CORS_ORIGINS` | Comma-separated browser origins allowed in production; native Android requests do not send an origin |
| `DATABASE_URL` | `file:local.db` locally or a PostgreSQL URL in production |
| `DATABASE_AUTH_TOKEN` | Optional token for a hosted libSQL database |
| `DATABASE_PROVIDER` | `sqlite` by default; use `postgres` for Azure Database for PostgreSQL |
| `POSTGRES_SSL` | `verify-full` by default; use `disable` only for a trusted local server |
| `POSTGRES_MAX_CONNECTIONS` | PostgreSQL connection-pool ceiling |
| `JWT_SECRET` | Secret used to sign access tokens |
| `JWT_EXPIRES_IN` | Token lifetime, such as `7d` |
| `STORAGE_DIR` | Local private file-storage directory |
| `STORAGE_PROVIDER` | `local` by default; set to `azure` to use Azure Blob Storage |
| `AZURE_STORAGE_CONNECTION_STRING` | Azure Blob connection string, required only for the Azure provider |
| `AZURE_STORAGE_ACCOUNT_URL` | Optional Azure Blob account URL for managed identity authentication |
| `AZURE_STORAGE_CONTAINER` | Private Azure Blob container name, required only for the Azure provider |
| `EXCEL_TEMPLATE_PATH` | Optional path to the reimbursement workbook template |
| `EXCEL_TEMPLATE_STORAGE_KEY` | Optional private storage key for the workbook; takes precedence over `EXCEL_TEMPLATE_PATH` |
| `FRANKFURTER_BASE_URL` | Frankfurter API base URL |
| `FRANKFURTER_TIMEOUT_MS` | Exchange-rate API timeout (default 8 seconds; maximum 15 seconds) |
| `ADMIN_PORTAL_ENABLED` | Keep `false` until the admin portal is ready |

## API routes

All successful JSON responses use `{ "message": "...", "data": ... }`. Errors use `{ "error": "..." }`.

### Authentication

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/register` | Create a user account |
| `POST` | `/api/auth/login` | Return a JWT access token |
| `GET` | `/api/auth/me` | Return the current user |

Authenticated requests send `Authorization: Bearer <token>`.

### Receipts and categories

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/categories` | List active receipt classifications |
| `GET` | `/api/receipts` | List the current user's receipts |
| `POST` | `/api/receipts` | Save a receipt and its image (`multipart/form-data`) |
| `GET` | `/api/receipts/:id` | Retrieve one owned receipt |
| `PUT` | `/api/receipts/:id` | Edit an owned receipt |
| `DELETE` | `/api/receipts/:id` | Delete an owned receipt and local image |

Receipt creation requires `image`, `amount`, `categoryId`, and `receiptDate`. Currency defaults to `SGD`. The optional `clientReceiptId` makes retries safe when an Android upload is interrupted; a repeated value for the same user returns the existing receipt.

The backend stores the original amount, selected currency, exchange rate, and SGD equivalent. Frankfurter rates are cached by currency and receipt date, and the rate used is saved with the receipt.

### Claims and reports

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/claims` | List the current user's claims |
| `POST` | `/api/claims` | Create a claim from receipt IDs |
| `GET` | `/api/claims/:id` | Retrieve a claim and its receipts |
| `POST` | `/api/claims/:id/submit` | Mark a claim as submitted |
| `POST` | `/api/reports/claims/:claimId` | Generate Excel, Word, and PDF files |
| `GET` | `/api/reports/:id` | Retrieve generated report metadata |
| `GET` | `/api/reports/claims/:claimId/latest` | Retrieve the latest report and file metadata for an owned claim (404 when none exists) |
| `GET` | `/api/report-files/:id/download` | Download a private report file owned by the current user |

Generated Excel, Word, and PDF files are stored privately and recorded in `reports` and `report_files`. The authenticated latest route returns `{ report, files }`; files are downloaded using `GET /api/report-files/:id/download`. PDFs embed licensed Latin/Tamil/Simplified Chinese fonts; unsupported glyphs appear as `[U+XXXX]`. Each receipt image is validated and included in the Word/PDF outputs. Claims whose images total more than 50 MiB are rejected with 422 so report generation stays within App Service memory limits.

Set `STORAGE_PROVIDER=azure` in a hosted deployment to store receipt images and generated reports in a private Azure Blob container. The storage service keeps the same key-based interface for both providers; no Azure credentials are committed to the repository. Leave the provider as `local` for development.

## Future admin portal

The schema already records ownership, roles, claims, receipt details, image storage keys, generated files, and audit entries so an admin portal can be added later. Admin routes are not mounted unless:

```text
ADMIN_PORTAL_ENABLED=true
```

When enabled, an authenticated user must also have the `ADMIN` role. Available routes are:

```text
GET /api/admin/users
GET /api/admin/claims
GET /api/admin/claims/:id
GET /api/admin/receipts/:id/image
```

Keep the flag set to `false` during the first release.

## Database commands

```powershell
npm run db:push
npm run db:seed
```

`db:seed` is idempotent and loads the receipt classifications used by the Android dropdown.

## Azure PostgreSQL deployment

For Azure Database for PostgreSQL Flexible Server, set `DATABASE_PROVIDER=postgres` and use a PostgreSQL connection URL in `DATABASE_URL`, for example:

```text
postgresql://user:password@server.postgres.database.azure.com:5432/claims?sslmode=require
```

Generate and apply PostgreSQL migrations from a deployment environment with access to the server:

```powershell
npm run db:generate:postgres
npm run db:migrate:postgres
npm run db:seed:postgres
```

`db:migrate:postgres` is the production path. The SQLite `db:push` command remains for local development and is not used against the Azure PostgreSQL server. PostgreSQL schema generation and migration files can be created without a live database; this workspace does not claim a live Azure integration test until credentials and network access are configured.

## Azure App Service deployment

The primary deployment uses Azure App Service Linux Basic B1, Azure Database for PostgreSQL Flexible Server Burstable B1ms with 32 GiB storage and no HA, and private Standard LRS Blob storage. The web app uses a system-assigned managed identity for Blob access. Its admin portal remains disabled. Provisioning, PostgreSQL firewall setup, workbook upload, migrations, and package deployment are documented in [infra/README.md](infra/README.md). Container Apps artifacts are optional legacy alternatives.

`infra/deploy-appservice.ps1` packages only production source, migrations, licensed fonts, and Node package manifests. It excludes local databases, `.env`, private files, tests, image fixtures, and work directories. It calls `az webapp deploy --async false` and verifies `/health` after Azure finishes deploying.

PostgreSQL migrations are generated with `npm run db:generate:postgres` only when the Drizzle schema changes, reviewed and committed under `drizzle/pg`, then applied with `npm run db:migrate:postgres`. Do not run SQLite `db:push` against PostgreSQL.
