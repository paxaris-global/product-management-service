package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.entities.ProductUrlMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

/**
 * Builds browser-facing product URLs (localhost / NodePort) and in-cluster URLs for showcase capture.
 */
@Service
public class ProductPublicUrlService {

    @Value("${provisioning.external-url-scheme:http}")
    private String externalUrlScheme;

    @Value("${provisioning.external-host:192.168.49.2}")
    private String externalHost;

    @Value("${provisioning.browser-host:127.0.0.1}")
    private String browserHost;

    @Value("${catalog.sync.kubernetes-namespace:default}")
    private String kubernetesNamespace;

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
            return buildUrl(browserHost, mapping.getFrontendNodePort());
        }
        return rewriteForBrowser(mapping.getFrontendBaseUrl());
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
        String deployment = ProductFrontendCatalogRules.toFrontendDeploymentName(
                realmName.trim(), productId.trim());
        return "http://" + deployment + "." + kubernetesNamespace.trim() + ".svc.cluster.local/";
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
            String clusterHost = normalizeHost(externalHost);
            if (uri.getHost() != null
                    && (uri.getHost().equalsIgnoreCase(clusterHost)
                    || isClusterOnlyHost(uri.getHost()))) {
                return buildUrl(browserHost, port);
            }
        } catch (Exception ignored) {
            // keep original
        }
        return url;
    }

    private String buildUrl(String host, int port) {
        String scheme = (externalUrlScheme == null || externalUrlScheme.isBlank())
                ? "http" : externalUrlScheme.trim().toLowerCase(Locale.ROOT);
        String normalizedHost = normalizeHost(host);
        return scheme + "://" + normalizedHost + ":" + port + "/";
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "127.0.0.1";
        }
        return host.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private static boolean isClusterOnlyHost(String host) {
        return host.endsWith(".svc.cluster.local")
                || host.endsWith(".svc")
                || "minikube".equalsIgnoreCase(host);
    }
}
