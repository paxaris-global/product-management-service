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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final DeployedProductCatalogSyncService catalogSync;

    /**
     * Lists every provisioned product (URL mapping), merged with captured showcase data when present.
     * Discovers running {@code *-frontend} services on Kubernetes and triggers background capture.
     */
    @Transactional
    public List<ProductShowcaseResponse> listShowcases(String realmName) {
        catalogSync.syncDeployedProductsFromKubernetes();

        List<ProductUrlMapping> mappings = urlMappingRepository.findAllByOrderByRealmNameAscProductIdAsc();
        String realmFilter = realmName == null || realmName.isBlank() ? null : realmName.trim();

        Map<String, ProductShowcase> showcaseByKey = new HashMap<>();
        List<ProductShowcase> showcases = realmFilter == null
                ? showcaseRepository.findAllByOrderByCapturedAtDesc()
                : showcaseRepository.findByRealmNameOrderByCapturedAtDesc(realmFilter);
        for (ProductShowcase row : showcases) {
            showcaseByKey.put(catalogKey(row.getRealmName(), row.getProductId()), row);
        }

        List<ProductShowcaseResponse> catalog = new ArrayList<>();
        for (ProductUrlMapping mapping : mappings) {
            if (realmFilter != null && !realmFilter.equals(mapping.getRealmName())) {
                continue;
            }
            if (!catalogSync.isCatalogProduct(mapping.getRealmName(), mapping.getProductId())) {
                continue;
            }
            String key = catalogKey(mapping.getRealmName(), mapping.getProductId());
            ProductShowcase captured = showcaseByKey.get(key);
            catalog.add(toCatalogEntry(mapping, captured));
            showcaseByKey.remove(key);

            if (captured == null || isPlaceholderOnly(captured)) {
                try {
                    provisioningService.getProductDeploymentStatus(mapping.getRealmName(), mapping.getProductId());
                } catch (Exception ex) {
                    log.debug("Catalog status check for {} / {}: {}", mapping.getRealmName(), mapping.getProductId(),
                            ex.getMessage());
                }
            }
        }

        // Legacy rows: showcase exists without a URL mapping (should be rare).
        for (ProductShowcase orphan : showcaseByKey.values()) {
            if (realmFilter != null && !realmFilter.equals(orphan.getRealmName())) {
                continue;
            }
            if (!catalogSync.isCatalogProduct(orphan.getRealmName(), orphan.getProductId())) {
                continue;
            }
            catalog.add(toResponse(orphan, orphan.getProductName()));
        }

        catalog.sort(Comparator
                .comparing((ProductShowcaseResponse item) -> item.capturedAt() == null)
                .thenComparing(ProductShowcaseResponse::capturedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProductShowcaseResponse::productName, String.CASE_INSENSITIVE_ORDER));

        return catalog;
    }

    @Transactional
    public int syncCatalogFromCluster() {
        int registered = catalogSync.syncDeployedProductsFromKubernetes();
        urlMappingRepository.findAllByOrderByRealmNameAscProductIdAsc().stream()
                .filter(m -> catalogSync.isCatalogProduct(m.getRealmName(), m.getProductId()))
                .forEach(mapping ->
                        provisioningService.getProductDeploymentStatus(
                                mapping.getRealmName(), mapping.getProductId())
                );
        return registered;
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

    private ProductShowcaseResponse toCatalogEntry(ProductUrlMapping mapping, ProductShowcase captured) {
        if (captured != null && !isPlaceholderOnly(captured)) {
            return toResponse(captured, captured.getProductName());
        }

        String productName = captured != null && captured.getProductName() != null && !captured.getProductName().isBlank()
                ? captured.getProductName()
                : humanizeProductId(mapping.getProductId());

        String description = captured != null && captured.getDescription() != null && !captured.getDescription().isBlank()
                ? captured.getDescription()
                : defaultCatalogDescription(productName, mapping.getRealmName());

        String preview = captured != null && captured.getPreviewImage() != null && !captured.getPreviewImage().isBlank()
                ? captured.getPreviewImage()
                : captureService.buildPlaceholderSvg(productName, mapping.getRealmName());

        Instant capturedAt = captured != null ? captured.getCapturedAt() : null;
        Long id = captured != null ? captured.getId() : null;

        return new ProductShowcaseResponse(
                id,
                mapping.getRealmName(),
                mapping.getProductId(),
                productName,
                mapping.getFrontendBaseUrl(),
                description,
                preview,
                capturedAt
        );
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

    private static String defaultCatalogDescription(String productName, String realmName) {
        return productName + " — live on Kubernetes (realm " + realmName + "). Screenshot updating…";
    }

    private static String catalogKey(String realmName, String productId) {
        return realmName + ":" + productId;
    }

    private static boolean isPlaceholderOnly(ProductShowcase showcase) {
        String preview = showcase.getPreviewImage();
        return preview == null || preview.isBlank() || preview.startsWith("data:image/svg+xml");
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
