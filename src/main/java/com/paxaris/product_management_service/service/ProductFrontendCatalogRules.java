package com.paxaris.product_management_service.service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Home-page catalog includes only provisioned <strong>product UI</strong> frontends
 * ({@code {realm}-admin-{product}-frontend}), not platform, backend, or data services.
 */
final class ProductFrontendCatalogRules {

    private static final String FRONTEND_SUFFIX = "-frontend";
    private static final Pattern ADMIN_SEGMENT = Pattern.compile("-admin-");

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
        String realm = realmName == null ? "" : realmName.trim().toLowerCase(Locale.ROOT);
        String product = productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
        return realm + "-admin-" + product + FRONTEND_SUFFIX;
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
