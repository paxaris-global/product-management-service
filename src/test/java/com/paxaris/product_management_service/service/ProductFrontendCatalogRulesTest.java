package com.paxaris.product_management_service.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductFrontendCatalogRulesTest {

    @Test
    void acceptsProvisionedProductFrontend() {
        assertTrue(ProductFrontendCatalogRules.isProvisionedProductFrontendName(
                "yatrify-admin-testyatrify-frontend"));
    }

    @Test
    void rejectsPlatformFrontends() {
        assertFalse(ProductFrontendCatalogRules.isProvisionedProductFrontendName("paxo-frontend"));
        assertFalse(ProductFrontendCatalogRules.isProvisionedProductFrontendName("python-frontend"));
    }
}
