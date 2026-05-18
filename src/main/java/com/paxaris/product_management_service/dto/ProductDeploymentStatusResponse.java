package com.paxaris.product_management_service.dto;

public record ProductDeploymentStatusResponse(
        String status,
        String realmName,
        String productId,
        String frontendBaseUrl,
        String backendBaseUrl,
        boolean frontendReady,
        boolean backendReady,
        boolean ready,
        int progressPercent,
        String phase,
        String message
) {
}
