package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paxaris.product_management_service.entities.ProductUrlMapping;
import com.paxaris.product_management_service.repository.ProductUrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registers products that are already running on Kubernetes (Argo CD) into {@code product_url_mapping}
 * so the home-page catalog can list and capture them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployedProductCatalogSyncService {

    private static final Pattern ADMIN_SEGMENT = Pattern.compile("-admin-");
    private static final String FRONTEND_SUFFIX = "-frontend";
    private static final String BACKEND_SUFFIX = "-backend";

    private final ProductUrlMappingRepository urlMappingRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${catalog.sync.kubernetes-enabled:true}")
    private boolean kubernetesEnabled;

    @Value("${catalog.sync.kubernetes-namespace:default}")
    private String kubernetesNamespace;

    @Value("${provisioning.external-url-scheme:http}")
    private String externalUrlScheme;

    @Value("${provisioning.external-host:192.168.49.2}")
    private String externalHost;

    /**
     * Discovers {@code *-frontend} services in the cluster and ensures URL mappings exist.
     */
    @Transactional
    public int syncDeployedProductsFromKubernetes() {
        if (!kubernetesEnabled) {
            return 0;
        }

        List<DiscoveredFrontend> discovered = discoverFrontendServices();
        int upserted = 0;
        for (DiscoveredFrontend frontend : discovered) {
            if (upsertMapping(frontend)) {
                upserted++;
            }
        }
        if (upserted > 0) {
            log.info("Catalog sync: registered or updated {} deployed product(s) from Kubernetes", upserted);
        }
        return upserted;
    }

    Optional<RealmProductRef> parseFrontendServiceName(String serviceName) {
        if (serviceName == null || !serviceName.endsWith(FRONTEND_SUFFIX)) {
            return Optional.empty();
        }
        String base = serviceName.substring(0, serviceName.length() - FRONTEND_SUFFIX.length());
        var matcher = ADMIN_SEGMENT.matcher(base);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String realm = base.substring(0, matcher.start());
        String productId = base.substring(matcher.end());
        if (realm.isBlank() || productId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RealmProductRef(realm, productId, serviceName));
    }

    private boolean upsertMapping(DiscoveredFrontend frontend) {
        Optional<RealmProductRef> refOpt = parseFrontendServiceName(frontend.serviceName());
        if (refOpt.isEmpty()) {
            return false;
        }
        RealmProductRef ref = refOpt.get();
        String backendServiceName = ref.serviceBaseName() + BACKEND_SUFFIX;

        Optional<ProductUrlMapping> existing =
                urlMappingRepository.findByRealmNameAndProductId(ref.realmName(), ref.productId());

        String frontendUrl = frontend.frontendUrl();
        String backendUrl = resolveBackendUrl(backendServiceName, existing);

        if (existing.isPresent()) {
            ProductUrlMapping mapping = existing.get();
            boolean changed = false;
            if (!frontendUrl.equals(mapping.getFrontendBaseUrl())) {
                mapping.setFrontendBaseUrl(frontendUrl);
                changed = true;
            }
            if (frontend.nodePort() != null && frontend.nodePort() > 0
                    && !frontend.nodePort().equals(mapping.getFrontendNodePort())) {
                mapping.setFrontendNodePort(frontend.nodePort());
                changed = true;
            }
            if (backendUrl != null && !backendUrl.equals(mapping.getBackendBaseUrl())) {
                mapping.setBackendBaseUrl(backendUrl);
                changed = true;
            }
            if (changed) {
                urlMappingRepository.save(mapping);
                return true;
            }
            return false;
        }

        int frontendPort = frontend.nodePort() != null && frontend.nodePort() > 0 ? frontend.nodePort() : 0;
        int backendPort = 0;

        ProductUrlMapping mapping = ProductUrlMapping.builder()
                .realmName(ref.realmName())
                .productId(ref.productId())
                .frontendNodePort(frontendPort)
                .backendNodePort(backendPort)
                .frontendBaseUrl(frontendUrl)
                .backendBaseUrl(backendUrl != null ? backendUrl : clusterServiceUrl(backendServiceName))
                .build();
        urlMappingRepository.save(mapping);
        log.info("Catalog sync: registered {} / {} at {}", ref.realmName(), ref.productId(), frontendUrl);
        return true;
    }

    private String resolveBackendUrl(String backendServiceName, Optional<ProductUrlMapping> existing) {
        if (existing.isPresent() && existing.get().getBackendBaseUrl() != null
                && !existing.get().getBackendBaseUrl().isBlank()) {
            return existing.get().getBackendBaseUrl();
        }
        return clusterServiceUrl(backendServiceName);
    }

    private List<DiscoveredFrontend> discoverFrontendServices() {
        try {
            String token = readServiceAccountToken();
            if (token == null) {
                log.debug("Catalog sync: no Kubernetes service account token; skipping cluster discovery");
                return List.of();
            }

            String url = "https://kubernetes.default.svc/api/v1/namespaces/"
                    + kubernetesNamespace + "/services";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Catalog sync: Kubernetes API returned HTTP {}", response.statusCode());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return List.of();
            }

            List<DiscoveredFrontend> result = new ArrayList<>();
            for (JsonNode item : items) {
                String name = item.path("metadata").path("name").asText(null);
                if (name == null || !name.endsWith(FRONTEND_SUFFIX)) {
                    continue;
                }
                Integer nodePort = null;
                JsonNode ports = item.path("spec").path("ports");
                if (ports.isArray() && !ports.isEmpty()) {
                    int np = ports.get(0).path("nodePort").asInt(0);
                    if (np > 0) {
                        nodePort = np;
                    }
                }
                String frontendUrl = nodePort != null
                        ? buildExternalUrl(nodePort)
                        : clusterServiceUrl(name);
                result.add(new DiscoveredFrontend(name, nodePort, frontendUrl));
            }
            return result;
        } catch (Exception ex) {
            log.warn("Catalog sync: Kubernetes discovery failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private String readServiceAccountToken() {
        Path tokenPath = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
        try {
            if (!Files.isRegularFile(tokenPath)) {
                return null;
            }
            return Files.readString(tokenPath).trim();
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildExternalUrl(int nodePort) {
        String scheme = (externalUrlScheme == null || externalUrlScheme.isBlank())
                ? "http" : externalUrlScheme.trim();
        String host = (externalHost == null || externalHost.isBlank())
                ? "192.168.49.2" : externalHost.trim();
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        return scheme + "://" + host + ":" + nodePort;
    }

    private String clusterServiceUrl(String serviceName) {
        return "http://" + serviceName + "." + kubernetesNamespace + ".svc.cluster.local";
    }

    public record RealmProductRef(String realmName, String productId, String serviceBaseName) {}

    private record DiscoveredFrontend(String serviceName, Integer nodePort, String frontendUrl) {}
}
