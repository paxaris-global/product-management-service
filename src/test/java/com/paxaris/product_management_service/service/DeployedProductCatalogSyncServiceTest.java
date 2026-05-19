package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployedProductCatalogSyncServiceTest {

    private DeployedProductCatalogSyncService catalogSync;

    @BeforeEach
    void setUp() {
        catalogSync = new DeployedProductCatalogSyncService(null, new ObjectMapper());
    }

    @Test
    void parseFrontendServiceName_yatrify() {
        Optional<DeployedProductCatalogSyncService.RealmProductRef> ref =
                catalogSync.parseFrontendServiceName("yatrify-admin-testyatrify-frontend");
        assertTrue(ref.isPresent());
        assertEquals("yatrify", ref.get().realmName());
        assertEquals("testyatrify", ref.get().productId());
    }

    @Test
    void parseFrontendServiceName_finaltest36() {
        Optional<DeployedProductCatalogSyncService.RealmProductRef> ref =
                catalogSync.parseFrontendServiceName("finaltest36-admin-backend-test-frontend");
        assertTrue(ref.isPresent());
        assertEquals("finaltest36", ref.get().realmName());
        assertEquals("backend-test", ref.get().productId());
    }

    @Test
    void parseFrontendServiceName_ignoresNonFrontend() {
        assertTrue(catalogSync.parseFrontendServiceName("api-gateway").isEmpty());
    }
}
