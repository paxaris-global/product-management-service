package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.entities.ProductShowcase;
import com.paxaris.product_management_service.entities.ProductUrlMapping;
import com.paxaris.product_management_service.repository.ProductShowcaseRepository;
import com.paxaris.product_management_service.repository.ProductUrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and stores user-uploaded catalog banner images at create-product time.
 */
@Service
@RequiredArgsConstructor
public class ProductBannerService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private final ProductShowcaseRepository showcaseRepository;
    private final ProductUrlMappingRepository urlMappingRepository;
    private final ProductPublicUrlService publicUrlService;

    @Value("${showcase.banner.max-bytes:5242880}")
    private long maxBannerBytes;

    public String toDataUrl(MultipartFile file) throws IOException {
        validateBannerFile(file);
        String contentType = normalizeContentType(file.getContentType());
        String base64 = Base64.getEncoder().encodeToString(file.getBytes());
        return "data:" + contentType + ";base64," + base64;
    }

    public void validateBannerFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Banner image file is required");
        }
        if (file.getSize() > maxBannerBytes) {
            throw new IllegalArgumentException(
                    "Banner image must be at most " + (maxBannerBytes / 1024 / 1024) + " MB"
            );
        }
        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Banner must be JPEG, PNG, WebP, or GIF (received: " + file.getContentType() + ")"
            );
        }
    }

    public ProductShowcase upsertBannerShowcase(
            String realmName,
            String productId,
            String dataUrl,
            String productName
    ) {
        ProductUrlMapping mapping = urlMappingRepository.findByRealmNameAndProductId(realmName, productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product URLs are not allocated for realm='" + realmName + "', product='" + productId + "'"
                ));

        String displayName = productName == null || productName.isBlank()
                ? humanizeProductId(productId)
                : productName.trim();

        String browserUrl = publicUrlService.toBrowserUrl(mapping);
        if (browserUrl == null || browserUrl.isBlank()) {
            browserUrl = publicUrlService.rewriteForBrowser(mapping.getFrontendBaseUrl());
        }

        ProductShowcase showcase = showcaseRepository.findByRealmNameAndProductId(realmName, productId)
                .orElseGet(ProductShowcase::new);

        showcase.setRealmName(realmName);
        showcase.setProductId(productId);
        showcase.setProductName(displayName);
        showcase.setFrontendUrl(browserUrl != null ? browserUrl : mapping.getFrontendBaseUrl());
        showcase.setPreviewImage(dataUrl);
        showcase.setCustomBanner(true);
        if (showcase.getDescription() == null || showcase.getDescription().isBlank()) {
            showcase.setDescription(displayName + " — provisioned product (realm " + realmName + ").");
        }
        showcase.setCapturedAt(Instant.now());

        return showcaseRepository.save(showcase);
    }

    public static boolean hasCustomBanner(ProductShowcase showcase) {
        return showcase != null && showcase.isCustomBanner();
    }

    private static String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "image/jpeg";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String humanizeProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            return "Product";
        }
        String normalized = productId.trim().replace('-', ' ').replace('_', ' ');
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }
}
