package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.dto.CaptureShowcaseRequest;
import com.paxaris.product_management_service.dto.ProductShowcaseResponse;
import com.paxaris.product_management_service.entities.ProductShowcase;
import com.paxaris.product_management_service.entities.ProductUrlMapping;
import com.paxaris.product_management_service.repository.ProductShowcaseRepository;
import com.paxaris.product_management_service.repository.ProductUrlMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class ProductShowcaseService {

    private static final int READY_WAIT_ATTEMPTS = 30;
    private static final long READY_WAIT_MS = 4000L;

    private final ProductShowcaseRepository showcaseRepository;
    private final ProductUrlMappingRepository urlMappingRepository;
    private final ProvisioningService provisioningService;
    private final ProductShowcaseCaptureService captureService;
    private final DeployedProductCatalogSyncService catalogSync;
    private final ProductShowcaseCaptureOrchestrator showcaseCaptureOrchestrator;
    private final ProductPublicUrlService publicUrlService;
    private final ProductBannerService bannerService;

    public ProductShowcaseService(
            ProductShowcaseRepository showcaseRepository,
            ProductUrlMappingRepository urlMappingRepository,
            ProvisioningService provisioningService,
            ProductShowcaseCaptureService captureService,
            DeployedProductCatalogSyncService catalogSync,
            @Lazy ProductShowcaseCaptureOrchestrator showcaseCaptureOrchestrator,
            ProductPublicUrlService publicUrlService,
            ProductBannerService bannerService
    ) {
        this.showcaseRepository = showcaseRepository;
        this.urlMappingRepository = urlMappingRepository;
        this.provisioningService = provisioningService;
        this.captureService = captureService;
        this.catalogSync = catalogSync;
        this.showcaseCaptureOrchestrator = showcaseCaptureOrchestrator;
        this.publicUrlService = publicUrlService;
        this.bannerService = bannerService;
    }

    /**
     * Lists every provisioned product (URL mapping), merged with captured showcase data when present.
     * Discovers running {@code *-frontend} services on Kubernetes and triggers background capture.
     */
    @Transactional
    public List<ProductShowcaseResponse> listShowcases(String realmName) {
        catalogSync.syncDeployedProductsFromKubernetes();
        Set<String> liveFrontends = catalogSync.liveProductFrontendDeploymentNames();

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
        Set<String> listedKeys = new HashSet<>();
        for (ProductUrlMapping mapping : mappings) {
            if (realmFilter != null && !realmFilter.equalsIgnoreCase(mapping.getRealmName())) {
                continue;
            }
            if (!catalogSync.isLiveCatalogProduct(mapping.getRealmName(), mapping.getProductId(), liveFrontends)) {
                continue;
            }
            String key = catalogKey(mapping.getRealmName(), mapping.getProductId());
            if (!listedKeys.add(key)) {
                continue;
            }
            ProductShowcase captured = showcaseByKey.remove(key);
            catalog.add(toCatalogEntry(mapping, captured));

            if (captured == null || shouldAutoCapture(captured)) {
                showcaseCaptureOrchestrator.captureWhenReadyIfAbsent(
                        mapping.getRealmName(), mapping.getProductId());
            }
        }

        // Legacy rows: showcase exists without a URL mapping (should be rare).
        for (ProductShowcase orphan : showcaseByKey.values()) {
            if (realmFilter != null && !realmFilter.equalsIgnoreCase(orphan.getRealmName())) {
                continue;
            }
            if (!catalogSync.isLiveCatalogProduct(orphan.getRealmName(), orphan.getProductId(), liveFrontends)) {
                continue;
            }
            String key = catalogKey(orphan.getRealmName(), orphan.getProductId());
            if (!listedKeys.add(key)) {
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
                .filter(m -> catalogSync.isLiveCatalogProduct(m.getRealmName(), m.getProductId()))
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
    public ProductShowcaseResponse uploadBanner(
            String realmName,
            String productId,
            MultipartFile bannerImage,
            String productName
    ) throws java.io.IOException {
        String dataUrl = bannerService.toDataUrl(bannerImage);
        ProductShowcase saved = bannerService.upsertBannerShowcase(realmName, productId, dataUrl, productName);
        log.info("Saved custom banner for realm='{}', product='{}'", realmName, productId);
        return toResponse(saved, saved.getProductName());
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
        String captureUrl = publicUrlService.toInClusterCaptureUrl(realmName, productId);
        String browserUrl = publicUrlService.toBrowserUrl(mapping);

        log.info("Capturing product showcase for realm='{}', product='{}', captureUrl={}", realmName, productId, captureUrl);
        ProductShowcaseCaptureService.CaptureResult captured;
        try {
            captured = captureService.capture(captureUrl, productName, realmName);
        } catch (Exception ex) {
            log.warn("In-cluster capture failed for {} / {}, retrying via NodePort: {}",
                    realmName, productId, ex.getMessage());
            captured = captureService.capture(mapping.getFrontendBaseUrl(), productName, realmName);
        }

        String displayName = captured.productName();
        if (displayName == null || displayName.isBlank()) {
            displayName = productName;
        }

        ProductShowcase showcase = showcaseRepository.findByRealmNameAndProductId(realmName, productId)
                .orElseGet(ProductShowcase::new);

        boolean preserveBanner = ProductBannerService.hasCustomBanner(showcase);
        String existingPreview = showcase.getPreviewImage();

        showcase.setRealmName(realmName);
        showcase.setProductId(productId);
        showcase.setProductName(displayName);
        showcase.setFrontendUrl(browserUrl != null ? browserUrl : mapping.getFrontendBaseUrl());
        showcase.setDescription(captured.description());
        if (preserveBanner && existingPreview != null && !existingPreview.isBlank()) {
            showcase.setPreviewImage(existingPreview);
            showcase.setCustomBanner(true);
        } else {
            showcase.setPreviewImage(captured.previewImage());
            showcase.setCustomBanner(false);
        }
        showcase.setCapturedAt(Instant.now());

        ProductShowcase saved = showcaseRepository.save(showcase);
        return toResponse(saved, displayName);
    }

    private ProductShowcaseResponse toCatalogEntry(ProductUrlMapping mapping, ProductShowcase captured) {
        if (captured != null && !shouldAutoCapture(captured)) {
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

        String browserUrl = publicUrlService.toBrowserUrl(mapping);
        if (browserUrl == null || browserUrl.isBlank()) {
            browserUrl = publicUrlService.rewriteForBrowser(mapping.getFrontendBaseUrl());
        }

        String canonicalRealm = canonicalRealm(mapping.getRealmName());
        String canonicalProductId = canonicalProductId(mapping.getProductId());

        return new ProductShowcaseResponse(
                id,
                canonicalRealm,
                canonicalProductId,
                productName,
                browserUrl,
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

    private static boolean shouldAutoCapture(ProductShowcase showcase) {
        if (ProductBannerService.hasCustomBanner(showcase)) {
            return false;
        }
        return isPlaceholderOnly(showcase);
    }

    private static boolean isPlaceholderOnly(ProductShowcase showcase) {
        String preview = showcase.getPreviewImage();
        return preview == null || preview.isBlank() || preview.startsWith("data:image/svg+xml");
    }

    private ProductShowcaseResponse toResponse(ProductShowcase showcase, String productName) {
        String canonicalRealm = canonicalRealm(showcase.getRealmName());
        String canonicalProductId = canonicalProductId(showcase.getProductId());

        String browserUrl = urlMappingRepository
                .findByRealmNameIgnoreCaseAndProductIdIgnoreCase(canonicalRealm, canonicalProductId)
                .map(publicUrlService::toBrowserUrl)
                .filter(url -> url != null && !url.isBlank())
                .orElseGet(() -> publicUrlService.useProxyBrowserPaths()
                        ? publicUrlService.toProxyBrowserPath(canonicalRealm, canonicalProductId)
                        : publicUrlService.rewriteForBrowser(showcase.getFrontendUrl()));

        return new ProductShowcaseResponse(
                showcase.getId(),
                canonicalRealm,
                canonicalProductId,
                productName,
                browserUrl,
                showcase.getDescription(),
                showcase.getPreviewImage(),
                showcase.getCapturedAt()
        );
    }

    private static String canonicalRealm(String realmName) {
        return realmName == null ? "" : realmName.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalProductId(String productId) {
        return productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
    }

    private static String catalogKey(String realmName, String productId) {
        return canonicalRealm(realmName) + ":" + canonicalProductId(productId);
    }
}
