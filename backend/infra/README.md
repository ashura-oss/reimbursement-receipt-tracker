# Azure deployment notes

Run from the `backend` directory. The deployment is intentionally parameterized and does not call Azure or store credentials in the repository.

## Prerequisites

- Azure CLI with the Container Apps and Bicep extensions available
- An Azure subscription with the student credit selected
- A resource group region supporting Container Apps Consumption and PostgreSQL Flexible Server
- Globally unique lowercase names for ACR, Storage, and PostgreSQL

## Build and deploy

`deploy.ps1` creates the resource group and foundation resources, grants the user-assigned identity AcrPull and Blob Data Contributor before the app exists, builds the image with `az acr build`, and deploys the Container App. Set `POSTGRES_ADMIN_PASSWORD` and `JWT_SECRET` only in the current PowerShell process. Pass `-ExistingEnvironmentId` when the subscription already has a Container Apps environment; this avoids a second environment under a one-environment quota.

```powershell
az login
az account set --subscription '<subscription-id>'
$env:POSTGRES_ADMIN_PASSWORD = '<random-password>'
$env:JWT_SECRET = '<different-random-secret-at-least-32-chars>'
./infra/deploy.ps1 -ResourceGroup receipt-claims-rg -Location southeastasia `
  -RegistryName receiptclaimsacr123 -PostgresServerName receiptclaims-pg123 `
  -StorageAccountName receiptclaimssa123
```

When reusing an existing environment:

```powershell
./infra/deploy.ps1 -ExistingEnvironmentId '/subscriptions/<id>/resourceGroups/<rg>/providers/Microsoft.App/managedEnvironments/<name>' `
  -RegistryName receiptclaimsacr123 -PostgresServerName receiptclaims-pg123 -StorageAccountName receiptclaimssa123
```

ACR Basic is included so the image and registry remain in Azure. If the student subscription does not cover registry spend, replace the `registries` resource and Container App registry block with GHCR or Docker Hub credentials; that is outside the all-Azure default.

## Template upload

Upload the supplied workbook to the private blob container after deployment and keep the storage key configured as `templates/vadi-claim.xlsx`. The app uses that key when `EXCEL_TEMPLATE_STORAGE_KEY` is set.

## Database initialization

Generate PostgreSQL migrations from the checked-in schema before the first release, then run them against the server from a controlled workstation or CI runner. Do not run SQLite `db:push` against PostgreSQL.

```powershell
npm run db:generate:postgres
$env:DATABASE_PROVIDER = 'postgres'
$env:DATABASE_URL = 'postgresql://receiptadmin:<password>@receiptclaims-pg123.postgres.database.azure.com:5432/receiptclaims?sslmode=require'
npm run db:migrate:postgres
npm run db:seed:postgres
```

The Flexible Server is configured at Burstable `Standard_B1ms`, 32 GiB storage, seven-day backup retention, and no HA. This is sized for a small family app; monitor CPU, memory, storage, and student credit before expanding.
