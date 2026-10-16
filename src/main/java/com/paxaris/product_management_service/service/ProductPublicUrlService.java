package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.entities.ProductUrlMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds browser-facing product URLs (Paxo /product-ui proxy or NodePort) and in-cluster URLs for health/capture.
 */
@Service
public class ProductPublicUrlService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = KubernetesInClusterHttpClient.create();

    public ProductPublicUrlService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Value("${provisioning.external-url-scheme:http}")
    private String externalUrlScheme;

    @Value("${provisioning.external-host:192.168.49.2}")
    private String externalHost;

    @Value("${provisioning.browser-host:127.0.0.1}")
    private String browserHost;

    @Value("${catalog.sync.kubernetes-namespace:default}")
    private String kubernetesNamespace;

    @Value("${provisioning.paxo-public-base-url:http://127.0.0.1:4200}")
    private String paxoPublicBaseUrl;

    /** proxy = /product-ui/{realm}/{product}/ via Paxo nginx; nodeport = host:nodePort */
    @Value("${provisioning.browser-url-mode:proxy}")
    private String browserUrlMode;

    public String toBrowserUrl(ProductUrlMapping mapping) {
        if (mapping == null) {
            return null;
        }
        if (useProxyBrowserPaths()) {
            return toProxyBrowserPath(mapping.getRealmName(), mapping.getProductId());
        }
        if (mapping.getFrontendNodePort() != null && mapping.getFrontendNodePort() > 0) {
            if (mapping.getFrontendBaseUrl() != null && !mapping.getFrontendBaseUrl().isBlank()) {
                return rewriteForBrowser(mapping.getFrontendBaseUrl());
            }
            return buildUrl(browserHost, mapping.getFrontendNodePort());
        }
        return rewriteForBrowser(mapping.getFrontendBaseUrl());
    }

    /** Absolute URL for Keycloak redirect URIs and API responses (proxy or NodePort). */
    public String toAbsoluteBrowserUrl(ProductUrlMapping mapping) {
        if (mapping == null) {
            return null;
        }
        if (useProxyBrowserPaths()) {
            return toAbsoluteProxyBrowserUrl(mapping.getRealmName(), mapping.getProductId());
        }
        String relative = toBrowserUrl(mapping);
        if (relative == null || relative.isBlank()) {
            return null;
        }
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        return joinPublicBase(relative);
    }

    public String toAbsoluteProxyBrowserUrl(String realmName, String productId) {
        return joinPublicBase(toProxyBrowserPath(realmName, productId));
    }

    /** Platform catalog products (e.g. Paxo) run at the Paxo root — not under /product-ui/.../ */
    public String toAbsoluteBrowserUrlForProduct(String realmName, String productId) {
        if (ProductFrontendCatalogRules.isPlatformProductId(productId)) {
            return joinPublicBase("/");
        }
        if (useProxyBrowserPaths()) {
            return toAbsoluteProxyBrowserUrl(realmName, productId);
        }
        return null;
    }

    public String toProxyBrowserPath(String realmName, String productId) {
        String realm = realmName == null ? "" : realmName.trim().toLowerCase(Locale.ROOT);
        String product = productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
        return "/product-ui/" + realm + "/" + product + "/";
    }

    public boolean useProxyBrowserPaths() {
        return browserUrlMode != null && browserUrlMode.trim().equalsIgnoreCase("proxy");
    }

    public String toInClusterCaptureUrl(String realmName, String productId) {
        return toInClusterFrontendUrl(realmName, productId);
    }

    public String toInClusterFrontendUrl(String realmName, String productId) {
        String service = ProductFrontendCatalogRules.toFrontendServiceName(
                realmName.trim(), productId.trim());
        return "http://" + service + "." + kubernetesNamespace.trim() + ".svc.cluster.local/";
    }

    public String toInClusterBackendUrl(String realmName, String productId) {
        String service = ProductFrontendCatalogRules.toBackendServiceName(
                realmName.trim(), productId.trim());
        return "http://" + service + "." + kubernetesNamespace.trim() + ".svc.cluster.local:8080";
    }

    public String rewriteForBrowser(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url.trim());
            int port = uri.getPort();
            if (port <= 0) {
                return url;
            }
            if (uri.getHost() != null && shouldRewriteHostForBrowser(uri.getHost())) {
                return buildUrl(browserHost, port);
            }
        } catch (Exception ignored) {
            // keep original
        }
        return url;
    }

    private String joinPublicBase(String path) {
        String base = normalizePublicBase(paxoPublicBaseUrl);
        String normalizedPath = path == null ? "" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return base + normalizedPath;
    }

    private String normalizePublicBase(String base) {
        if (base == null || base.isBlank()) {
            return "http://127.0.0.1:4200";
        }
        String normalized = base.trim().replaceAll("/+$", "");
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        return normalized;
    }

    private String buildUrl(String host, int port) {
        String scheme = (externalUrlScheme == null || externalUrlScheme.isBlank())
                ? "http" : externalUrlScheme.trim().toLowerCase(Locale.ROOT);
        String normalizedHost = resolveBrowserHost(host);
        return scheme + "://" + normalizedHost + ":" + port + "/";
    }

    private String resolveBrowserHost(String host) {
        String normalized = normalizeHost(host);
        if ("auto".equalsIgnoreCase(normalized) || "minikube-auto".equalsIgnoreCase(normalized)) {
            return discoverKubernetesNodeAddress().orElse("192.168.49.2");
        }
        return normalized;
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "127.0.0.1";
        }
        return host.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private boolean shouldRewriteHostForBrowser(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (isClusterOnlyHost(host)) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || "127.0.0.1".equals(normalized)) {
            return true;
        }
        return normalized.matches("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$")
                || normalized.matches("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    }

    private static boolean isClusterOnlyHost(String host) {
        return host.endsWith(".svc.cluster.local")
                || host.endsWith(".svc")
                || "minikube".equalsIgnoreCase(host)
                || "auto".equalsIgnoreCase(host)
                || "minikube-auto".equalsIgnoreCase(host);
    }

    private Optional<String> discoverKubernetesNodeAddress() {
        try {
            String token = readServiceAccountToken();
            if (token == null) {
                return Optional.empty();
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://kubernetes.default.svc/api/v1/nodes"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            JsonNode items = objectMapper.readTree(response.body()).path("items");
            if (!items.isArray()) {
                return Optional.empty();
            }
            for (JsonNode node : items) {
                JsonNode addresses = node.path("status").path("addresses");
                if (!addresses.isArray()) {
                    continue;
                }
                String internalIp = null;
                for (JsonNode addr : addresses) {
                    String type = addr.path("type").asText("");
                    String value = addr.path("address").asText("");
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    if ("ExternalIP".equalsIgnoreCase(type)) {
                        return Optional.of(value);
                    }
                    if ("InternalIP".equalsIgnoreCase(type) && internalIp == null) {
                        internalIp = value;
                    }
                }
                if (internalIp != null) {
                    return Optional.of(internalIp);
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String readServiceAccountToken() {
        Path tokenPath = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
        try {
            if (!Files.isRegularFile(tokenPath)) {
                return null;
            }
            return Files.readString(tokenPath).trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
