package com.paxaris.product_management_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    private ProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        // Initialize with test configuration
        provisioningService = new ProvisioningService("test-token", "test-org", "https://api.github.com", "admin", "paxaris-global", "paxo");
    }

    @Test
    void testGetGithubToken() {
        assertEquals("test-token", provisioningService.getGithubToken());
    }

    @Test
    void testGetGithubOrg() {
        assertEquals("test-org", provisioningService.getGithubOrg());
    }

    @Test
    void testGenerateRepositoryName() {
        String repoName = provisioningService.generateRepositoryName(
                "demo-realm", "john", "my-app");

        assertEquals("demo-realm-john-my-app", repoName);
    }

    @Test
    void testGenerateRepositoryNameWithNullAdmin() {
        String repoName = provisioningService.generateRepositoryName(
                "demo-realm", null, "my-app");

        assertEquals("demo-realm-admin-my-app", repoName);
    }

    @Test
    void testGenerateRepositoryNameConvertsToLowercase() {
        String repoName = provisioningService.generateRepositoryName(
                "Demo-Realm", "John", "My-App");

        assertEquals("demo-realm-john-my-app", repoName);
    }

    @Test
    void testUnzipWithEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "test.zip",
                "application/zip",
                new byte[0]
        );

        // Should throw an exception for invalid ZIP
        assertThrows(Exception.class, () -> {
            provisioningService.provision("test-repo", emptyFile);
        });
    }

    @Test
    void testValidateConfigWithMissingToken() {
        ProvisioningService invalidService = new ProvisioningService("", "test-org", "https://api.github.com", "admin", "paxaris-global", "paxo");

        MockMultipartFile dummyFile = new MockMultipartFile(
                "file",
                "test.zip",
                "application/zip",
                "dummy".getBytes()
        );

        assertThrows(IllegalStateException.class, () -> {
            invalidService.provision("test-repo", dummyFile);
        });
    }

    @Test
    void testValidateConfigWithMissingOrg() {
        ProvisioningService invalidService = new ProvisioningService("test-token", "", "https://api.github.com", "admin", "paxaris-global", "paxo");

        MockMultipartFile dummyFile = new MockMultipartFile(
                "file",
                "test.zip",
                "application/zip",
                "dummy".getBytes()
        );

        assertThrows(IllegalStateException.class, () -> {
            invalidService.provision("test-repo", dummyFile);
        });
    }
}

