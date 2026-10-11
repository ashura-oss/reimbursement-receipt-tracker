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
| `DATABASE_URL` | `file:local.db` locally or a Turso/libSQL URL in production |
| `DATABASE_AUTH_TOKEN` | Optional token for a hosted libSQL database |
| `JWT_SECRET` | Secret used to sign access tokens |
| `JWT_EXPIRES_IN` | Token lifetime, such as `7d` |
| `STORAGE_DIR` | Local private file-storage directory |
| `EXCEL_TEMPLATE_PATH` | Optional path to the reimbursement workbook template |
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
