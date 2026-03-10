# Code Reference - File Locations & Line Numbers

## 📍 New Files Created

### 1. MultipartConfig.java
**Location**: `src/main/java/com/paxaris/product_management_service/config/MultipartConfig.java`
**Purpose**: Configure multipart file upload with 100MB limit
**Key Classes**:
- MultipartConfigElement bean configuration
- File size settings

### 2. ProvisioningServiceHelper.java
**Location**: `src/main/java/com/paxaris/product_management_service/service/ProvisioningServiceHelper.java`
**Purpose**: Helper for proper multipart file uploads from other services
**Key Methods**:
- `provisionRepository(String repoName, MultipartFile zipFile)` - Line 54
- `provisionRepositoryFromPath(String repoName, Path zipFilePath)` - Line 83
- Inner class `MultipartFileResource` - Line 121

---

## 📍 Modified Files

### 1. pom.xml
**Location**: `pom.xml`
**Changes**:
- Added commons-fileupload dependency (Line 77-81)

### 2. application.properties
**Location**: `src/main/resources/application.properties`
**Changes**:
- Added multipart configuration (Lines 15-17)
  - spring.servlet.multipart.max-file-size=100MB
  - spring.servlet.multipart.max-request-size=100MB
  - spring.servlet.multipart.enabled=true

### 3. ProvisioningController.java
**Location**: `src/main/java/com/paxaris/product_management_service/controller/ProvisioningController.java`
**Changes**:
- Enhanced /upload endpoint with better null checking
- Added detailed logging
- Improved error handling
- Lines 34-65 (updated method)

### 4. RealmProductRoleUrlControllerTest.java
**Location**: `src/test/java/com/paxaris/product_management_service/controller/RealmProductRoleUrlControllerTest.java`
**Changes**:
- Added 4 new test methods
  - `saveOrUpdateWithUriAndHttpMethod()` - Line 46
  - `saveOrUpdateWithMultipleHttpMethods()` - Line 60
  - `getByIdReturnsRole()` - Line 129

---

## 🔄 Data Models

### RoleRequest DTO
**Location**: `src/main/java/com/paxaris/product_management_service/dto/RoleRequest.java`
**Attributes**:
- realmName: String
- productName: String
- roleName: String
- uri: String (✅ Used for storage)
- httpMethod: String (✅ Used for storage)

### RealmProductRole Entity
**Location**: `src/main/java/com/paxaris/product_management_service/entities/RealmProductRole.java`
**Fields**:
- id: Long (PK)
- realmName: String
- productName: String
- roleName: String
- (Has OneToMany relationship with RealmProductRoleUrl)

### RealmProductRoleUrl Entity
**Location**: `src/main/java/com/paxaris/product_management_service/entities/RealmProductRoleUrl.java`
**Fields**:
- id: Long (PK)
- uri: String (VARCHAR 191)
- httpMethod: HttpMethodType (ENUM)
- role: RealmProductRole (FK)

### HttpMethodType Enum
**Location**: `src/main/java/com/paxaris/product_management_service/entities/HttpMethodType.java`
**Values**: GET, POST, PUT, PATCH, DELETE

---

## 🔗 Service Implementations

### RealmProductRoleUrlService
**Location**: `src/main/java/com/paxaris/product_management_service/service/RealmProductRoleUrlService.java`
**Methods**:
- `void saveOrUpdateRole(RoleRequest request)` - Saves role and URI+method to DB
- `List<RealmProductRole> getAll()` - Retrieves all roles
- `RealmProductRole getById(Long id)` - Gets specific role
- `void deleteById(Long id)` - Deletes role

### RealmProductRoleUrlServiceImpl
**Location**: `src/main/java/com/paxaris/product_management_service/service/impl/RealmProductRoleUrlServiceImpl.java`
**Implementation Details**:
- `saveOrUpdateRole()` - Line 31
  - Step 1: Save role to realm_product_role table - Line 42
  - Step 2: Save URI+method to realm_product_role_url table - Line 68
- `normalizeUri()` - Line 96
- `parseHttpMethod()` - Line 107

---

## 🔌 Controllers

### RealmProductRoleUrlController
**Location**: `src/main/java/com/paxaris/product_management_service/controller/RealmProductRoleUrlController.java`
**Endpoints**:
- POST `/project/roles/save-or-update` - Line 27
- GET `/project/roles` - Line 31
- GET `/project/roles/{id}` - Line 35
- DELETE `/project/roles/{id}` - Line 39

### ProvisioningController
**Location**: `src/main/java/com/paxaris/product_management_service/controller/ProvisioningController.java`
**Endpoints**:
- POST `/project/provision/upload` - Line 34
- GET `/project/provision/generate-repo-name` - Line 72
- GET `/project/provision/health` - Line 92

---

## 📚 Repositories

### RealmProductRoleRepository
**Location**: `src/main/java/com/paxaris/product_management_service/repository/RealmProductRoleRepository.java`
**Methods**:
- `findByRealmNameAndProductNameAndRoleName(...)`
- Standard JPA methods (save, delete, findAll, etc.)

### RealmProductRoleUrlRepository
**Location**: `src/main/java/com/paxaris/product_management_service/repository/RealmProductRoleUrlRepository.java`
**Methods**:
- `findByRoleAndUriAndHttpMethod(RealmProductRole, String, HttpMethodType)`
- Standard JPA methods

---

## 🧪 Test Files

### RealmProductRoleUrlControllerTest
**Location**: `src/test/java/com/paxaris/product_management_service/controller/RealmProductRoleUrlControllerTest.java`
**Test Methods** (6 total):
1. `saveOrUpdateReturnsOk()` - Line 39
2. `saveOrUpdateWithUriAndHttpMethod()` - Line 46
3. `saveOrUpdateWithMultipleHttpMethods()` - Line 60
4. `getAllReturnsServiceData()` - Line 90
5. `deleteByIdReturnsNoContent()` - Line 113
6. `getByIdReturnsRole()` - Line 129

### ProvisioningServiceTest
**Location**: `src/test/java/com/paxaris/product_management_service/service/ProvisioningServiceTest.java`
**Test Methods** (8 total):
- Configuration validation tests
- Repository name generation tests
- Error handling tests

### ProjectManagerServiceApplicationTests
**Location**: `src/test/java/com/paxaris/product_management_service/ProjectManagerServiceApplicationTests.java`
**Test**: Application startup verification

---

## 📖 Documentation Files

### README_IMPLEMENTATION.md
**Purpose**: Complete implementation overview
**Sections**: Overview, accomplishments, structure, workflow, API endpoints, etc.

### CHANGES_SUMMARY.md
**Purpose**: Summary of all changes made
**Sections**: What was implemented, files changed, data flow, test results, etc.

### IMPLEMENTATION_COMPLETE.md
**Purpose**: Detailed implementation details
**Sections**: Database schema, code changes, testing, features, etc.

### IDENTITY_SERVICE_QUICK_GUIDE.md
**Purpose**: Quick start guide for Identity Service
**Sections**: Role request format, file upload fix, examples

### PROVISIONING_SERVICE_INTEGRATION_GUIDE.md
**Purpose**: Complete integration guide
**Sections**: Solutions, endpoint details, workflow, troubleshooting, best practices

---

## 🔍 Key Code Snippets

### Saving Role with URI & HTTP Method
**File**: RealmProductRoleUrlServiceImpl.java (Line 31)
```java
@Override
public void saveOrUpdateRole(RoleRequest request) {
    // Step 1: Save role
    RealmProductRole role = roleRepository.save(...);
    
    // Step 2: Save URI + HTTP method
    if (normalizedUri != null && normalizedMethod != null) {
        RealmProductRoleUrl roleUrl = new RealmProductRoleUrl(
            role, normalizedUri, normalizedMethod
        );
        urlRepository.save(roleUrl);
    }
}
```

### Multipart File Upload Helper
**File**: ProvisioningServiceHelper.java (Line 54)
```java
public Map<String, String> provisionRepository(String repoName, MultipartFile zipFile) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("repoName", repoName);
    body.add("zipFile", new MultipartFileResource(zipFile));
    
    HttpEntity<MultiValueMap<String, Object>> requestEntity = 
        new HttpEntity<>(body, headers);
    
    return restTemplate.postForObject(url, requestEntity, Map.class);
}
```

### Enhanced Upload Endpoint
**File**: ProvisioningController.java (Line 34)
```java
@PostMapping(value = "/upload", consumes = "multipart/form-data")
public ResponseEntity<Map<String, String>> provisionRepository(
        @RequestParam("repoName") String repoName,
        @RequestParam("zipFile") MultipartFile zipFile) {
    
    if (zipFile == null || zipFile.isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "ZIP file is empty or missing"));
    }
    // ... rest of method
}
```

---

## 🚀 Build & Run

### Compile
```bash
mvn clean compile
```

### Test
```bash
mvn test
mvn test -Dtest=RealmProductRoleUrlControllerTest
```

### Package
```bash
mvn clean package
```

### Run JAR
```bash
java -jar target/product_management_service-0.0.1-SNAPSHOT.jar
```

---

## ✅ Verification Commands

### Check Database Tables
```sql
SELECT * FROM realm_product_role;
SELECT * FROM realm_product_role_url;
```

### Test API Endpoints
```bash
# Save role with URI
curl -X POST http://localhost:8088/project/roles/save-or-update \
  -H "Content-Type: application/json" \
  -d '{
    "realmName":"demo",
    "productName":"app",
    "roleName":"admin",
    "uri":"/api/products",
    "httpMethod":"GET"
  }'

# Get all roles
curl http://localhost:8088/project/roles

# Health check
curl http://localhost:8088/project/provision/health
```

---

**Last Updated**: March 10, 2026  
**Total Files Created**: 5  
**Total Files Modified**: 4  
**Documentation Files**: 5  
**Tests Added**: 4  
**Total Tests Passing**: 15/15 ✅

