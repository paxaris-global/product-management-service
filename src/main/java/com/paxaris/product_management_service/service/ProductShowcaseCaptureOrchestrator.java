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
        String key = realmName + ":" + productId;
        if (!inFlight.add(key)) {
            return;
        }
        try {
            if (showcaseRepository.findByRealmNameAndProductId(realmName, productId).isPresent()) {
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
}
