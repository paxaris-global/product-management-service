# ProvisioningService - GitHub Repository Automation

This service provides automated GitHub repository creation and project file provisioning capabilities for the Product Management Service.

## Overview

The `ProvisioningService` enables:
- Creating private GitHub repositories in your organization
- Uploading project files from ZIP archives
- Single-commit uploads for efficient provisioning
- Repository name generation based on realm/client conventions

## Configuration

Add the following environment variables or application properties:

```properties
# GitHub Configuration
github.token=${GITHUB_TOKEN}
github.org=${GITHUB_ORG}
```

### Environment Variables

- **GITHUB_TOKEN**: Personal Access Token (PAT) or GitHub App token with `repo` and `admin:org` permissions
- **GITHUB_ORG**: Your GitHub organization name

### Example Configuration

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export GITHUB_ORG=your-organization-name
```

## API Endpoints

### 1. Provision Repository

**Endpoint:** `POST /project/provision/upload`

**Parameters:**
- `repoName` (query param): Name of the repository to create
- `zipFile` (multipart file): ZIP file containing project contents

**Example Request:**
```bash
curl -X POST "http://localhost:8088/project/provision/upload?repoName=demo-realm-john-myapp" \
  -F "zipFile=@/path/to/project.zip"
```

**Success Response:**
```json
{
  "status": "success",
  "repository": "demo-realm-john-myapp",
  "organization": "your-organization-name",
  "message": "Repository created and files uploaded successfully",
  "tempPath": "/tmp/upload-extract-123456789"
}
```

**Error Response:**
```json
{
  "error": "Configuration error: GITHUB_TOKEN missing"
}
```

### 2. Generate Repository Name

**Endpoint:** `GET /project/provision/generate-repo-name`

**Parameters:**
- `realmName` (required): Keycloak realm name
- `adminUsername` (optional): Admin username (defaults to "admin")
- `clientName` (required): Client/product name

**Example Request:**
```bash
curl "http://localhost:8088/project/provision/generate-repo-name?realmName=demo-realm&adminUsername=john&clientName=myapp"
```

**Response:**
```json
{
  "repositoryName": "demo-realm-john-myapp"
}
```

### 3. Health Check

**Endpoint:** `GET /project/provision/health`

**Example Request:**
```bash
curl http://localhost:8088/project/provision/health
```

**Response (Ready):**
```json
{
  "githubTokenConfigured": true,
  "githubOrgConfigured": true,
  "ready": true,
  "githubOrg": "your-organization-name"
}
```

**Response (Not Ready):**
```json
{
  "githubTokenConfigured": false,
  "githubOrgConfigured": true,
  "ready": false,
  "githubOrg": "your-organization-name"
}
```

## Usage Examples

### Java/Spring Integration

```java
@Autowired
private ProvisioningService provisioningService;

public void provisionNewClient(String realm, String admin, String client, MultipartFile zipFile) {
    try {
        // Generate standardized repository name
        String repoName = ProvisioningService.generateRepositoryName(realm, admin, client);
        
        // Provision repository
        Path tempPath = provisioningService.provision(repoName, zipFile);
        
        log.info("Repository created: {}/{}", 
                provisioningService.getGithubOrg(), repoName);
        
    } catch (Exception e) {
        log.error("Provisioning failed", e);
    }
}
```

### cURL with File Upload

```bash
# Create a test ZIP file
zip -r project.zip src/ pom.xml README.md

# Upload and provision
curl -X POST "http://localhost:8088/project/provision/upload?repoName=test-repo" \
  -F "zipFile=@project.zip" \
  -H "Content-Type: multipart/form-data"
```

### Postman/HTTP Client

1. Select **POST** method
2. URL: `http://localhost:8088/project/provision/upload?repoName=my-new-repo`
3. Body:
   - Type: `form-data`
   - Key: `zipFile` (type: File)
   - Value: Select your ZIP file

## How It Works

1. **Repository Creation**: Creates a private repository in your GitHub organization with `auto_init: true` to establish the main branch

2. **ZIP Extraction**: Securely extracts uploaded ZIP file to a temporary directory with Zip Slip protection

3. **File Upload**: 
   - Walks the extracted directory tree
   - Creates Git tree entries for all files
   - Commits all files in a single atomic commit
   - Updates the main branch reference

4. **Cleanup**: Returns temp directory path for optional cleanup

## Security Features

### Zip Slip Protection
The service validates all extracted file paths to prevent directory traversal attacks:

```java
Path resolvedPath = extractPath.resolve(entry.getName()).normalize();
if (!resolvedPath.startsWith(extractPath)) {
    throw new IOException("Zip Slip security violation");
}
```

### Configuration Validation
Service validates GitHub token and organization before any API calls:

```java
private void validateConfig() {
    if (githubToken == null || githubToken.isEmpty())
        throw new IllegalStateException("GITHUB_TOKEN missing");
    if (githubOrg == null || githubOrg.isEmpty())
        throw new IllegalStateException("GITHUB_ORG missing");
}
```

## Repository Naming Convention

Format: `{realmName}-{adminUsername}-{clientName}`

Examples:
- `demo-realm-john-myapp` → realm: demo-realm, admin: john, client: myapp
- `production-admin-api-service` → realm: production, admin: admin (default), client: api-service

All names are converted to lowercase automatically.

## GitHub API Permissions Required

Your GitHub token must have:
- `repo` - Full control of private repositories
- `admin:org` - Full control of organizations (for creating repos in org)

### Creating a Personal Access Token (PAT)

1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Select scopes: `repo`, `admin:org`
4. Copy the generated token
5. Set as `GITHUB_TOKEN` environment variable

## Error Handling

The service handles common scenarios:

| Error | HTTP Status | Cause | Solution |
|-------|-------------|-------|----------|
| Configuration error | 500 | Missing GITHUB_TOKEN or GITHUB_ORG | Set environment variables |
| Empty ZIP file | 400 | Uploaded file is empty | Upload valid ZIP file |
| GitHub API error | 500 | Invalid token, repo exists, rate limit | Check token permissions, repo name |
| Zip Slip violation | 500 | Malicious ZIP file | Use trusted ZIP sources |

## Testing

Run unit tests:
```bash
mvn test -Dtest=ProvisioningServiceTest
```

Test coverage includes:
- ✅ Configuration validation
- ✅ Repository name generation
- ✅ Token/organization getters
- ✅ Empty file handling
- ✅ Missing configuration detection

## Dependencies

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-compress</artifactId>
    <version>1.26.0</version>
</dependency>
```

## Integration with Identity Service

The Identity Service can call this endpoint when provisioning new clients:

```java
// In Identity Service
WebClient webClient = WebClient.builder()
    .baseUrl("http://product-management-service:8088")
    .build();

// Generate repo name
String repoName = String.format("%s-%s-%s", realm, admin, clientName).toLowerCase();

// Upload ZIP
MultipartBodyBuilder builder = new MultipartBodyBuilder();
builder.part("zipFile", new FileSystemResource(zipFile));

webClient.post()
    .uri("/project/provision/upload?repoName=" + repoName)
    .contentType(MediaType.MULTIPART_FORM_DATA)
    .body(BodyInserters.fromMultipartData(builder.build()))
    .retrieve()
    .bodyToMono(Map.class)
    .block();
```

## Logs

The service provides detailed logging:

```
2026-03-10 15:00:00 INFO  - Received provisioning request for repository: demo-realm-john-myapp
2026-03-10 15:00:01 INFO  - Successfully pushed all files to your-org/demo-realm-john-myapp in a single commit: abc123def
2026-03-10 15:00:01 INFO  - ✅ Successfully provisioned repository: your-org/demo-realm-john-myapp
```

## Troubleshooting

### "GITHUB_TOKEN missing"
- Ensure `GITHUB_TOKEN` environment variable is set
- Check application.properties for `github.token=${GITHUB_TOKEN}`

### "GitHub API error (404)"
- Verify organization name is correct
- Check token has `admin:org` permission

### "GitHub API error (422)"
- Repository name may already exist
- Repository name contains invalid characters

### "ZIP file is empty"
- Ensure uploaded file contains data
- Check file size limits in Spring Boot configuration

## Future Enhancements

Potential improvements:
- [ ] Support for GitHub Enterprise Server
- [ ] Webhook integration for build triggers
- [ ] Repository template support
- [ ] Branch protection rules
- [ ] Team permissions assignment
- [ ] Async provisioning with status polling

---

## Support

For issues or questions, contact the Product Management Service team.

