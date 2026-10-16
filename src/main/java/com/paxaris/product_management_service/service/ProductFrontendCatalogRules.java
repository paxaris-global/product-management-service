package com.paxaris.product_management_service.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Home-page catalog includes only provisioned <strong>product UI</strong> frontends
 * ({@code {realm}-admin-{product}-frontend}), not platform, backend, or data services.
 */
final class ProductFrontendCatalogRules {

    private static final String FRONTEND_SUFFIX = "-frontend";
    private static final String BACKEND_SUFFIX = "-backend";
    private static final Pattern ADMIN_SEGMENT = Pattern.compile("-admin-");

    /** Platform UIs exposed as catalog products via /product-ui/{realm}/{productId}/ */
    private static final Map<String, String> PLATFORM_PRODUCT_SERVICES = Map.of(
            "paxo", "paxo-frontend"
    );

    private static final Set<String> EXCLUDED_PLATFORM_FRONTENDS = Set.of(
            "paxo-frontend",
            "python-frontend",
            "python-foundry-frontend"
    );

    private ProductFrontendCatalogRules() {}

    static boolean isProvisionedProductFrontendName(String deploymentOrServiceName) {
        return parseRealmAndProduct(deploymentOrServiceName).isPresent();
    }

    static String toFrontendDeploymentName(String realmName, String productId) {
        return toFrontendServiceName(realmName, productId);
    }

    static boolean isPlatformProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        return PLATFORM_PRODUCT_SERVICES.containsKey(productId.trim().toLowerCase(Locale.ROOT));
    }

    static String toFrontendServiceName(String realmName, String productId) {
        String realm = realmName == null ? "" : realmName.trim().toLowerCase(Locale.ROOT);
        String product = productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
        String platformService = PLATFORM_PRODUCT_SERVICES.get(product);
        if (platformService != null) {
            return platformService;
        }
        return realm + "-admin-" + product + FRONTEND_SUFFIX;
    }

    static String toBackendServiceName(String realmName, String productId) {
        String realm = realmName == null ? "" : realmName.trim().toLowerCase(Locale.ROOT);
        String product = productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
        return realm + "-admin-" + product + BACKEND_SUFFIX;
    }

    static Optional<RealmProductRef> parseRealmAndProduct(String frontendDeploymentName) {
        if (frontendDeploymentName == null || frontendDeploymentName.isBlank()) {
            return Optional.empty();
        }
        String name = frontendDeploymentName.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(FRONTEND_SUFFIX)) {
            return Optional.empty();
        }
        if (EXCLUDED_PLATFORM_FRONTENDS.contains(name)) {
            return Optional.empty();
        }
        if (name.contains("-postgres")
                || name.contains("-redis")
                || name.contains("-mysql")
                || name.endsWith("-backend")) {
            return Optional.empty();
        }
        String base = frontendDeploymentName.trim();
        base = base.substring(0, base.length() - FRONTEND_SUFFIX.length());
        var matcher = ADMIN_SEGMENT.matcher(base);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String realm = base.substring(0, matcher.start());
        String productId = base.substring(matcher.end());
        if (realm.isBlank() || productId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RealmProductRef(realm, productId, base));
    }

    record RealmProductRef(String realmName, String productId, String serviceBaseName) {}
}
