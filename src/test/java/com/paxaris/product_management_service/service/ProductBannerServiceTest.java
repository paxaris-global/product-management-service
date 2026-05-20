package com.paxaris.product_management_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductBannerServiceTest {

    private final ProductBannerService bannerService = new ProductBannerService(null, null, null);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bannerService, "maxBannerBytes", 5_242_880L);
    }

    @Test
    void rejectsOversizedBanner() {
        byte[] bytes = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "bannerImage",
                "big.png",
                "image/png",
                bytes
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> bannerService.validateBannerFile(file)
        );
        assertTrue(ex.getMessage().contains("MB"));
    }

    @Test
    void rejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile(
                "bannerImage",
                "doc.pdf",
                "application/pdf",
                new byte[] {1, 2, 3}
        );

        assertThrows(IllegalArgumentException.class, () -> bannerService.validateBannerFile(file));
    }
}
