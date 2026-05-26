package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.dto.CaptureShowcaseRequest;
import com.paxaris.product_management_service.repository.ProductShowcaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers showcase capture once a product is healthy on ArgoCD/Kubernetes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductShowcaseCaptureOrchestrator {

    private final ProductShowcaseService showcaseService;
    private final ProductShowcaseRepository showcaseRepository;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Async
    public void captureWhenReadyIfAbsent(String realmName, String productId) {
        String key = catalogKey(realmName, productId);
        if (!inFlight.add(key)) {
            return;
        }
        try {
            var existing = showcaseRepository.findByRealmNameIgnoreCaseAndProductIdIgnoreCase(realmName, productId);
            if (existing.isPresent() && ProductBannerService.hasCustomBanner(existing.get())) {
                log.debug("Custom banner set for {} / {}, skipping auto-capture", realmName, productId);
                return;
            }
            if (existing.isPresent() && !isPlaceholderOnly(existing.get())) {
                log.debug("Showcase already exists for {} / {}, skipping auto-capture", realmName, productId);
                return;
            }
            log.info("Auto-capturing product showcase after deployment ready: realm='{}', product='{}'",
                    realmName, productId);
            showcaseService.captureShowcase(realmName, productId, new CaptureShowcaseRequest(null));
        } catch (Exception ex) {
            log.warn("Auto showcase capture failed for {} / {}: {}", realmName, productId, ex.getMessage());
        } finally {
            inFlight.remove(key);
        }
    }

    private static boolean isPlaceholderOnly(com.paxaris.product_management_service.entities.ProductShowcase showcase) {
        String preview = showcase.getPreviewImage();
        return preview == null
                || preview.isBlank()
                || preview.startsWith("data:image/svg+xml");
    }

    private static String catalogKey(String realmName, String productId) {
        return normalize(realmName) + ":" + normalize(productId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
