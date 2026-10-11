# Reports, storage, and admin extension

## Storage

`src/services/storageService.js` is the provider boundary. Local development writes private files under `STORAGE_DIR` (default `backend/storage`). Set `STORAGE_PROVIDER=azure` with `AZURE_STORAGE_ACCOUNT_URL` and a managed identity (or use `AZURE_STORAGE_CONNECTION_STRING` for local Azure testing) to store blobs privately. Receipt controllers use `saveReceiptImage` and `deleteStorageObject`; route code does not change between providers. Storage keys are normalized and traversal is rejected.

## Exchange rates

`exchangeRateService.getRate(currency, date)` calls Frankfurter's date-scoped endpoint and caches the returned rate in `exchange_rates`. SGD to SGD always returns `1`. `calculateSgdEquivalent` rounds the calculated SGD amount to cents and returns the exact rate to persist with a receipt.

## Reports

`reportService.generateClaimReport` generates Excel, Word, and PDF files and records each file in `report_files`. It validates every linked receipt image before creating outputs; a missing, damaged, or unavailable image makes the report fail rather than silently omitting evidence. Set `EXCEL_TEMPLATE_PATH` to use a local workbook as the base, or `EXCEL_TEMPLATE_STORAGE_KEY` to load a private workbook through the configured storage provider (the Azure deployment passes this key so the workbook does not need to be committed into the image). The adapter finds the row-20 claim table in the Vadi template, maps `No`, `Date`, `Accounts Code`, `Description`, currency, `Exchange Rate`, `S$ Equivalent`, and `Cost centre 2`, expands the data region when a claim exceeds the sample rows, and updates totals and downstream formulas. The app captures the total amount paid and does not collect a GST breakdown, so generated workbooks relabel `Amount Excluding GST` as `Receipt Amount (as paid)` and leave the separate GST column empty; no tax amount is inferred. If a configured template cannot be read or does not contain that table, generation fails with a typed 503 or 422 response; if no template is configured, it creates a clean `Expense Claim` workbook.

Report metadata is available at `GET /api/reports/:id` and `GET /api/reports/claims/:claimId/latest`, both returning `{ report, files }` for the authenticated owner; the latest route returns 404 when no report exists. Files are downloaded through the user-scoped `GET /api/report-files/:id/download` route.

## Future admin portal

Admin routes are in `src/routes/adminRoutes.js` and mounted only when `ADMIN_PORTAL_ENABLED=true`. They require a JWT with role `ADMIN`; when disabled, `/api/admin/*` remains a 404. Routes expose user and claim summaries, claim receipts, and private receipt image streaming for a future portal.
