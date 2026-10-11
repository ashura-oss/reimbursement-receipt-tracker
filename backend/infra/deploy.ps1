[CmdletBinding()]
param(
  [string]$ResourceGroup = 'receipt-claims-rg',
  [string]$Location = 'southeastasia',
  [Parameter(Mandatory = $true)][string]$RegistryName,
  [string]$ImageTag = 'latest',
  [Parameter(Mandatory = $true)][string]$PostgresServerName,
  [Parameter(Mandatory = $true)][string]$StorageAccountName,
  [string]$PostgresAdminLogin = 'receiptadmin',
  [string]$PostgresDatabaseName = 'receiptclaims',
  [string]$StorageContainerName = 'receipt-claims',
  [string]$ExcelTemplateStorageKey = 'templates/vadi-claim.xlsx',
  [string]$ExistingEnvironmentId = '',
  [string]$AppName = 'receipt-claims-api',
  [string]$EnvironmentName = 'receipt-claims-env',
  [string]$LogAnalyticsName = 'receipt-claims-logs'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $env:POSTGRES_ADMIN_PASSWORD) { throw 'Set POSTGRES_ADMIN_PASSWORD in this PowerShell session before deployment.' }
if (-not $env:JWT_SECRET) { throw 'Set JWT_SECRET in this PowerShell session before deployment.' }
if ($env:JWT_SECRET.Length -lt 32) { throw 'JWT_SECRET must be at least 32 characters.' }

function Invoke-AzChecked {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  & az @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Azure CLI command failed (exit code $LASTEXITCODE). Review Azure CLI output above; secret values are not repeated here." }
}

$backendDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$template = Join-Path $PSScriptRoot 'main.bicep'
$parameterFile = [IO.Path]::GetTempFileName()
try {
  # Keep credentials out of the process command line and shell history. The temp file is removed in finally.
  $deploymentParameters = [ordered]@{
    location = @{ value = $Location }
    appName = @{ value = $AppName }
    environmentName = @{ value = $EnvironmentName }
    existingEnvironmentId = @{ value = $ExistingEnvironmentId }
    deployApp = @{ value = $false }
    registryName = @{ value = $RegistryName }
    imageTag = @{ value = $ImageTag }
    postgresServerName = @{ value = $PostgresServerName }
    postgresAdminLogin = @{ value = $PostgresAdminLogin }
    postgresAdminPassword = @{ value = $env:POSTGRES_ADMIN_PASSWORD }
    jwtSecret = @{ value = $env:JWT_SECRET }
    postgresDatabaseName = @{ value = $PostgresDatabaseName }
    storageAccountName = @{ value = $StorageAccountName }
    storageContainerName = @{ value = $StorageContainerName }
    excelTemplateStorageKey = @{ value = $ExcelTemplateStorageKey }
    logAnalyticsName = @{ value = $LogAnalyticsName }
  }
  $deploymentParameters | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $parameterFile -Encoding UTF8
  $parameterArg = "@$parameterFile"

  Invoke-AzChecked @('group', 'create', '--name', $ResourceGroup, '--location', $Location, '--output', 'none')

  # Provision the registry, database, storage, identity, and role assignments first.
  # The user-assigned identity has AcrPull before any Container App revision pulls an image.
  Invoke-AzChecked @('deployment', 'group', 'create', '--resource-group', $ResourceGroup, '--template-file', $template, '--parameters', $parameterArg, '--output', 'none')

  # Build using the backend directory itself as context. .dockerignore excludes local data/work.
  Invoke-AzChecked @('acr', 'build', '--registry', $RegistryName, '--image', "receipt-claims-backend:$ImageTag", $backendDirectory)

  # Deploy only after the image exists and the identity role assignment has completed.
  $deploymentParameters.deployApp.value = $true
  $deploymentParameters | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $parameterFile -Encoding UTF8
  Invoke-AzChecked @('deployment', 'group', 'create', '--resource-group', $ResourceGroup, '--template-file', $template, '--parameters', $parameterArg, '--output', 'none')

  Write-Host "Deployment submitted. App: $AppName; registry: $RegistryName.azurecr.io; image tag: $ImageTag"
  Write-Host 'Check readiness with: az containerapp show --name <app-name> --resource-group <resource-group> --query properties.provisioningState -o tsv'
} finally {
  Remove-Item -LiteralPath $parameterFile -Force -ErrorAction SilentlyContinue
}
