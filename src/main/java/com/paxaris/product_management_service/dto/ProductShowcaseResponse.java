package com.paxaris.product_management_service.dto;

import java.time.Instant;

public record ProductShowcaseResponse(
        Long id,
        String realmName,
        String productId,
        String productName,
        String frontendUrl,
        String description,
        String previewImage,
        Instant capturedAt
) {
}
