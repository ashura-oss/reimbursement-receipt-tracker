[CmdletBinding()]
param(
  [string]$ResourceGroup = 'receipt-claims-rg',
  [string]$Location = 'southeastasia',
  [Parameter(Mandatory = $true)][string]$AppName,
  [Parameter(Mandatory = $true)][string]$PostgresServerName,
  [Parameter(Mandatory = $true)][string]$StorageAccountName,
  [Parameter(Mandatory = $true)][string]$PostgresAdminPassword,
  [Parameter(Mandatory = $true)][string]$JwtSecret,
  [string]$PostgresAdminLogin = 'receiptadmin',
  [string]$PostgresDatabaseName = 'receiptclaims',
  [string]$StorageContainerName = 'receipt-claims',
  [string]$ExcelTemplateStorageKey = 'templates/vadi-claim.xlsx',
  [string]$DeveloperIpAddress = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PostgresAdminPassword.Length -lt 16) { throw 'PostgresAdminPassword must be at least 16 characters.' }
if ($JwtSecret.Length -lt 32) { throw 'JwtSecret must be at least 32 characters.' }

function Invoke-AzChecked {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $output = & az @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Azure CLI command failed (exit code $LASTEXITCODE). Review Azure CLI output above; secret values are not repeated here." }
  return $output
}

$backendDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$template = Join-Path $PSScriptRoot 'appservice.bicep'
$parameterFile = Join-Path ([IO.Path]::GetTempPath()) ("receipt-claims-parameters-" + [guid]::NewGuid().ToString('N') + '.json')
try {
  $parameters = [ordered]@{
    location = @{ value = $Location }
    appName = @{ value = $AppName }
    postgresServerName = @{ value = $PostgresServerName }
    postgresAdminLogin = @{ value = $PostgresAdminLogin }
    postgresAdminPassword = @{ value = $PostgresAdminPassword }
    postgresDatabaseName = @{ value = $PostgresDatabaseName }
    storageAccountName = @{ value = $StorageAccountName }
    storageContainerName = @{ value = $StorageContainerName }
    jwtSecret = @{ value = $JwtSecret }
    excelTemplateStorageKey = @{ value = $ExcelTemplateStorageKey }
  }
  $parameters | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $parameterFile -Encoding utf8
  Invoke-AzChecked @('group', 'create', '--name', $ResourceGroup, '--location', $Location, '--output', 'none') | Out-Null
  Invoke-AzChecked @('deployment', 'group', 'create', '--resource-group', $ResourceGroup, '--template-file', $template, '--parameters', "@$parameterFile", '--output', 'none') | Out-Null

  # PostgreSQL Flexible Server's public endpoint starts deny-by-default. Open only the app's outbound IPs.
  $outbound = (Invoke-AzChecked @('webapp', 'show', '--resource-group', $ResourceGroup, '--name', $AppName, '--query', 'outboundIpAddresses', '--output', 'tsv') | Out-String).Trim()
  $ips = @($outbound -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}$' } | Select-Object -Unique)
  if ($ips.Count -eq 0) { throw 'Azure did not return App Service outbound IPv4 addresses; PostgreSQL remains deny-by-default.' }
  for ($index = 0; $index -lt $ips.Count; $index++) {
    $ruleName = 'app-egress-{0:D3}' -f ($index + 1)
    Invoke-AzChecked @('postgres', 'flexible-server', 'firewall-rule', 'create', '--resource-group', $ResourceGroup, '--server-name', $PostgresServerName, '--name', $ruleName, '--start-ip-address', $ips[$index], '--end-ip-address', $ips[$index], '--output', 'none') | Out-Null
  }
  if ($DeveloperIpAddress) {
    if ($DeveloperIpAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw 'DeveloperIpAddress must be an IPv4 address.' }
    Invoke-AzChecked @('postgres', 'flexible-server', 'firewall-rule', 'create', '--resource-group', $ResourceGroup, '--server-name', $PostgresServerName, '--name', 'temporary-developer', '--start-ip-address', $DeveloperIpAddress, '--end-ip-address', $DeveloperIpAddress, '--output', 'none') | Out-Null
  }
  Write-Host "Provisioned App Service, PostgreSQL, and private Blob storage in $ResourceGroup. PostgreSQL accepts traffic only from $($ips.Count) App Service outbound IP(s)."
  if ($DeveloperIpAddress) { Write-Host 'Remove firewall rule temporary-developer after migrations and other workstation tasks.' }
  Write-Host "App: $AppName; database: $PostgresDatabaseName; storage container: $StorageContainerName. No secret values were displayed."
} finally {
  Remove-Item -LiteralPath $parameterFile -Force -ErrorAction SilentlyContinue
}
