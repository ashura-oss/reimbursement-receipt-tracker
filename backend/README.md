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
- SQLite locally and Turso/libSQL-compatible databases in production
- Azure Database for PostgreSQL Flexible Server through `postgres-js` when `DATABASE_PROVIDER=postgres`
- bcrypt and JSON Web Tokens for authentication
- Multer for multipart receipt uploads
- Frankfurter for free currency exchange rates
- ExcelJS, docx, and pdf-lib for report files

The current implementation has no Luna or other AI dependency. Receipt amounts are entered manually by the user, which keeps the saved claim data reviewable and deterministic.

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

Use Node.js 18 or newer.

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

For production, set `DATABASE_URL` to the Turso/libSQL URL and provide `DATABASE_AUTH_TOKEN`. Local receipt and report files use `STORAGE_DIR`; this storage service can be replaced with a private object-storage provider without changing the controllers.

## Configuration

`.env` is local-only and is never committed. The available values are documented in `.env.example`:

| Variable | Purpose |
| --- | --- |
| `PORT` | Express listening port |
| `CORS_ORIGINS` | Comma-separated browser origins allowed in production; native Android requests do not send an origin |
| `DATABASE_URL` | `file:local.db` locally or a Turso/libSQL URL in production |
| `DATABASE_AUTH_TOKEN` | Optional token for a hosted libSQL database |
| `DATABASE_PROVIDER` | `sqlite` by default; use `postgres` for Azure Database for PostgreSQL |
| `POSTGRES_SSL` | `require` by default for Azure PostgreSQL; use `disable` only for a trusted local server |
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

Generated files are saved under the configured storage directory and recorded in the `reports` and `report_files` tables.

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

## Azure Container Apps deployment

The `infra/` directory contains a source build and deployment path for the Azure trial subscription:

- `Dockerfile` uses Node 22 Bookworm slim and runs as the non-root `node` user.
- `infra/main.bicep` provisions an Azure Container Registry Basic tier, Container Apps Consumption environment (or reuses an existing environment), a pre-authorized user-assigned app identity, private Blob Storage (Standard_LRS), Log Analytics, and PostgreSQL Flexible Server Burstable `Standard_B1ms` with 32 GiB Premium_LRS storage and no HA.
- Blob access uses the Container App managed identity with the `Storage Blob Data Contributor` role. The account has public blob access disabled; `AZURE_STORAGE_ACCOUNT_URL` is used with `DefaultAzureCredential`.
- PostgreSQL is public-network enabled with SSL and an Azure-services firewall rule so the consumption app can connect without a VNet. Tighten this to private networking before handling sensitive production data.
- Admin routes remain disabled by the template (`ADMIN_PORTAL_ENABLED=false`).

The deployment uses Azure Container Registry Basic so the image stays within Azure. ACR Basic adds a small fixed registry charge; Docker Hub or GHCR can avoid that registry resource but would require an external dependency and pull credentials. Container Apps Consumption with `minReplicas=0` has no idle instance charge; Blob and Log Analytics costs follow storage and ingestion. PostgreSQL B1ms is the main fixed cost. Verify current regional pricing in the Azure calculator before committing student credit.

From an Azure CLI login with an active subscription:

```powershell
$env:POSTGRES_ADMIN_PASSWORD = 'use-a-long-random-password'
$env:JWT_SECRET = 'use-a-different-long-random-secret-at-least-32-chars'
./infra/deploy.ps1 -ResourceGroup receipt-claims-rg -Location southeastasia `
  -RegistryName receiptclaimsacr123 -PostgresServerName receiptclaims-pg123 `
  -StorageAccountName receiptclaimssa123
```

The script creates the resource group, deploys foundation resources and role assignments, builds the image in ACR using the backend directory as context, and then deploys the Container App. If the subscription already has a Container Apps environment, pass its full resource ID using `-ExistingEnvironmentId`; this avoids creating a second environment under a one-environment quota. Upload the private workbook after deployment (the signed-in operator needs Blob Data Contributor access):

```powershell
az storage blob upload --account-name receiptclaimssa123 --container-name receipt-claims `
  --name templates/vadi-claim.xlsx --file "$env:USERPROFILE\Downloads\vadi india claim sign_8e46ec99-3fda-4944-824a-122b6ed80ba9.xlsx" `
  --auth-mode login --overwrite
```

The app is configured with `EXCEL_TEMPLATE_STORAGE_KEY=templates/vadi-claim.xlsx`. Use `az containerapp show --name receipt-claims-api --resource-group receipt-claims-rg --query properties.configuration.ingress.fqdn -o tsv` to retrieve its HTTPS hostname. Run PostgreSQL migrations from a controlled environment after retrieving the generated connection settings; do not commit passwords or deployment parameter files.
