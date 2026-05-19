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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registers provisioned <strong>product UI frontends</strong> from Kubernetes into
 * {@code product_url_mapping} for the Paxo home catalog (not backends, DBs, or platform UIs).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployedProductCatalogSyncService {

    private static final String BACKEND_SUFFIX = "-backend";
    private static final int FRONTEND_CONTAINER_PORT = 80;

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
     * Whether this realm/product belongs in the home catalog (provisioned product frontend only).
     */
    public boolean isCatalogProduct(String realmName, String productId) {
        if (realmName == null || realmName.isBlank() || productId == null || productId.isBlank()) {
            return false;
        }
        return ProductFrontendCatalogRules.isProvisionedProductFrontendName(
                ProductFrontendCatalogRules.toFrontendDeploymentName(realmName.trim(), productId.trim())
        );
    }

    /**
     * Deployment names for product UI frontends currently running in the cluster (port 80).
     */
    public Set<String> liveProductFrontendDeploymentNames() {
        if (!kubernetesEnabled) {
            return Set.of();
        }
        Map<String, Integer> serviceNodePorts = loadServiceNodePorts();
        return discoverProductFrontendDeployments(serviceNodePorts).stream()
                .map(DiscoveredFrontend::deploymentName)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    public boolean isLiveCatalogProduct(String realmName, String productId) {
        if (!isCatalogProduct(realmName, productId)) {
            return false;
        }
        String deploymentName = ProductFrontendCatalogRules.toFrontendDeploymentName(
                realmName.trim(), productId.trim());
        Set<String> live = liveProductFrontendDeploymentNames();
        if (live.isEmpty()) {
            // Local/dev without K8 API: fall back to naming rules only.
            return true;
        }
        return live.contains(deploymentName);
    }

    Optional<ProductFrontendCatalogRules.RealmProductRef> parseFrontendServiceName(String serviceName) {
        return ProductFrontendCatalogRules.parseRealmAndProduct(serviceName);
    }

    /**
     * Discovers product UI {@code Deployment}s (port 80, {@code *-admin-*-frontend}) and ensures URL mappings exist.
     */
    @Transactional
    public int syncDeployedProductsFromKubernetes() {
        if (!kubernetesEnabled) {
            return 0;
        }

        Map<String, Integer> serviceNodePorts = loadServiceNodePorts();
        List<DiscoveredFrontend> discovered = discoverProductFrontendDeployments(serviceNodePorts);
        int upserted = 0;
        for (DiscoveredFrontend frontend : discovered) {
            if (upsertMapping(frontend)) {
                upserted++;
            }
        }
        if (upserted > 0) {
            log.info("Catalog sync: registered or updated {} product frontend(s) from Kubernetes", upserted);
        }
        return upserted;
    }

    private boolean upsertMapping(DiscoveredFrontend frontend) {
        Optional<ProductFrontendCatalogRules.RealmProductRef> refOpt =
                ProductFrontendCatalogRules.parseRealmAndProduct(frontend.deploymentName());
        if (refOpt.isEmpty()) {
            return false;
        }
        ProductFrontendCatalogRules.RealmProductRef ref = refOpt.get();
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

        ProductUrlMapping mapping = ProductUrlMapping.builder()
                .realmName(ref.realmName())
                .productId(ref.productId())
                .frontendNodePort(frontendPort)
                .backendNodePort(0)
                .frontendBaseUrl(frontendUrl)
                .backendBaseUrl(backendUrl != null ? backendUrl : clusterServiceUrl(backendServiceName))
                .build();
        urlMappingRepository.save(mapping);
        log.info("Catalog sync: registered product frontend {} / {} at {}", ref.realmName(), ref.productId(), frontendUrl);
        return true;
    }

    private String resolveBackendUrl(String backendServiceName, Optional<ProductUrlMapping> existing) {
        if (existing.isPresent() && existing.get().getBackendBaseUrl() != null
                && !existing.get().getBackendBaseUrl().isBlank()) {
            return existing.get().getBackendBaseUrl();
        }
        return clusterServiceUrl(backendServiceName);
    }

    private List<DiscoveredFrontend> discoverProductFrontendDeployments(Map<String, Integer> serviceNodePorts) {
        try {
            String token = readServiceAccountToken();
            if (token == null) {
                log.debug("Catalog sync: no Kubernetes service account token; skipping cluster discovery");
                return List.of();
            }

            String url = "https://kubernetes.default.svc/apis/apps/v1/namespaces/"
                    + kubernetesNamespace + "/deployments";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Catalog sync: Kubernetes deployments API returned HTTP {}", response.statusCode());
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
                if (!ProductFrontendCatalogRules.isProvisionedProductFrontendName(name)) {
                    continue;
                }
                if (!hasFrontendContainerPort(item)) {
                    log.debug("Catalog sync: skip {} (not a UI frontend deployment)", name);
                    continue;
                }

                Integer nodePort = serviceNodePorts.get(name);
                String frontendUrl = nodePort != null && nodePort > 0
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

    private boolean hasFrontendContainerPort(JsonNode deployment) {
        JsonNode containers = deployment.path("spec").path("template").path("spec").path("containers");
        if (!containers.isArray() || containers.isEmpty()) {
            return false;
        }
        for (JsonNode container : containers) {
            JsonNode ports = container.path("ports");
            if (!ports.isArray()) {
                continue;
            }
            for (JsonNode port : ports) {
                if (port.path("containerPort").asInt(0) == FRONTEND_CONTAINER_PORT) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Integer> loadServiceNodePorts() {
        Map<String, Integer> nodePorts = new HashMap<>();
        try {
            String token = readServiceAccountToken();
            if (token == null) {
                return nodePorts;
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
                return nodePorts;
            }
            JsonNode items = objectMapper.readTree(response.body()).path("items");
            if (!items.isArray()) {
                return nodePorts;
            }
            for (JsonNode item : items) {
                String name = item.path("metadata").path("name").asText(null);
                if (name == null || !ProductFrontendCatalogRules.isProvisionedProductFrontendName(name)) {
                    continue;
                }
                JsonNode ports = item.path("spec").path("ports");
                if (ports.isArray() && !ports.isEmpty()) {
                    int np = ports.get(0).path("nodePort").asInt(0);
                    if (np > 0) {
                        nodePorts.put(name, np);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Catalog sync: could not load service node ports: {}", ex.getMessage());
        }
        return nodePorts;
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

    private record DiscoveredFrontend(String deploymentName, Integer nodePort, String frontendUrl) {}

    /** @deprecated Use {@link ProductFrontendCatalogRules.RealmProductRef} */
    @Deprecated
    public record RealmProductRef(String realmName, String productId, String serviceBaseName) {}
}
