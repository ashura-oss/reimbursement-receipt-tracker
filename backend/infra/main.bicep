param location string = resourceGroup().location
param appName string = 'receipt-claims-api'
param environmentName string = 'receipt-claims-env'
param existingEnvironmentId string = ''
param deployApp bool = true
param registryName string
param imageTag string = 'latest'
param postgresServerName string
param postgresAdminLogin string = 'receiptadmin'
@secure()
param postgresAdminPassword string
@secure()
param jwtSecret string
param postgresDatabaseName string = 'receiptclaims'
param storageAccountName string
param storageContainerName string = 'receipt-claims'
param excelTemplateStorageKey string = 'templates/vadi-claim.xlsx'
param logAnalyticsName string = 'receipt-claims-logs'

var acrLoginServer = '${registryName}.azurecr.io'
var containerImage = '${acrLoginServer}/receipt-claims-backend:${imageTag}'
var postgresDatabaseUrl = 'postgresql://${postgresAdminLogin}:${uriComponent(postgresAdminPassword)}@${postgresServerName}.postgres.database.azure.com:5432/${postgresDatabaseName}?sslmode=require'
var storageAccountUrl = 'https://${storageAccountName}.blob.${az.environment().suffixes.storage}'

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: registryName
  location: location
  sku: { name: 'Basic' }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: logAnalyticsName
  location: location
  properties: { sku: { name: 'PerGB2018' }, retentionInDays: 30, features: { enableLogAccessUsingOnlyResourcePermissions: true } }
}

resource blobStorage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageAccountName
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: {
    accessTier: 'Hot'
    allowBlobPublicAccess: false
    minimumTlsVersion: 'TLS1_2'
    publicNetworkAccess: 'Enabled'
    supportsHttpsTrafficOnly: true
  }
}

resource blobContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  name: '${storageAccountName}/default/${storageContainerName}'
  properties: { publicAccess: 'None' }
  dependsOn: [ blobStorage ]
}

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: postgresServerName
  location: location
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    administratorLogin: postgresAdminLogin
    administratorLoginPassword: postgresAdminPassword
    version: '16'
    storage: { storageSizeGB: 32, autoGrow: 'Disabled', type: 'Premium_LRS' }
    backup: { backupRetentionDays: 7, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
    authConfig: { activeDirectoryAuth: 'Disabled', passwordAuth: 'Enabled' }
    network: { publicNetworkAccess: 'Enabled' }
  }
}

resource postgresFirewall 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: postgres
  name: 'AllowAzureServices'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

resource appIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${appName}-identity'
  location: location
}

resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, appIdentity.id, 'acrpull')
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')
    principalId: appIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource blobContributor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(blobStorage.id, appIdentity.id, 'blob-contributor')
  scope: blobStorage
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
    principalId: appIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = if (empty(existingEnvironmentId)) {
  name: environmentName
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: { customerId: logs.properties.customerId, sharedKey: logs.listKeys().primarySharedKey }
    }
    workloadProfiles: [{ name: 'Consumption', workloadProfileType: 'Consumption' }]
  }
}

resource app 'Microsoft.App/containerApps@2024-03-01' = if (deployApp) {
  name: appName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${appIdentity.id}': {} }
  }
  properties: {
    managedEnvironmentId: empty(existingEnvironmentId) ? environment.id : existingEnvironmentId
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: { external: true, targetPort: 3000, transport: 'auto', allowInsecure: false }
      registries: [{ server: acrLoginServer, identity: appIdentity.id }]
      secrets: [
        { name: 'database-url', value: postgresDatabaseUrl }
        { name: 'jwt-secret', value: jwtSecret }
      ]
    }
    template: {
      containers: [{
        name: 'api'
        image: containerImage
        env: [
          { name: 'NODE_ENV', value: 'production' }
          { name: 'PORT', value: '3000' }
          { name: 'DATABASE_PROVIDER', value: 'postgres' }
          { name: 'DATABASE_URL', secretRef: 'database-url' }
          { name: 'POSTGRES_SSL', value: 'verify-full' }
          { name: 'ADMIN_PORTAL_ENABLED', value: 'false' }
          { name: 'STORAGE_PROVIDER', value: 'azure' }
          { name: 'AZURE_STORAGE_ACCOUNT_URL', value: storageAccountUrl }
          { name: 'AZURE_STORAGE_CONTAINER', value: storageContainerName }
          { name: 'EXCEL_TEMPLATE_STORAGE_KEY', value: excelTemplateStorageKey }
          { name: 'JWT_SECRET', secretRef: 'jwt-secret' }
        ]
        resources: {
          cpu: json('0.5')
          memory: '1Gi'
        }
      }]
      scale: { minReplicas: 0, maxReplicas: 1, rules: [{ name: 'http', http: { metadata: { concurrentRequests: '20' } } }] }
    }
  }
  dependsOn: [ acrPull, blobContributor, blobContainer, postgresFirewall ]
}

output storageAccountUrl string = storageAccountUrl
output registryLoginServer string = acrLoginServer
output postgresFqdn string = postgres.properties.fullyQualifiedDomainName
output appIdentityClientId string = appIdentity.properties.clientId
