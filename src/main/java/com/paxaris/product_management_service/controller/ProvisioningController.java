package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.service.ProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/project/provision")
@RequiredArgsConstructor
@Tag(name = "Provisioning", description = "Repository provisioning APIs")
public class ProvisioningController {

    private final ProvisioningService provisioningService;

    /**
     * Provision a new GitHub repository with uploaded zip file contents.
     *
     * @param repoName Name of the repository to create
     * @param zipFile  ZIP file containing project contents
     * @return Response with repository details
     */
    /**
     * Enhanced endpoint for provisioning with better multipart handling.
     * Recommended for use with RestTemplate from other services.
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
        @Operation(summary = "Provision repository", description = "Creates a GitHub repository and uploads ZIP contents in one operation")
        @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Repository provisioned successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "500", description = "Provisioning failed")
        })
    public ResponseEntity<Map<String, String>> provisionRepository(
            @RequestParam("repoName") String repoName,
            @RequestParam("zipFile") MultipartFile zipFile) {

        log.info("Received provisioning request for repository: {}", repoName);

        try {
            if (zipFile == null || zipFile.isEmpty()) {
                log.warn("ZIP file is empty or null for repo: {}", repoName);
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "ZIP file is empty or missing"));
            }

            log.info("Processing ZIP file: {}, size: {} bytes", zipFile.getOriginalFilename(), zipFile.getSize());

            Path tempPath = provisioningService.provision(repoName, zipFile);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("repository", repoName);
            response.put("organization", provisioningService.getGithubOrg());
            response.put("message", "Repository created and files uploaded successfully");
            response.put("tempPath", tempPath.toString());

            log.info("✅ Successfully provisioned repository: {}/{}",
                    provisioningService.getGithubOrg(), repoName);

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            log.error("❌ Configuration error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Configuration error: " + e.getMessage()));

        } catch (Exception e) {
            log.error("❌ Failed to provision repository: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to provision repository: " + e.getMessage()));
        }
    }

    /**
     * Generate a standardized repository name.
     *
     * @param realmName    Realm name
     * @param adminUsername Admin username
     * @param clientName   Client/product name
     * @return Generated repository name
     */
    @GetMapping("/generate-repo-name")
    @Operation(summary = "Generate repository name", description = "Builds a normalized repository name from realm, admin user, and client")
    @ApiResponse(responseCode = "200", description = "Repository name generated")
    public ResponseEntity<Map<String, String>> generateRepoName(
            @RequestParam String realmName,
            @RequestParam(required = false) String adminUsername,
            @RequestParam String clientName) {

        String repoName = provisioningService.generateRepositoryName(
                realmName, adminUsername, clientName);

        return ResponseEntity.ok(Map.of("repositoryName", repoName));
    }

    /**
     * Health check endpoint for provisioning service.
     *
     * @return Configuration status
     */
    @GetMapping("/health")
    @Operation(summary = "Provisioning health", description = "Returns provisioning configuration readiness")
    @ApiResponse(responseCode = "200", description = "Provisioning service healthy")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();

        boolean tokenConfigured = provisioningService.getGithubToken() != null
                && !provisioningService.getGithubToken().isEmpty();
        boolean orgConfigured = provisioningService.getGithubOrg() != null
                && !provisioningService.getGithubOrg().isEmpty();

        status.put("githubTokenConfigured", tokenConfigured);
        status.put("githubOrgConfigured", orgConfigured);
        status.put("ready", tokenConfigured && orgConfigured);

        if (orgConfigured) {
            status.put("githubOrg", provisioningService.getGithubOrg());
        }

        HttpStatus httpStatus = (tokenConfigured && orgConfigured)
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(httpStatus).body(status);
    }
}

