package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.dto.CaptureShowcaseRequest;
import com.paxaris.product_management_service.dto.ProductShowcaseResponse;
import com.paxaris.product_management_service.entities.ProductShowcase;
import com.paxaris.product_management_service.entities.ProductUrlMapping;
import com.paxaris.product_management_service.repository.ProductShowcaseRepository;
import com.paxaris.product_management_service.repository.ProductUrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductShowcaseService {

    private static final int READY_WAIT_ATTEMPTS = 30;
    private static final long READY_WAIT_MS = 4000L;

    private final ProductShowcaseRepository showcaseRepository;
    private final ProductUrlMappingRepository urlMappingRepository;
    private final ProvisioningService provisioningService;
    private final ProductShowcaseCaptureService captureService;

    @Transactional(readOnly = true)
    public List<ProductShowcaseResponse> listShowcases(String realmName) {
        List<ProductShowcase> rows = realmName == null || realmName.isBlank()
                ? showcaseRepository.findAllByOrderByCapturedAtDesc()
                : showcaseRepository.findByRealmNameOrderByCapturedAtDesc(realmName.trim());
        return rows.stream().map(row -> toResponse(row, row.getProductName())).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ProductShowcaseResponse> getShowcase(String realmName, String productId) {
        return showcaseRepository.findByRealmNameAndProductId(realmName, productId)
                .map(row -> toResponse(row, row.getProductName()));
    }

    @Transactional
    public ProductShowcaseResponse captureShowcase(
            String realmName,
            String productId,
            CaptureShowcaseRequest request
    ) {
        ProductUrlMapping mapping = urlMappingRepository.findByRealmNameAndProductId(realmName, productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product URLs are not allocated for realm='" + realmName + "', product='" + productId + "'"
                ));

        waitUntilReady(realmName, productId);

        String productName = resolveProductName(productId, request);
        String frontendUrl = mapping.getFrontendBaseUrl();

        log.info("Capturing product showcase for realm='{}', product='{}', url={}", realmName, productId, frontendUrl);
        ProductShowcaseCaptureService.CaptureResult captured =
                captureService.capture(frontendUrl, productName, realmName);

        String displayName = captured.productName();
        if (displayName == null || displayName.isBlank()) {
            displayName = productName;
        }

        ProductShowcase showcase = showcaseRepository.findByRealmNameAndProductId(realmName, productId)
                .orElseGet(ProductShowcase::new);

        showcase.setRealmName(realmName);
        showcase.setProductId(productId);
        showcase.setProductName(displayName);
        showcase.setFrontendUrl(frontendUrl);
        showcase.setDescription(captured.description());
        showcase.setPreviewImage(captured.previewImage());
        showcase.setCapturedAt(Instant.now());

        ProductShowcase saved = showcaseRepository.save(showcase);
        return toResponse(saved, displayName);
    }

    private void waitUntilReady(String realmName, String productId) {
        for (int attempt = 1; attempt <= READY_WAIT_ATTEMPTS; attempt++) {
            var status = provisioningService.getProductDeploymentStatus(realmName, productId);
            if (status.ready()) {
                return;
            }
            if (attempt == READY_WAIT_ATTEMPTS) {
                throw new IllegalStateException(
                        "Product is not healthy yet; showcase capture requires frontend and backend to be ready"
                );
            }
            try {
                Thread.sleep(READY_WAIT_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for product readiness", ex);
            }
        }
    }

    private String resolveProductName(String productId, CaptureShowcaseRequest request) {
        if (request != null && request.productName() != null && !request.productName().isBlank()) {
            return request.productName().trim();
        }
        return humanizeProductId(productId);
    }

    private static String humanizeProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            return "Product";
        }
        String normalized = productId.trim().replace('-', ' ').replace('_', ' ');
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private ProductShowcaseResponse toResponse(ProductShowcase showcase, String productName) {
        return new ProductShowcaseResponse(
                showcase.getId(),
                showcase.getRealmName(),
                showcase.getProductId(),
                productName,
                showcase.getFrontendUrl(),
                showcase.getDescription(),
                showcase.getPreviewImage(),
                showcase.getCapturedAt()
        );
    }
}
