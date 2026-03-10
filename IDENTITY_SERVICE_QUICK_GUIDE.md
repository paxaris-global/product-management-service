# Identity Service Integration Guide

## Quick Start: Store URI & HTTP Method with Roles

### Enhanced Role Request

```java
// RoleCreationRequest now includes:
RoleCreationRequest {
    name: "admin",
    description: "Admin role",
    uri: "/api/products",          // ✅ NEW
    httpMethod: "GET"              // ✅ NEW
}
```

### Update Your Role Creation Code

```java
@Override
public void createClientRoles(String realm, String clientName, 
                            List<RoleCreationRequest> roleRequests, String token) {
    // ... existing Keycloak creation code ...
    
    for (RoleCreationRequest role : roleRequests) {
        // Register in Product Manager with URI + HTTP Method
        RoleRequest pmRequest = new RoleRequest();
        pmRequest.setRealmName(realm);
        pmRequest.setProductName(clientName);
        pmRequest.setRoleName(role.getName());
        
        // ✅ ADD THESE:
        pmRequest.setUri(role.getUri());
        pmRequest.setHttpMethod(role.getHttpMethod());
        
        webClient.post()
            .uri("/project/roles/save-or-update")
            .bodyValue(pmRequest)
            .retrieve()
            .toBodilessEntity()
            .block();
    }
}
```

## Fix File Upload to Product Manager

### Use ProvisioningServiceHelper

```java
import com.paxaris.product_management_service.service.ProvisioningServiceHelper;

public void provisionRepository(String realm, String clientName, MultipartFile zipFile) {
    ProvisioningServiceHelper helper = new ProvisioningServiceHelper(
        restTemplate, 
        "http://project-manager:8088"
    );
    
    String repoName = String.format("%s-%s", realm, clientName).toLowerCase();
    Map<String, String> response = helper.provisionRepository(repoName, zipFile);
    
    log.info("Repository provisioned: {}", response.get("repository"));
}
```

### Database Schema

```sql
-- Roles are stored here with realm and product info
realm_product_role (id, realm_name, product_name, role_name)

-- URIs and HTTP methods are stored here
realm_product_role_url (id, role_id, uri, http_method)
```

### Example POST Request

```bash
POST http://identity-service:8080/realms/demo/clients/myapp/roles
Authorization: Bearer {token}
Content-Type: application/json

[
  {
    "name": "admin",
    "description": "Admin role",
    "uri": "/api/products",
    "httpMethod": "GET"
  },
  {
    "name": "editor",
    "description": "Editor role",
    "uri": "/api/products",
    "httpMethod": "POST"
  }
]
```

## Verify Integration

```bash
# Check roles in Product Manager
curl http://localhost:8088/project/roles

# Check provisioning service health
curl http://localhost:8088/project/provision/health
```

**Status**: ✅ Implementation complete and tested (15/15 tests pass)

