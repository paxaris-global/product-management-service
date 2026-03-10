# Product Manager Provisioning Service - Integration Guide

## Overview

This document explains how to properly integrate the Product Management Service's provisioning endpoint with your Identity Service using RestTemplate or WebClient.

---

## Problem Statement

The Identity Service needs to upload a ZIP file to the Product Manager's provisioning endpoint when creating new client products. The error commonly encountered is:

```
I/O error on POST request for "http://project-manager:8088/project/provision/upload": 
Error writing request body to server
```

This occurs because `RestTemplate.postForEntity()` doesn't properly handle multipart file uploads by default.

---

## Solution 1: Using RestTemplate (Recommended for Identity Service)

The Product Manager now provides a helper utility (`ProvisioningServiceHelper`) that handles proper multipart encoding.

### Implementation in Identity Service

```java
package com.paxaris.identity_service.service.impl;

import com.paxaris.product_management_service.service.ProvisioningServiceHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class YourProvisioningService {
    
    private final RestTemplate restTemplate;
    private final String productManagerBaseUrl = "http://project-manager:8088";
    
    public YourProvisioningService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    /**
     * Provision a new GitHub repository through Product Manager
     */
    public void provisionRepository(String realmName, String adminUsername, 
                                   String clientName, MultipartFile zipFile) {
        try {
            // 1. Generate repository name
            String repoName = String.format("%s-%s-%s", 
                realmName, adminUsername, clientName).toLowerCase();
            
            // 2. Use the helper to send multipart data properly
            ProvisioningServiceHelper helper = new ProvisioningServiceHelper(
                restTemplate, 
                productManagerBaseUrl
            );
            
            // 3. Send the provisioning request
            Map<String, String> response = helper.provisionRepository(repoName, zipFile);
            
            log.info("✅ Repository provisioned successfully: {}", repoName);
            log.info("Response: {}", response);
            
        } catch (Exception e) {
            log.error("❌ Failed to provision repository", e);
            throw new RuntimeException("Provisioning failed: " + e.getMessage(), e);
        }
    }
}
```

### Key Points

1. **Use the Helper Class**: `ProvisioningServiceHelper` handles all the complexity of multipart encoding
2. **Pass MultipartFile directly**: The helper converts it properly for transmission
3. **Error Handling**: The helper includes proper error handling and logging
4. **No Manual Multipart Building**: Don't try to build multipart bodies manually with RestTemplate

---

## Solution 2: Using WebClient (Alternative)

If you prefer Spring 5+ WebClient:

```java
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.core.io.FileSystemResource;

public void provisionWithWebClient(String repoName, Path zipFilePath) {
    WebClient webClient = WebClient.builder()
        .baseUrl("http://project-manager:8088")
        .build();
    
    Map<String, Object> response = webClient.post()
        .uri(uriBuilder -> uriBuilder
            .path("/project/provision/upload")
            .queryParam("repoName", repoName)
            .build())
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData("zipFile", 
            new FileSystemResource(zipFilePath.toFile())))
        .retrieve()
        .bodyToMono(Map.class)
        .block();
    
    log.info("Provisioning response: {}", response);
}
```

---

## Endpoint Details

### POST /project/provision/upload

**Purpose**: Create a new GitHub repository and upload project files

**Parameters**:
- `repoName` (query): Repository name to create (e.g., `demo-john-myapp`)
- `zipFile` (form file): ZIP file containing project contents

**Request Headers**:
- `Content-Type: multipart/form-data` (automatically set by helper)

**Success Response (200)**:
```json
{
  "status": "success",
  "repository": "demo-john-myapp",
  "organization": "your-org-name",
  "message": "Repository created and files uploaded successfully",
  "tempPath": "/tmp/upload-extract-123456"
}
```

**Error Response (500)**:
```json
{
  "error": "Failed to provision repository: {error details}"
}
```

---

## Integration Workflow

Here's the complete flow when creating a new product:

```java
@Service
@Transactional
public class KeycloakProductServiceImpl implements KeycloakProductService {
    
    private final RestTemplate restTemplate;
    
    @Override
    public void createProduct(String realm, String clientName, 
                            MultipartFile templateZip, String token) {
        try {
            // Step 1: Create Keycloak client
            String clientUUID = createKeycloakClient(realm, clientName, token);
            log.info("✅ Keycloak client created: {}", clientUUID);
            
            // Step 2: Create client roles
            createClientRoles(realm, clientName, roleRequests, token);
            log.info("✅ Roles created");
            
            // Step 3: Provision GitHub repository (THIS IS WHERE PROVISIONING HAPPENS)
            provisionRepository(realm, "admin-user", clientName, templateZip);
            log.info("✅ Repository provisioned");
            
            log.info("✅ Product creation completed successfully");
            
        } catch (Exception e) {
            log.error("❌ Product creation failed", e);
            throw new RuntimeException("Failed to create product: " + e.getMessage(), e);
        }
    }
    
    private void provisionRepository(String realm, String adminUsername, 
                                    String clientName, MultipartFile zipFile) {
        ProvisioningServiceHelper helper = new ProvisioningServiceHelper(
            restTemplate,
            "http://project-manager:8088"
        );
        
        String repoName = String.format("%s-%s-%s", 
            realm, adminUsername, clientName).toLowerCase();
        
        Map<String, String> response = helper.provisionRepository(repoName, zipFile);
        log.info("Repository provisioned: {}", response);
    }
}
```

---

## Configuration in Identity Service

No special configuration is needed. However, ensure you have:

1. **RestTemplate Bean**:
```java
@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

2. **Proper Dependency**: The helper class is in the Product Manager service, so add it to your classpath or copy the implementation.

---

## Troubleshooting

### Problem: "I/O error on POST request"

**Cause**: Incorrect multipart encoding

**Solution**: 
- ✅ Use `ProvisioningServiceHelper` instead of manual RestTemplate calls
- ❌ Don't use `restTemplate.postForEntity()` with multipart directly
- ❌ Don't try to build multipart bodies manually

### Problem: "ZIP file is empty or missing"

**Cause**: File not properly transmitted

**Solution**:
- Verify ZIP file is not empty before calling helper
- Check file permissions
- Ensure file is readable

### Problem: "Configuration error: GITHUB_TOKEN missing"

**Cause**: GitHub credentials not configured in Product Manager

**Solution**:
- Set environment variables:
  ```bash
  export GITHUB_TOKEN=your-token
  export GITHUB_ORG=your-org
  ```
- Or configure in `application.properties`:
  ```properties
  github.token=${GITHUB_TOKEN}
  github.org=${GITHUB_ORG}
  ```

### Problem: "Failed to create GitHub repository"

**Cause**: GitHub API rate limiting or permission issues

**Solution**:
- Ensure token has `repo` and `public_repo` scopes
- Check rate limits: `https://api.github.com/rate_limit`
- Wait before retrying

---

## File Size Limits

The provisioning endpoint supports files up to **100MB** by default.

**Configuration** (if you need to change):

In Product Manager's `application.properties`:
```properties
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
```

---

## Testing

### Manual cURL Test

```bash
# Create test ZIP
mkdir -p test-project/src test-project/target
echo "Hello World" > test-project/README.md
zip -r test-project.zip test-project/

# Send to Product Manager
curl -X POST "http://localhost:8088/project/provision/upload?repoName=test-repo-123" \
  -F "zipFile=@test-project.zip" \
  -v
```

### Java Unit Test

```java
@SpringBootTest
public class ProvisioningIntegrationTest {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Test
    public void testProvisioningEndpoint() throws Exception {
        // Create test ZIP
        MockMultipartFile zipFile = new MockMultipartFile(
            "zipFile",
            "test.zip",
            "application/zip",
            "PK\u0003\u0004...".getBytes()
        );
        
        // Use helper
        ProvisioningServiceHelper helper = new ProvisioningServiceHelper(
            restTemplate,
            "http://localhost:8088"
        );
        
        Map<String, String> response = helper.provisionRepository(
            "test-repo",
            zipFile
        );
        
        assertNotNull(response);
        assertEquals("success", response.get("status"));
    }
}
```

---

## Best Practices

1. **Always use ProvisioningServiceHelper**: It handles all edge cases
2. **Validate ZIP before sending**: Check size and format
3. **Log repository names**: For audit trail
4. **Handle errors gracefully**: Provide meaningful messages to users
5. **Retry logic**: Implement exponential backoff for transient failures
6. **Timeout settings**: Set appropriate timeouts (default 30s might be too short for large files)

```java
// Example with custom timeout
RestTemplate restTemplate = new RestTemplate(
    new HttpComponentsClientHttpRequestFactory()
);
HttpComponentsClientHttpRequestFactory factory = 
    (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
factory.setConnectTimeout(60000); // 60 seconds
factory.setReadTimeout(60000);
```

---

## API Health Check

To verify the provisioning service is ready:

```bash
curl http://localhost:8088/project/provision/health
```

**Response**:
```json
{
  "githubTokenConfigured": true,
  "githubOrgConfigured": true,
  "ready": true,
  "githubOrg": "your-organization"
}
```

---

## Support

For issues or questions:
1. Check the logs in both services
2. Verify network connectivity between services
3. Confirm GitHub credentials and API access
4. Review the Integration Workflow section above

---

**Last Updated**: March 10, 2026

