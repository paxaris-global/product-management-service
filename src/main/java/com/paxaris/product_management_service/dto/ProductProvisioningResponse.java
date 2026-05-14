package com.paxaris.product_management_service.dto;

public record ProductProvisioningResponse(
    String status,
    String realmName,
    String productId,
    String backendRepository,
    String frontendRepository,
    Integer frontendNodePort,
    Integer backendNodePort,
    String frontendBaseUrl,
    String backendBaseUrl
) {
}
