@description('Environment: dev, test, or prod')
@allowed(['dev', 'test', 'prod'])
param environment string = 'dev'

@description('Azure region')
param location string = resourceGroup().location

@description('Enabled countries')
param countries array = ['mx', 'co', 'cl']

@description('Shared Log Analytics Workspace ID')
param logAnalyticsWorkspaceId string

var prefix = 'meridian-pay-${environment}'

// 1. Key Vault for webhook secrets and Salesforce credentials (NFR6)
resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: replace('${prefix}-kv', '-', '')
  location: location
  properties: {
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: subscription().tenantId
    enableRbacAuthorization: true
    enableSoftDelete: true
  }
}

// 2. Azure Service Bus: one queue per country (with DLQ) + topic for downstream consumers
resource serviceBus 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: '${prefix}-sb'
  location: location
  sku: {
    name: 'Standard'
    tier: 'Standard'
  }
}

resource countryQueues 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = [for country in countries: {
  parent: serviceBus
  name: 'payments-${country}'
  properties: {
    lockDuration: 'PT30S'
    maxDeliveryCount: 5
    deadLetteringOnMessageExpiration: true
    defaultMessageTimeToLive: 'P7D'
  }
}]

resource canonicalTopic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: serviceBus
  name: 'canonical-payment-events'
  properties: {
    defaultMessageTimeToLive: 'P7D'
  }
}

resource financeSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = {
  parent: canonicalTopic
  name: 'finance-sub'
  properties: {
    maxDeliveryCount: 5
    deadLetteringOnMessageExpiration: true
  }
}

resource notificationsSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = {
  parent: canonicalTopic
  name: 'notifications-sub'
  properties: {
    maxDeliveryCount: 5
    deadLetteringOnMessageExpiration: true
  }
}

// 3. Azure Cosmos DB (Serverless) for idempotency and payment state
resource cosmosAccount 'Microsoft.DocumentDB/databaseAccounts@2024-02-15-preview' = {
  name: '${prefix}-cosmos'
  location: location
  kind: 'GlobalDocumentDB'
  properties: {
    databaseAccountOfferType: 'Standard'
    consistencyPolicy: {
      defaultConsistencyLevel: 'Session'
    }
    locations: [
      {
        locationName: location
        failoverPriority: 0
      }
    ]
    capabilities: [
      {
        name: 'EnableServerless'
      }
    ]
  }
}

resource cosmosDb 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases@2024-02-15-preview' = {
  parent: cosmosAccount
  name: 'payments'
  properties: {
    resource: {
      id: 'payments'
    }
  }
}

resource paymentStatesContainer 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2024-02-15-preview' = {
  parent: cosmosDb
  name: 'payment_states'
  properties: {
    resource: {
      id: 'payment_states'
      partitionKey: {
        paths: ['/paymentKey']
        kind: 'Hash'
      }
      defaultTtl: 7776000 // 90 days retention
    }
  }
}

// 4. Azure App Service (Linux, Java 21) with System-Assigned Managed Identity
resource appServicePlan 'Microsoft.Web/serverfarms@2022-09-01' = {
  name: '${prefix}-plan'
  location: location
  sku: {
    name: environment == 'prod' ? 'P1v3' : 'B1'
  }
  kind: 'linux'
  properties: {
    reserved: true
  }
}

resource intakeApp 'Microsoft.Web/sites@2022-09-01' = {
  name: '${prefix}-intake'
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: appServicePlan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: 'JAVA|21-java21'
      alwaysOn: environment == 'prod'
      appSettings: [
        {
          name: 'SPRING_PROFILES_ACTIVE'
          value: environment
        }
        {
          name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
          value: appInsights.properties.ConnectionString
        }
      ]
    }
  }
}

// Least-privilege RBAC: allow App Service Managed Identity to read Key Vault secrets
resource kvSecretsUserRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, intakeApp.id, 'KeyVaultSecretsUser')
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
    principalId: intakeApp.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

// Least-privilege RBAC: allow App Service Managed Identity to send/receive Service Bus messages
resource sbDataOwnerRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(serviceBus.id, intakeApp.id, 'ServiceBusDataOwner')
  scope: serviceBus
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '090c5cfd-751d-490a-894a-3ce6f1109419')
    principalId: intakeApp.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

// 5. Observability: Application Insights + DLQ Alerts for ALL countries
resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: '${prefix}-ai'
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
  }
}

resource dlqAlerts 'Microsoft.Insights/metricAlerts@2018-03-01' = [for country in countries: {
  name: '${prefix}-dlq-alert-${country}'
  location: 'global'
  properties: {
    description: 'Triggers when any message enters the dead-letter queue for ${country}'
    severity: 2
    enabled: true
    scopes: [serviceBus.id]
    evaluationFrequency: 'PT1M'
    windowSize: 'PT5M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          name: 'DeadLetterCount'
          metricName: 'DeadletteredMessages'
          operator: 'GreaterThan'
          threshold: 0
          timeAggregation: 'Average'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: ['payments-${country}']
            }
          ]
          criterionType: 'StaticThresholdCriterion'
        }
      ]
    }
  }
}]

