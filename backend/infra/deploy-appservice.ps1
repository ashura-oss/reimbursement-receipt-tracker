[CmdletBinding()]
param(
  [string]$ResourceGroup = 'receipt-claims-rg',
  [string]$AppName = 'receipt-claims-api',
  [int]$HealthTimeoutMinutes = 20,
  [ValidateSet('Prebuilt', 'OryxSource')]
  [string]$DeploymentMode = 'Prebuilt',
  [string]$PrebuiltZipPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-AzChecked {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $output = & az @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Azure CLI command failed (exit code $LASTEXITCODE). Review Azure CLI output above." }
  return $output
}

function Get-AppSettings {
  $json = (Invoke-AzChecked @('webapp', 'config', 'appsettings', 'list', '--resource-group', $ResourceGroup, '--name', $AppName, '--output', 'json') | Out-String).Trim()
  if (-not $json) { return @() }
  return @(ConvertFrom-Json -InputObject $json)
}

function Get-LatestDeployment {
  $json = (Invoke-AzChecked @('webapp', 'log', 'deployment', 'list', '--resource-group', $ResourceGroup, '--name', $AppName, '--output', 'json') | Out-String).Trim()
  if (-not $json) { return $null }
  $deployments = @(ConvertFrom-Json -InputObject $json)
  if ($deployments.Count -eq 0) { return $null }
  return $deployments | Sort-Object { $_.start_time } -Descending | Select-Object -First 1
}

function Get-ObjectPropertyValue {
  param([Parameter(Mandatory = $true)]$Object, [Parameter(Mandatory = $true)][string]$Name)
  $property = $Object.PSObject.Properties[$Name]
  if ($property) { return $property.Value }
  return $null
}

function Get-DeploymentState {
  param([Parameter(Mandatory = $true)]$Deployment)
  $text = [string](Get-ObjectPropertyValue -Object $Deployment -Name 'status_text')
    if ($text -match 'success|succeeded|complete') { return 'Succeeded' }
  if ($text -match 'fail|error') { return 'Failed' }
  if ($text -match 'build|deploy|pending|running|progress') { return 'InProgress' }
  $code = 0
  if ([int]::TryParse([string](Get-ObjectPropertyValue -Object $Deployment -Name 'status'), [ref]$code)) {
    if ($code -eq 4) { return 'Succeeded' }
    if ($code -eq 3) { return 'Failed' }
    if ($code -in @(0, 1, 2)) { return 'InProgress' }
  }
  if ((Get-ObjectPropertyValue -Object $Deployment -Name 'active') -eq $true) { return 'InProgress' }
  return 'Unknown'
}

function Test-KuduAvailable {
  param([Parameter(Mandatory = $true)][string]$Uri)
  try {
    $response = Invoke-WebRequest -Uri $Uri -Method Get -UseBasicParsing -TimeoutSec 10
    return ([int]$response.StatusCode -in @(200, 301, 302, 401, 403))
  } catch {
    $response = $_.Exception.Response
    if ($response -and $response.StatusCode) { return ([int]$response.StatusCode -in @(200, 301, 302, 401, 403)) }
    return $false
  }
}

function Wait-AppServiceAndKudu {
  param([Parameter(Mandatory = $true)][string]$HealthUri, [Parameter(Mandatory = $true)][string]$KuduUri)
  $deadline = [DateTimeOffset]::UtcNow.AddMinutes(10)
  while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $siteHealthy = $false
    try {
      $health = Invoke-RestMethod -Uri $HealthUri -Method Get -TimeoutSec 15
      $siteHealthy = $health.data.status -eq 'ok'
    } catch { }
    if ($siteHealthy -and (Test-KuduAvailable -Uri $KuduUri)) { return }
    Write-Host 'Waiting for App Service health and the Kudu deployment endpoint after configuration changes.'
    Start-Sleep -Seconds 15
  }
  throw 'App Service or Kudu did not become ready within 10 minutes. No ZIP upload was started.'
}

function Wait-KuduReady {
  param([Parameter(Mandatory = $true)][string]$KuduUri)
  $deadline = [DateTimeOffset]::UtcNow.AddMinutes(10)
  while ([DateTimeOffset]::UtcNow -lt $deadline) {
    if (Test-KuduAvailable -Uri $KuduUri) { return }
    Write-Host 'Waiting for the Kudu deployment endpoint before ZIP upload.'
    Start-Sleep -Seconds 15
  }
  throw 'Kudu did not become ready within 10 minutes. No ZIP upload was started.'
}

function Test-DeploymentZip {
  param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][bool]$RequirePrebuiltDependencies)
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::OpenRead($Path)
  try {
    $files = @($zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
    $normalizedPaths = @()
    foreach ($entry in $files) {
      $entryPath = $entry.FullName.Replace('\', '/')
      if ($entryPath.StartsWith('/') -or $entryPath -match '(^|/)\.\.?(/|$)' -or $entryPath -match '^[A-Za-z]:') {
        throw "Refusing ZIP with an unsafe path: $($entry.FullName)"
      }
      $isDependency = $entryPath.StartsWith('node_modules/', [StringComparison]::Ordinal)
      if ($entryPath -match '(^|/)\.env($|\.)') {
        throw "Refusing ZIP containing local data, credentials, tests, or private documents: $entryPath"
      }
      if (-not $isDependency -and (
        $entryPath -match '(^|/)(tests?|__tests__|fixtures|test-fixtures|storage|work|reports?)(/|$)' -or
        $entryPath -match '\.(test|spec)\.[cm]?[jt]sx?$' -or
        $entryPath -match '\.(db|db-shm|db-wal|xlsx|xls|docx|pdf)$'
      )) { throw "Refusing ZIP containing app tests, local data, or private documents: $entryPath" }
      $allowed = $entryPath -in @('package.json', 'package-lock.json', 'index.js', 'drizzle.pg.config.js') -or
        $entryPath -match '^(src|assets)/.+$' -or $entryPath -match '^drizzle/pg/.+$' -or
        ($RequirePrebuiltDependencies -and $entryPath -match '^node_modules/.+$')
      if (-not $allowed) { throw "Refusing ZIP with an unexpected file: $entryPath" }
      $normalizedPaths += $entryPath
    }
    foreach ($required in @('package.json', 'package-lock.json', 'index.js', 'drizzle.pg.config.js')) {
      if ($normalizedPaths -notcontains $required) { throw "Deployment ZIP is missing required file '$required'." }
    }
    if (-not ($normalizedPaths | Where-Object { $_ -like 'src/*' })) { throw 'Deployment ZIP has no src/ application files.' }
    if (-not ($normalizedPaths | Where-Object { $_ -like 'drizzle/pg/*' })) { throw 'Deployment ZIP has no PostgreSQL migrations.' }
    if ($RequirePrebuiltDependencies -and -not ($normalizedPaths | Where-Object { $_ -like 'node_modules/*' })) {
      throw 'Prebuilt deployment ZIP must include Linux-compatible production node_modules.'
    }
  } finally { $zip.Dispose() }
}

function Wait-DeploymentAfterUnknownResult {
  param([string]$PreviousDeploymentId)
  $deadline = [DateTimeOffset]::UtcNow.AddMinutes($HealthTimeoutMinutes)
  while ([DateTimeOffset]::UtcNow -lt $deadline) {
    try {
      $latest = Get-LatestDeployment
      $latestId = if ($latest) { [string](Get-ObjectPropertyValue -Object $latest -Name 'id') } else { '' }
      if ($latest -and $latestId -and $latestId -ne $PreviousDeploymentId) {
        $state = Get-DeploymentState -Deployment $latest
        if ($state -eq 'Succeeded') { return }
        if ($state -eq 'Failed') { throw "Azure reports that deployment '$latestId' failed. Inspect it with az webapp log deployment show --resource-group $ResourceGroup --name $AppName --deployment-id $latestId. Do not retry until the failed deployment is understood." }
      }
    } catch {
      if ($_.Exception.Message -match '^Azure reports that deployment') { throw }
      Write-Host 'The deployment status endpoint is still unavailable; checking again before deciding whether the ZIP upload completed.'
    }
    Start-Sleep -Seconds 15
  }
  throw "Azure returned an unclear ZIP deployment result and no completed deployment could be confirmed within $HealthTimeoutMinutes minutes. Inspect `az webapp log deployment list --resource-group $ResourceGroup --name $AppName` before retrying; this script does not submit a duplicate upload."
}

$backendDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("receipt-claims-deploy-" + [guid]::NewGuid().ToString('N'))))
$expectedPrefix = $temporaryBase + [IO.Path]::DirectorySeparatorChar
if (-not $temporaryRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $temporaryRoot) -notmatch '^receipt-claims-deploy-[0-9a-f]{32}$') {
  throw 'Refusing to stage deployment files outside the unique temporary deployment directory.'
}
$stageDirectory = Join-Path $temporaryRoot 'package'
$zipPath = Join-Path $temporaryRoot 'receipt-claims.zip'
if ($DeploymentMode -eq 'OryxSource') { New-Item -ItemType Directory -Path $stageDirectory -Force | Out-Null }

try {
  if ($DeploymentMode -eq 'Prebuilt') {
    if (-not $PrebuiltZipPath) { throw 'Prebuilt mode requires -PrebuiltZipPath pointing to the Linux-built production ZIP.' }
    $zipPath = (Resolve-Path -LiteralPath $PrebuiltZipPath).Path
    Test-DeploymentZip -Path $zipPath -RequirePrebuiltDependencies $true
  } else {
    $requiredFiles = @('package.json', 'package-lock.json', 'index.js', 'drizzle.pg.config.js')
    foreach ($file in $requiredFiles) {
      Copy-Item -LiteralPath (Join-Path $backendDirectory $file) -Destination $stageDirectory
    }
    foreach ($directory in @('src', 'assets')) {
      Copy-Item -LiteralPath (Join-Path $backendDirectory $directory) -Destination $stageDirectory -Recurse
    }
    $drizzleDirectory = Join-Path $stageDirectory 'drizzle'
    New-Item -ItemType Directory -Path $drizzleDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $backendDirectory 'drizzle\pg') -Destination $drizzleDirectory -Recurse

    # Keep the allow-list narrow and strip tests/fixtures embedded in source directories.
    Get-ChildItem -LiteralPath $stageDirectory -Recurse -File | Where-Object {
      $_.Name -match '\.(test|spec)\.[cm]?[jt]sx?$' -or
      $_.Name -match '(^|test|fixture).*image.*\.(png|jpe?g|webp)$' -or
      $_.Name -eq 'testImageFixtures.js'
    } | Remove-Item -Force
    $stagePrefix = [IO.Path]::GetFullPath($stageDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $unwantedDirectories = @(Get-ChildItem -LiteralPath $stageDirectory -Recurse -Directory | Where-Object { $_.Name -in @('tests', '__tests__', 'fixtures', 'test-fixtures') } | Sort-Object FullName -Descending)
    foreach ($directory in $unwantedDirectories) {
      $resolvedDirectory = [IO.Path]::GetFullPath($directory.FullName)
      if (-not $resolvedDirectory.StartsWith($stagePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove a directory outside the staged deployment package.' }
      Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
    }

    $forbidden = Get-ChildItem -LiteralPath $stageDirectory -Recurse -File | Where-Object {
      $_.Name -match '^\.env($|\.)' -or $_.Name -match '\.(db|db-shm|db-wal|xlsx|xls|docx|pdf|zip)$'
    }
    if ($forbidden) { throw "Refusing deployment package because private/test artifacts were found: $($forbidden.Name -join ', ')" }
    Compress-Archive -Path (Join-Path $stageDirectory '*') -DestinationPath $zipPath -CompressionLevel Optimal
    Test-DeploymentZip -Path $zipPath -RequirePrebuiltDependencies $false
  }

  $state = (Invoke-AzChecked @('webapp', 'show', '--resource-group', $ResourceGroup, '--name', $AppName, '--query', 'state', '--output', 'tsv') | Out-String).Trim()
  if ($state -ne 'Running') { throw "App Service '$AppName' is not running (state '$state'). Deploy infrastructure first." }
  $hostName = (Invoke-AzChecked @('webapp', 'show', '--resource-group', $ResourceGroup, '--name', $AppName, '--query', 'defaultHostName', '--output', 'tsv') | Out-String).Trim()
  if (-not $hostName) { throw 'Azure did not return the app hostname.' }
  $healthUri = "https://$hostName/health"
  $kuduHost = $hostName -replace '\.azurewebsites\.net$', '.scm.azurewebsites.net'
  if ($kuduHost -eq $hostName) { $kuduHost = "$AppName.scm.azurewebsites.net" }
  $kuduUri = "https://$kuduHost/"

  # Avoid restarting a healthy app unnecessarily: update only values that differ.
  $existingSettings = Get-AppSettings
  $currentByName = @{}
  foreach ($setting in $existingSettings) { $currentByName[[string]$setting.name] = [string]$setting.value }
  $desiredSettings = @(
    @{ Name = 'POSTGRES_SSL'; Value = 'verify-full' },
    @{ Name = 'ADMIN_PORTAL_ENABLED'; Value = 'false' }
  )
  if ($DeploymentMode -eq 'OryxSource') {
    $desiredSettings += @(
      @{ Name = 'SCM_DO_BUILD_DURING_DEPLOYMENT'; Value = 'true' },
      @{ Name = 'ENABLE_ORYX_BUILD'; Value = 'true' }
    )
  } else {
    $desiredSettings += @(
      @{ Name = 'SCM_DO_BUILD_DURING_DEPLOYMENT'; Value = 'false' },
      @{ Name = 'ENABLE_ORYX_BUILD'; Value = 'false' }
    )
  }
  $changedSettings = @($desiredSettings | Where-Object { -not $currentByName.ContainsKey($_.Name) -or $currentByName[$_.Name] -ne $_.Value })
  $startupChanged = $false
  if ($changedSettings.Count -gt 0) {
    $setArguments = @('webapp', 'config', 'appsettings', 'set', '--resource-group', $ResourceGroup, '--name', $AppName, '--settings')
    $setArguments += @($changedSettings | ForEach-Object { "$($_.Name)=$($_.Value)" })
    $setArguments += @('--output', 'none')
    Invoke-AzChecked $setArguments | Out-Null
  }
  $runFromPackage = $currentByName['WEBSITE_RUN_FROM_PACKAGE']
  if ($runFromPackage -in @('1', 'true', 'True')) {
    Invoke-AzChecked @('webapp', 'config', 'appsettings', 'delete', '--resource-group', $ResourceGroup, '--name', $AppName, '--setting-names', 'WEBSITE_RUN_FROM_PACKAGE', '--output', 'none') | Out-Null
  }
  if ($DeploymentMode -eq 'Prebuilt') {
    $currentStartupFile = (Invoke-AzChecked @('webapp', 'config', 'show', '--resource-group', $ResourceGroup, '--name', $AppName, '--query', 'appCommandLine', '--output', 'tsv') | Out-String).Trim()
    if ($currentStartupFile -ne 'node index.js') {
      Invoke-AzChecked @('webapp', 'config', 'set', '--resource-group', $ResourceGroup, '--name', $AppName, '--startup-file', 'node index.js', '--output', 'none') | Out-Null
      $startupChanged = $true
    }
  }
  if ($DeploymentMode -eq 'OryxSource') {
    if ($changedSettings.Count -gt 0 -or $runFromPackage -in @('1', 'true', 'True') -or $startupChanged) {
      Write-Host 'Oryx settings changed; waiting for App Service health and Kudu readiness before source deployment.'
    } else {
      Write-Host 'Oryx settings already match; confirming App Service health and Kudu readiness before source deployment.'
    }
    Wait-AppServiceAndKudu -HealthUri $healthUri -KuduUri $kuduUri
  } else {
    if ($changedSettings.Count -gt 0 -or $runFromPackage -in @('1', 'true', 'True') -or $startupChanged) {
      Write-Host 'Prebuilt mode settings changed; waiting for Kudu readiness. The existing app health check is intentionally skipped for recovery.'
    }
    Wait-KuduReady -KuduUri $kuduUri
  }

  Write-Host "Deploying $DeploymentMode production package to $AppName (zip size $([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MiB)."
  $previousDeployment = Get-LatestDeployment
  $previousDeploymentId = if ($previousDeployment) { [string](Get-ObjectPropertyValue -Object $previousDeployment -Name 'id') } else { '' }
  $deploymentTag = 'receipt-claims-' + [guid]::NewGuid().ToString('N')
  $deployArguments = @('webapp', 'deploy', '--resource-group', $ResourceGroup, '--name', $AppName, '--src-path', $zipPath, '--type', 'zip', '--tag', $deploymentTag, '--async', 'false', '--timeout', '1800000')
  if ($DeploymentMode -eq 'Prebuilt') { $deployArguments += @('--clean', 'true') }
  $deployArguments += @('--output', 'none')
  try {
    Invoke-AzChecked $deployArguments | Out-Null
  } catch {
    Write-Host 'The ZIP deployment command returned an error. Checking Kudu deployment status before deciding what happened.'
    Wait-DeploymentAfterUnknownResult -PreviousDeploymentId $previousDeploymentId
  }

  $deadline = [DateTimeOffset]::UtcNow.AddMinutes($HealthTimeoutMinutes)
  $healthy = $false
  while ([DateTimeOffset]::UtcNow -lt $deadline) {
    try {
      $response = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 20
      if ($response.data.status -eq 'ok') { $healthy = $true; break }
      Write-Host 'Health endpoint responded but the service is not ready yet.'
    } catch {
      Write-Host 'Waiting for the deployed service to become healthy.'
    }
    Start-Sleep -Seconds 15
  }
  if (-not $healthy) { throw "Deployment command completed, but health verification did not succeed within $HealthTimeoutMinutes minutes at $healthUri. The Azure deployment remains in place for inspection." }
  Write-Host "Deployment succeeded. `/health` confirms the Node process started and loaded its runtime imports: $healthUri"
} finally {
  $resolvedCleanupRoot = [IO.Path]::GetFullPath($temporaryRoot)
  if ($resolvedCleanupRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedCleanupRoot) -match '^receipt-claims-deploy-[0-9a-f]{32}$') {
    Remove-Item -LiteralPath $resolvedCleanupRoot -Recurse -Force -ErrorAction SilentlyContinue
  } else {
    Write-Warning 'Skipped cleanup because the deployment temp path no longer matches its validated target.'
  }
}
