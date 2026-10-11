# Azure App Service deployment

The current primary production target is **Azure App Service on Linux Basic B1**, Azure Database for PostgreSQL Flexible Server **Burstable B1ms / 32 GiB / no HA**, and Standard_LRS Azure Blob Storage. The Container Apps files (`main.bicep`, `deploy.ps1`) are optional legacy experiments; they are not the primary path. No Azure resources are created by tests or documentation checks.

The App Service has a system-assigned managed identity. Blob public access is disabled, the receipt container is private, and the app identity receives only `Storage Blob Data Contributor` on its storage account. PostgreSQL public networking is deny-by-default; `provision-appservice.ps1` adds individual firewall rules for the App Service outbound IPv4 addresses. App Service settings keep `ADMIN_PORTAL_ENABLED=false`.

## Requirements

- Azure CLI logged into the intended subscription, with Bicep support.
- A region with App Service Linux B1 and PostgreSQL Flexible Server B1ms quota.
- Globally unique lowercase names for the web app, PostgreSQL server, and storage account.
- A PostgreSQL password and a separate JWT signing secret supplied through PowerShell variables. Do not put these in Git, command history, or deployment parameter files.
- For the workbook, a local copy of the supplied template and permission for your Azure identity to upload to the private blob account.

## Provision the Azure resources

Run from `backend/`. Supply credentials from a password manager or another secure source into the current PowerShell session. The script writes a temporary parameter file, removes it in `finally`, deploys the Bicep template, and restricts PostgreSQL ingress to the App Service's reported outbound IPs. An optional developer IPv4 can be allowed temporarily for migrations; remove that rule afterwards.

```powershell
az login
az account set --subscription '<subscription-id>'
$pgPassword = Read-Host 'PostgreSQL admin password' -AsSecureString
$env:PG_DEPLOY_PASSWORD = [System.Net.NetworkCredential]::new('', $pgPassword).Password
$jwtSecret = Read-Host 'JWT signing secret (32+ characters)' -AsSecureString
$env:JWT_DEPLOY_SECRET = [System.Net.NetworkCredential]::new('', $jwtSecret).Password
./infra/provision-appservice.ps1 `
  -ResourceGroup receipt-claims-rg -Location southeastasia `
  -AppName receipt-claims-api -PostgresServerName receiptclaims-pg123 `
  -StorageAccountName receiptclaimssa123 `
  -PostgresAdminPassword $env:PG_DEPLOY_PASSWORD -JwtSecret $env:JWT_DEPLOY_SECRET
```

Use the values returned by Azure outputs or the parameters you entered for the app/database/storage names. The App Service is configured to listen on port 8080, use Node 22, and expose `/health` for health checks. The deployment helper defaults to a prebuilt Linux ZIP; Oryx source builds are an explicit optional mode. Verify quota and resource availability before provisioning; this template requests the sizes stated above.

## Run PostgreSQL migrations

Generate migrations only when the Drizzle schema changes, commit the generated SQL under `drizzle/pg`, and apply committed migrations from a trusted workstation or CI runner. Set `DATABASE_URL` to the App Service's PostgreSQL hostname and the same database/admin credentials entered above. Use `POSTGRES_SSL=verify-full` for the Azure PostgreSQL hostname.

```powershell
$env:DATABASE_PROVIDER = 'postgres'
$env:POSTGRES_SSL = 'verify-full'
$env:DATABASE_URL = 'postgresql://<url-encoded-user>:<url-encoded-password>@<server>.postgres.database.azure.com:5432/receiptclaims?sslmode=require'
npm run db:migrate:postgres
npm run db:seed:postgres
```

If a workstation firewall rule was added using `-DeveloperIpAddress`, remove it after migrations:

```powershell
az postgres flexible-server firewall-rule delete `
  --resource-group receipt-claims-rg --server-name receiptclaims-pg123 `
  --name temporary-developer --yes
```

Firewall command arguments follow Azure CLI's PostgreSQL command schema: `--server-name` identifies the database server and `--name` identifies the firewall rule. To inspect rules, use:

```powershell
az postgres flexible-server firewall-rule list `
  --resource-group receipt-claims-rg --server-name receiptclaims-pg123 --output table
```

When replacing the legacy `AllowAzureServices` rule, first create one rule per App Service outbound IP with `az postgres flexible-server firewall-rule create --resource-group <rg> --server-name <postgres-server> --name <rule-name> --start-ip-address <ip> --end-ip-address <ip>`. Verify the complete list before removing the broad rule:

```powershell
az postgres flexible-server firewall-rule delete `
  --resource-group receipt-claims-rg --server-name receiptclaims-pg123 `
  --name AllowAzureServices --yes
```

The app uses the managed identity to access Blob Storage; production must not use storage account keys or public containers. Grant your own Azure identity the minimum blob upload role if you need to upload the workbook.

## Upload the workbook template

```powershell
az storage blob upload --auth-mode login `
  --account-name receiptclaimssa123 --container-name receipt-claims `
  --name templates/vadi-claim.xlsx --file '<path-to-private-workbook>' --overwrite
```

The app setting `EXCEL_TEMPLATE_STORAGE_KEY` points to that private object. No workbook, receipt image, or report is included in the deploy package.

## Deploy backend

The default recovery/production mode deploys a Linux-built ZIP. To create it, run the repository's **Build backend Linux deployment bundle** workflow manually from GitHub Actions (`workflow_dispatch`). It installs Node 22 dependencies, runs the backend tests, prunes dev dependencies, and publishes a seven-day `receipt-claims-linux` artifact. Download and extract the ZIP locally. The archive must contain app source, PostgreSQL migrations, licensed fonts, and Linux-compatible production `node_modules`; the helper validates its paths and rejects `.env`, local app data, tests/fixtures, reports, and unexpected files while allowing ordinary dependency package tests/docs. This mode sets the startup command to `node index.js` only if needed, turns Oryx build flags off only if needed, deploys with a clean target directory, and waits for Kudu without requiring the existing app to be healthy. It then checks `/health`; since that route is served only after Node loads `index.js` and its static imports, this verifies runtime module loading.

```powershell
./infra/deploy-appservice.ps1 `
  -ResourceGroup receipt-claims-rg -AppName receipt-claims-api `
  -DeploymentMode Prebuilt -PrebuiltZipPath '<path-to-receipt-claims-linux.zip>'
```

An optional `OryxSource` mode packages source without `node_modules` and explicitly enables Oryx build automation. Use it only when the service is healthy before upload and a source build is intended:

```powershell
./infra/deploy-appservice.ps1 `
  -ResourceGroup receipt-claims-rg -AppName receipt-claims-api `
  -DeploymentMode OryxSource
```

Both modes update only app settings that differ and force `ADMIN_PORTAL_ENABLED=false`. If Azure returns an unclear deploy response, the helper checks Kudu deployment status and never resubmits a duplicate ZIP. If post-deploy health/runtime startup fails, inspect App Service and deployment logs; the uploaded ZIP remains in place for diagnosis. The app's admin portal remains off until a separate explicitly authorized release changes `ADMIN_PORTAL_ENABLED`.

## Capacity and ongoing costs

This is a small low-traffic starting point, not a production SLA. The plan is Basic B1, PostgreSQL is B1ms with 32 GiB storage, and Blob is Standard LRS; actual billing depends on region, uptime, backups, and usage. The B1 App Service and Flexible Server are billed even at zero traffic. Keep the app, database, and storage in the same region, configure student credits before deployment, and review Azure Cost Management after resources are created. Container Apps Consumption and ACR are not required for this deployment.
