package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployedProductCatalogSyncServiceTest {

    private DeployedProductCatalogSyncService catalogSync;

    @BeforeEach
    void setUp() {
        catalogSync = new DeployedProductCatalogSyncService(null, new ObjectMapper());
    }

    @Test
    void parseFrontendServiceName_yatrify() {
        Optional<ProductFrontendCatalogRules.RealmProductRef> ref =
                catalogSync.parseFrontendServiceName("yatrify-admin-testyatrify-frontend");
        assertTrue(ref.isPresent());
        assertEquals("yatrify", ref.get().realmName());
        assertEquals("testyatrify", ref.get().productId());
    }

    @Test
    void parseFrontendServiceName_finaltest36() {
        Optional<ProductFrontendCatalogRules.RealmProductRef> ref =
                catalogSync.parseFrontendServiceName("finaltest36-admin-backend-test-frontend");
        assertTrue(ref.isPresent());
        assertEquals("finaltest36", ref.get().realmName());
        assertEquals("backend-test", ref.get().productId());
    }

    @Test
    void excludesPlatformAndInfraFrontends() {
        assertFalse(catalogSync.parseFrontendServiceName("paxo-frontend").isPresent());
        assertFalse(catalogSync.parseFrontendServiceName("python-frontend").isPresent());
        assertFalse(catalogSync.parseFrontendServiceName("api-gateway").isPresent());
        assertFalse(catalogSync.parseFrontendServiceName("yatrify-admin-testyatrify-backend").isPresent());
    }

    @Test
    void isCatalogProduct_matchesProvisionedFrontendNaming() {
        assertTrue(catalogSync.isCatalogProduct("yatrify", "testyatrify"));
        assertFalse(catalogSync.isCatalogProduct("yatrify", "redis"));
        assertFalse(catalogSync.isCatalogProduct("", "test"));
    }
}
