# Reports, storage, and admin extension

## Storage

`src/services/storageService.js` is the provider boundary. The current provider writes private files under `STORAGE_DIR` (default `backend/storage`). Receipt controllers use `saveReceiptImage` and `deleteStorageObject`; changing those functions to an Azure Blob adapter does not change route code. Storage keys are normalized and traversal is rejected.

## Exchange rates

`exchangeRateService.getRate(currency, date)` calls Frankfurter's date-scoped endpoint and caches the returned rate in `exchange_rates`. SGD to SGD always returns `1`. `calculateSgdEquivalent` rounds the calculated SGD amount to cents and returns the exact rate to persist with a receipt.

## Reports

`reportService.generateClaimReport` generates Excel, Word, and PDF files and records each file in `report_files`. It reads the receipts linked to a claim, preserving each receipt image in the Word/PDF outputs. Set `EXCEL_TEMPLATE_PATH` to use the supplied workbook as the base. The adapter finds the header row containing `Amount Excluding GST` and maps common headers such as Currency, Exchange Rate, and S$ Equivalent; if no template is configured, it creates a clean `Expense Claim` workbook.

Report metadata is available at `GET /api/reports/:id`; files are downloaded through the user-scoped `GET /api/report-files/:id/download` route.

## Future admin portal

Admin routes are in `src/routes/adminRoutes.js` and mounted only when `ADMIN_PORTAL_ENABLED=true`. They require a JWT with role `ADMIN`; when disabled, `/api/admin/*` remains a 404. Routes expose user and claim summaries, claim receipts, and private receipt image streaming for a future portal.
