package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paxaris.product_management_service.dto.ProductDeploymentStatusResponse;
import com.paxaris.product_management_service.dto.ProductProvisioningResponse;
import com.paxaris.product_management_service.entities.ProductUrlMapping;
import com.paxaris.product_management_service.repository.ProductUrlMappingRepository;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.*;

@Service
public class ProvisioningService {

    // Add SLF4J Logger
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProvisioningService.class);

    private static final int TREE_BATCH_SIZE = 200;
    private static final long MAX_GITHUB_BLOB_BYTES = 50L * 1024 * 1024;
    private static final int REPO_READY_MAX_RETRIES = 20;
    private static final long REPO_READY_RETRY_DELAY_MS = 1500;
    private static final Set<Integer> RESERVED_NODE_PORTS = Set.of(32080, 32087, 32088, 31686, 30417, 30418);
    private static final List<String> SKIPPED_PATH_SEGMENTS = List.of(
            "/.git/", "/node_modules/", "/target/", "/build/", "/dist/", "/out/",
            "/.idea/", "/.vscode/", "/__macosx/", "/.angular/", "/.nx/", "/.gradle/",
            "/.mvn/", "/.next/", "/.nuxt/", "/.cache/", "/.pytest_cache/", "/.venv/",
            "/venv/", "/__pycache__/", "/coverage/", "/.sonar/"
    );

    private final String githubToken;
    private final String githubOrg;
    private final String githubApiBaseUrl;
    private final String defaultAdminUsername;
    private final String paxoOrg;
    private final String paxoRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String dockerHubUsername;
    private final String dockerHubToken;
    private final ProductUrlMappingRepository productUrlMappingRepository;
    private final ProductShowcaseCaptureOrchestrator showcaseCaptureOrchestrator;
    private final String externalUrlScheme;
    private final String externalHost;
    private final int frontendNodePortStart;
    private final int frontendNodePortEnd;
    private final int backendNodePortStart;
    private final int backendNodePortEnd;

    private record BuildSpec(String contextPath, String dockerfilePath) {
    }

    /** Detected database/cache needs from backend source (docker-compose / application.yml). */
    private record DataStackRequirements(
            boolean needsPostgres,
            boolean needsRedis,
            String databaseName,
            String databaseUser,
            String databasePassword
    ) {
        static DataStackRequirements none() {
            return new DataStackRequirements(false, false, "app", "app_user", "app_pass");
        }
    }

    public ProvisioningService(
            @Value("${github.token}") String githubToken,
            @Value("${github.org}") String githubOrg,
            @Value("${github.api.base-url}") String githubApiBaseUrl,
            @Value("${provisioning.default-admin-username}") String defaultAdminUsername,
            @Value("${paxo.org:paxaris-global}") String paxoOrg,
            @Value("${paxo.repo:paxo}") String paxoRepo,
            @Value("${docker.hub.username:}") String dockerHubUsername,
            @Value("${docker.hub.token:}") String dockerHubToken,
            ProductUrlMappingRepository productUrlMappingRepository,
            @Lazy ProductShowcaseCaptureOrchestrator showcaseCaptureOrchestrator,
            @Value("${provisioning.external-url-scheme:http}") String externalUrlScheme,
            @Value("${provisioning.external-host:192.168.49.2}") String externalHost,
            @Value("${provisioning.frontend-node-port-start:32100}") int frontendNodePortStart,
            @Value("${provisioning.frontend-node-port-end:32399}") int frontendNodePortEnd,
            @Value("${provisioning.backend-node-port-start:32400}") int backendNodePortStart,
            @Value("${provisioning.backend-node-port-end:32699}") int backendNodePortEnd) {
        this.githubToken = githubToken;
        this.githubOrg = githubOrg;
        this.githubApiBaseUrl = githubApiBaseUrl;
        this.defaultAdminUsername = defaultAdminUsername;
        this.paxoOrg = paxoOrg;
        this.paxoRepo = paxoRepo;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
        this.dockerHubUsername = dockerHubUsername;
        this.dockerHubToken = dockerHubToken;
        this.productUrlMappingRepository = productUrlMappingRepository;
        this.showcaseCaptureOrchestrator = showcaseCaptureOrchestrator;
        this.externalUrlScheme = externalUrlScheme;
        this.externalHost = externalHost;
        this.frontendNodePortStart = frontendNodePortStart;
        this.frontendNodePortEnd = frontendNodePortEnd;
        this.backendNodePortStart = backendNodePortStart;
        this.backendNodePortEnd = backendNodePortEnd;
    }

    public String getGithubToken() {
        return githubToken;
    }

    public String getGithubOrg() {
        return githubOrg;
    }

    /**
     * Entry point for provisioning: Creates repo, unzips file, and uploads in
     * incremental commits to avoid large Git tree payloads.
     */
    public Path provision(String repoName, MultipartFile zipFile) throws Exception {
        return provisionRepositoryInternal(repoName, zipFile, null, null);
    }

    public ProductProvisioningResponse provisionProduct(
            String realmName,
            String productId,
            String backendRepoName,
            String frontendRepoName,
            MultipartFile backendZip,
            MultipartFile frontendZip
    ) throws Exception {
        String normalizedBackendRepo = normalizeRepositoryName(backendRepoName);
        String normalizedFrontendRepo = normalizeRepositoryName(frontendRepoName);
        ProductUrlMapping mapping = allocateAndSaveProductUrls(realmName, productId);

        provisionRepositoryInternal(normalizedBackendRepo, backendZip, mapping.getBackendNodePort(), null);
        provisionRepositoryInternal(
            normalizedFrontendRepo,
            frontendZip,
            mapping.getFrontendNodePort(),
            toK8sName(normalizedBackendRepo, 63)
        );

        return new ProductProvisioningResponse(
            "success",
            realmName,
            productId,
            normalizedBackendRepo,
            normalizedFrontendRepo,
            mapping.getFrontendNodePort(),
            mapping.getBackendNodePort(),
            mapping.getFrontendBaseUrl(),
            mapping.getBackendBaseUrl()
        );
    }

    /**
     * Reserves NodePorts and public URLs for a product before long-running provisioning.
     * Idempotent: returns existing mapping when the product was already allocated.
     */
    public ProductDeploymentStatusResponse getProductDeploymentStatus(String realmName, String productId) {
        Optional<ProductUrlMapping> mappingOpt =
                productUrlMappingRepository.findByRealmNameIgnoreCaseAndProductIdIgnoreCase(
                        realmName, productId);
        if (mappingOpt.isEmpty()) {
            return new ProductDeploymentStatusResponse(
                    "not_found",
                    realmName,
                    productId,
                    null,
                    null,
                    false,
                    false,
                    false,
                    0,
                    "NOT_ALLOCATED",
                    "Product URLs are not allocated yet"
            );
        }

        ProductUrlMapping mapping = mappingOpt.get();
        String frontendUrl = mapping.getFrontendBaseUrl();
        String backendUrl = mapping.getBackendBaseUrl();
        boolean frontendReady = pingUrl(frontendUrl);
        boolean backendReady = pingBackendHealth(backendUrl);
        boolean ready = frontendReady && backendReady;

        int progressPercent = 45;
        if (backendReady) {
            progressPercent += 27;
        }
        if (frontendReady) {
            progressPercent += 28;
        }
        if (!ready) {
            progressPercent = Math.min(progressPercent, 95);
        }

        String phase = ready ? "RUNNING" : (backendReady || frontendReady ? "STARTING" : "SYNCING");
        String message = ready
                ? "Product is live on ArgoCD/Kubernetes"
                : buildDeploymentStatusMessage(frontendReady, backendReady);

        if (ready) {
            showcaseCaptureOrchestrator.captureWhenReadyIfAbsent(realmName, productId);
        }

        return new ProductDeploymentStatusResponse(
                ready ? "ready" : "pending",
                realmName,
                productId,
                frontendUrl,
                backendUrl,
                frontendReady,
                backendReady,
                ready,
                ready ? 100 : progressPercent,
                phase,
                message
        );
    }

    private String buildDeploymentStatusMessage(boolean frontendReady, boolean backendReady) {
        if (!frontendReady && !backendReady) {
            return "Waiting for ArgoCD to sync backend, frontend, database, and pods";
        }
        if (backendReady && !frontendReady) {
            return "Backend is up; waiting for frontend pod";
        }
        if (!backendReady && frontendReady) {
            return "Frontend is up; waiting for backend and database";
        }
        return "Finalizing health checks";
    }

    private boolean pingUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        for (String path : List.of("", "/")) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalized + path))
                        .timeout(java.time.Duration.ofSeconds(4))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                if (code >= 200 && code < 500) {
                    return true;
                }
            } catch (Exception ex) {
                log.debug("Health check failed for {}: {}", normalized + path, ex.getMessage());
            }
        }
        return false;
    }

    private boolean pingBackendHealth(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        List<String> candidates = List.of(
                normalized + "/api/v1/actuator/health",
                normalized + "/actuator/health",
                normalized + "/api/v1/health",
                normalized + "/health",
                normalized + "/"
        );
        for (String url : candidates) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(4))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    String body = response.body() == null ? "" : response.body().toLowerCase(Locale.ROOT);
                    if (url.contains("actuator") || url.contains("health")) {
                        if (body.contains("\"status\":\"up\"") || body.contains("status\":\"up")
                                || body.contains("ok")) {
                            return true;
                        }
                    } else {
                        return true;
                    }
                }
            } catch (Exception ex) {
                log.debug("Backend health check failed for {}: {}", url, ex.getMessage());
            }
        }
        return false;
    }

    public ProductProvisioningResponse allocateProductUrls(String realmName, String productId) {
        ProductUrlMapping mapping = allocateAndSaveProductUrls(realmName, productId);
        return new ProductProvisioningResponse(
                "success",
                realmName,
                productId,
                null,
                null,
                mapping.getFrontendNodePort(),
                mapping.getBackendNodePort(),
                mapping.getFrontendBaseUrl(),
                mapping.getBackendBaseUrl()
        );
    }

    private Path provisionRepositoryInternal(
            String repoName,
            MultipartFile zipFile,
            Integer nodePort,
            String frontendBackendServiceName
    ) throws Exception {
        String normalizedRepoName = normalizeRepositoryName(repoName);

        createRepo(normalizedRepoName);
        waitForRepositoryReady(normalizedRepoName);
                configureRepositoryActions(normalizedRepoName);
        Path tempDir = unzip(zipFile);
                setRepoGithubActionsVariables(normalizedRepoName);
                if (frontendBackendServiceName != null && !frontendBackendServiceName.isBlank()) {
                    rewriteFrontendBackendUpstream(tempDir, frontendBackendServiceName);
                }
                ensureRepositoryTemplates(normalizedRepoName, tempDir, nodePort);
        uploadDirectoryToGitHub(tempDir, normalizedRepoName);
        generateAndDeployManifests(normalizedRepoName, tempDir, nodePort);
        return tempDir;
    }

        private void configureRepositoryActions(String repoName) throws IOException {
                String actionsPermissionsUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName + "/actions/permissions";
                String workflowPermissionsUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName + "/actions/permissions/workflow";

                String actionsPermissionsBody = """
                                {
                                    "enabled": true,
                                    "allowed_actions": "all"
                                }
                                """;

                String workflowPermissionsBody = """
                                {
                                    "default_workflow_permissions": "write",
                                    "can_approve_pull_request_reviews": true
                                }
                                """;

                sendRequest("PUT", actionsPermissionsUrl, actionsPermissionsBody);
                try {
                        sendRequest("PUT", workflowPermissionsUrl, workflowPermissionsBody);
                } catch (RuntimeException ex) {
                        // 409 = org-level enforcement blocks per-repo default_workflow_permissions override.
                        // Inline `permissions: contents: write` in the generated workflow YAML takes precedence,
                        // so this setting is not required for the pipeline to work.
                        if (ex.getMessage() != null && ex.getMessage().contains("(409)")) {
                                log.warn("⚠️ Skipping workflow permissions update for {} (org policy blocks it, inline workflow permissions apply): {}",
                                        repoName, ex.getMessage().lines().findFirst().orElse("conflict"));
                        } else {
                                throw ex;
                        }
                }
        }

    private void waitForRepositoryReady(String repoName) throws IOException {
        String repoUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= REPO_READY_MAX_RETRIES; attempt++) {
            try {
                JsonNode repoNode = sendRequest("GET", repoUrl, null);
                String fullName = repoNode.path("full_name").asText();
                if (fullName != null && !fullName.isBlank()) {
                    return;
                }
            } catch (RuntimeException ex) {
                lastError = ex;
                if (!ex.getMessage().contains("(404)")) {
                    throw ex;
                }
            }

            try {
                Thread.sleep(REPO_READY_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for GitHub repository readiness", ie);
            }
        }

        throw new RuntimeException("Repository not ready after retries: " + githubOrg + "/" + repoName,
                lastError);
    }

        private void ensureRepositoryTemplates(String repoName, Path tempDir, Integer nodePort) throws IOException {
            boolean isBackend = repoName.endsWith("-backend");
            int containerPort = isBackend ? 8080 : 80;
            BuildSpec buildSpec = resolveBuildSpec(tempDir, isBackend);

                Path dockerfilePath = tempDir.resolve("Dockerfile");
                if (Files.notExists(dockerfilePath)) {
                        String dockerfile = isBackend ? generateBackendDockerfile() : generateFrontendDockerfile();
                        Files.writeString(dockerfilePath, dockerfile);
                }

                Path k8Dir = tempDir.resolve("k8");
                Path k8ManifestPath = k8Dir.resolve("deployment.yaml");
                if (Files.notExists(k8ManifestPath) || nodePort != null) {
                        Files.createDirectories(k8Dir);
                        if (isBackend) {
                                DataStackRequirements stack = detectDataStack(tempDir);
                                String k8Name = toK8sName(repoName, 63);
                                Files.writeString(
                                        k8ManifestPath,
                                        generateBackendK8Manifest(repoName, nodePort, stack, k8Name)
                                );
                                if (stack.needsPostgres()) {
                                        Files.writeString(
                                                k8Dir.resolve("postgres.yaml"),
                                                generatePostgresManifest(k8Name, stack)
                                        );
                                }
                                if (stack.needsRedis()) {
                                        Files.writeString(k8Dir.resolve("redis.yaml"), generateRedisManifest(k8Name));
                                }
                        } else {
                                Files.writeString(k8ManifestPath, generateRepositoryK8Manifest(repoName, containerPort, nodePort));
                        }
                }

                Path workflowsDir = tempDir.resolve(".github").resolve("workflows");
                Files.createDirectories(workflowsDir);

                // Always enforce the managed workflow so image naming and architectures stay consistent.
                Path workflowPath = workflowsDir.resolve("gitops-deploy.yml");
                Files.writeString(
                    workflowPath,
                    generateRepositoryWorkflow(repoName, buildSpec.contextPath(), buildSpec.dockerfilePath())
                );

                // Remove Foundry/legacy workflows that expect repo secrets or duplicate CI.
                // Provisioning only configures Actions *variables* (DOCKERHUB_*); secret-based triggers fail with
                // "Password required" from docker/login-action@v3.
                try (var workflowFiles = Files.list(workflowsDir)) {
                    workflowFiles
                            .filter(Files::isRegularFile)
                            .filter(path -> {
                                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                                return name.endsWith(".yml") || name.endsWith(".yaml");
                            })
                            .filter(path -> {
                                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                                return !name.equals("gitops-deploy.yml");
                            })
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                    log.info("Removed conflicting workflow '{}' from {}", path.getFileName(), repoName);
                                } catch (IOException ex) {
                                    throw new RuntimeException("Failed to remove workflow " + path, ex);
                                }
                            });
                }
        }

        private String generateBackendDockerfile() {
                return """
                                FROM maven:3.9.8-eclipse-temurin-21 AS builder
                                WORKDIR /app
                                COPY pom.xml ./
                                COPY src ./src
                                RUN mvn -B -DskipTests clean package

                                FROM eclipse-temurin:21-jre
                                WORKDIR /app
                                COPY --from=builder /app/target/*.jar app.jar
                                EXPOSE 8080
                                ENTRYPOINT [\"java\",\"-jar\",\"/app/app.jar\"]
                                """;
        }

        private String generateFrontendDockerfile() {
                return """
                                FROM node:20-alpine AS builder
                                WORKDIR /app
                                COPY package*.json ./
                                RUN npm ci
                                COPY . .
                                RUN npm run build

                                FROM nginx:1.27-alpine
                                COPY --from=builder /app/dist /usr/share/nginx/html
                                EXPOSE 80
                                CMD [\"nginx\",\"-g\",\"daemon off;\"]
                                """;
        }

        private String generateRepositoryK8Manifest(String repoName, int containerPort, Integer nodePort) {
            String k8Name = toK8sName(repoName, 63);
            String imageRepo = generateImageRepository(repoName);
            String nodePortBlock = nodePort != null ? "            nodePort: " + nodePort + "\n" : "";
            String serviceType = nodePort != null ? "NodePort" : "ClusterIP";
                return """
                                apiVersion: apps/v1
                                kind: Deployment
                                metadata:
                                    name: %s
                                spec:
                                    replicas: 1
                                    selector:
                                        matchLabels:
                                            app: %s
                                    template:
                                        metadata:
                                            labels:
                                                app: %s
                                        spec:
                                            imagePullSecrets:
                                                - name: dockerhub-secret
                                            containers:
                                                - name: %s
                                                    image: \"%s:latest\"
                                                    imagePullPolicy: Always
                                                    ports:
                                                        - containerPort: %d
                                ---
                                apiVersion: v1
                                kind: Service
                                metadata:
                                    name: %s
                                spec:
                                    selector:
                                        app: %s
                                    ports:
                                        - port: %d
                                            targetPort: %d
%s                                    type: %s
                                """.formatted(
                                    k8Name,
                                    k8Name,
                                    k8Name,
                                    k8Name,
                                    imageRepo,
                                    containerPort,
                                    k8Name,
                                    k8Name,
                                    containerPort,
                                    containerPort,
                                    nodePortBlock,
                                    serviceType
                                );
        }

        private String generateRepositoryWorkflow(String repoName, String buildContextPath, String dockerfilePath) {
            String imageRepo = generateImageRepository(repoName);
                return """
                                name: Build Push And GitOps Update

                                on:
                                    push:
                                        branches:
                                            - main
                                            - master

                                permissions:
                                    contents: write

                                jobs:
                                    build-and-update:
                                        if: github.actor != 'github-actions[bot]'
                                        runs-on: ubuntu-latest
                                        steps:
                                            - name: Checkout
                                              uses: actions/checkout@v4

                                            - name: Set image variables
                                              id: vars
                                              run: |
                                                    IMAGE_REPO="%s"
                                                    IMAGE_TAG="${GITHUB_SHA}"
                                                    echo "image_repo=$IMAGE_REPO" >> "$GITHUB_OUTPUT"
                                                    echo "image_tag=$IMAGE_TAG" >> "$GITHUB_OUTPUT"

                                            - name: Login to Docker Hub
                                              uses: docker/login-action@v3
                                              with:
                                                    username: ${{ vars.DOCKERHUB_USERNAME }}
                                                    password: ${{ vars.DOCKERHUB_TOKEN }}

                                            - name: Set up Docker Buildx
                                              uses: docker/setup-buildx-action@v3

                                            - name: Set up QEMU
                                              uses: docker/setup-qemu-action@v3

                                            - name: Build and push image
                                              uses: docker/build-push-action@v6
                                              with:
                                                    context: %s
                                                    file: %s
                                                    platforms: linux/amd64,linux/arm64
                                                    push: true
                                                    tags: |
                                                        ${{ steps.vars.outputs.image_repo }}:latest
                                                        ${{ steps.vars.outputs.image_repo }}:${{ steps.vars.outputs.image_tag }}

                                            - name: Update k8 image tag
                                              run: |
                                                    sed -E -i.bak "s|^([[:space:]]*)image:[[:space:]].*|\\1image: ${{ steps.vars.outputs.image_repo }}:${{ steps.vars.outputs.image_tag }}|" k8/deployment.yaml
                                                    rm -f k8/deployment.yaml.bak

                                            - name: Commit and push manifest changes
                                              run: |
                                                    if git diff --quiet; then
                                                        echo "No manifest changes to commit"
                                                        exit 0
                                                    fi
                                                    git config user.name "github-actions[bot]"
                                                    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
                                                    git add k8/deployment.yaml
                                                    git commit -m "ci: update image tag [skip ci]"
                                                    git push
                                """.formatted(imageRepo, buildContextPath, dockerfilePath);
        }

    private BuildSpec resolveBuildSpec(Path tempDir, boolean isBackend) throws IOException {
        String manifestFileName = isBackend ? "pom.xml" : "package.json";

        if (Files.exists(tempDir.resolve(manifestFileName)) && Files.exists(tempDir.resolve("Dockerfile"))) {
            return new BuildSpec(".", "./Dockerfile");
        }

        String conventionalAppDir = isBackend ? "backend" : "frontend";
        Path conventionalDir = tempDir.resolve(conventionalAppDir);
        if (Files.exists(conventionalDir.resolve(manifestFileName)) && Files.exists(conventionalDir.resolve("Dockerfile"))) {
            return new BuildSpec("./" + conventionalAppDir, "./" + conventionalAppDir + "/Dockerfile");
        }

        try (var children = Files.list(tempDir)) {
            List<Path> childDirs = children
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equals(".github"))
                    .filter(path -> !path.getFileName().toString().equals("k8"))
                    .filter(path -> !path.getFileName().toString().equals("__MACOSX"))
                    .toList();

            for (Path childDir : childDirs) {
                Path childManifest = childDir.resolve(manifestFileName);
                Path childDockerfile = childDir.resolve("Dockerfile");
                if (Files.exists(childManifest) && Files.exists(childDockerfile)) {
                    String rel = tempDir.relativize(childDir).toString().replace("\\", "/");
                    return new BuildSpec("./" + rel, "./" + rel + "/Dockerfile");
                }

                Path nestedConventionalDir = childDir.resolve(conventionalAppDir);
                Path nestedManifest = nestedConventionalDir.resolve(manifestFileName);
                Path nestedDockerfile = nestedConventionalDir.resolve("Dockerfile");
                if (Files.exists(nestedManifest) && Files.exists(nestedDockerfile)) {
                    String rel = tempDir.relativize(nestedConventionalDir).toString().replace("\\", "/");
                    return new BuildSpec("./" + rel, "./" + rel + "/Dockerfile");
                }
            }
        }

        return new BuildSpec(".", "./Dockerfile");
    }

    public String generateRepositoryName(String realmName, String adminUsername, String productName) {
        String adminPart = adminUsername != null ? adminUsername : defaultAdminUsername;
        return normalizeRepositoryName(String.format("%s-%s-%s", realmName, adminPart, productName));
    }

    private String normalizeRepositoryName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Repository name cannot be blank");
        }

        String normalized = rawName.toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Repository name became empty after normalization");
        }

        return normalized;
    }

    // --------------------------------------------------
    // CREATE GITHUB REPO
    // --------------------------------------------------
        // --------------------------------------------------
        // SET GITHUB ACTIONS VARIABLES (DockerHub)
        // --------------------------------------------------
        private void setRepoGithubActionsVariables(String repoName) throws IOException {
            if (dockerHubUsername == null || dockerHubUsername.isBlank()
                    || dockerHubToken == null || dockerHubToken.isBlank()) {
                throw new IllegalStateException("Docker Hub credentials are missing (DOCKER_USERNAME / DOCKER_PASSWORD)");
            }
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("DOCKERHUB_USERNAME", dockerHubUsername);
            variables.put("DOCKERHUB_TOKEN", dockerHubToken);
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String baseUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName + "/actions/variables";
                String createBody = "{\"name\":\"" + entry.getKey() + "\",\"value\":\"" + entry.getValue() + "\"}";
                try {
                    sendRequest("POST", baseUrl, createBody);
                } catch (RuntimeException ex) {
                    if (ex.getMessage().contains("(409)")) {
                        String updateUrl = baseUrl + "/" + entry.getKey();
                        String updateBody = "{\"name\":\"" + entry.getKey() + "\",\"value\":\"" + entry.getValue() + "\"}";
                        sendRequest("PATCH", updateUrl, updateBody);
                    } else {
                        throw ex;
                    }
                }
            }
        }

        // --------------------------------------------------
        // CREATE GITHUB REPO
        // --------------------------------------------------
    public void createRepo(String repoName) throws IOException {
        validateConfig();

        if (repositoryExists(repoName)) {
            log.info("Repository '{}' already exists in org '{}'; reusing for provisioning", repoName, githubOrg);
            return;
        }

        String apiUrl = githubApiBaseUrl + "/orgs/" + githubOrg + "/repos";

        // auto_init: true is required to create the 'main' branch so we can update it
        // later
        String body = """
                {
                  "name": "%s",
                  "private": false,
                  "auto_init": true
                }
                """.formatted(repoName);

        try {
            sendRequest("POST", apiUrl, body);
        } catch (RuntimeException e) {
            if (isRepositoryAlreadyExistsError(e)) {
                log.info("Repository '{}' already exists (create race); continuing", repoName);
                return;
            }
            throw e;
        }
    }

    private boolean repositoryExists(String repoName) throws IOException {
        String url = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName;
        int status = sendRequestStatus("GET", url, null);
        if (status == 200) {
            return true;
        }
        if (status == 404) {
            return false;
        }
        throw new RuntimeException("GitHub API error (" + status + ") checking repository at " + url);
    }

    private boolean isRepositoryAlreadyExistsError(RuntimeException error) {
        String message = error.getMessage();
        return message != null
                && message.contains("(422)")
                && message.contains("name already exists");
    }

    private int sendRequestStatus(String method, String urlStr, String jsonBody) throws IOException {
        HttpRequest.BodyPublisher bodyPublisher = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method(method, bodyPublisher)
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + method + " " + urlStr, e);
        }
        return response.statusCode();
    }

    // --------------------------------------------------
    // UPLOAD FILES (BATCHED COMMITS)
    // --------------------------------------------------
    public void uploadDirectoryToGitHub(Path root, String repo) throws Exception {
        List<FileBlobRef> fileRefs = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();

        // 1) Walk files and create blobs in GitHub
        Files.walk(root)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    String path = root.relativize(file).toString().replace("\\", "/");
                    try {
                        if (shouldSkipPath(path)) {
                            skippedFiles.add(path);
                            return;
                        }

                        byte[] content = Files.readAllBytes(file);

                        if (content.length > MAX_GITHUB_BLOB_BYTES) {
                            skippedFiles.add(path);
                            log.warn("Skipping file '{}' ({} bytes) because it exceeds GitHub blob safety limit ({} bytes)",
                                    path, content.length, MAX_GITHUB_BLOB_BYTES);
                            return;
                        }

                        String blobSha = createBlob(repo, content);
                        fileRefs.add(new FileBlobRef(path, blobSha));
                    } catch (IOException e) {
                        if (shouldSkipPath(path)) {
                            skippedFiles.add(path);
                            log.warn("Skipping unreadable generated/junk file '{}': {}", path, e.getMessage());
                            return;
                        }
                        throw new RuntimeException("Error reading file for GitHub upload: " + file, e);
                    } catch (Exception e) {
                        throw new RuntimeException("Error creating GitHub blob for file: " + file, e);
                    }
                });

        if (fileRefs.isEmpty()) {
            if (!skippedFiles.isEmpty()) {
                throw new RuntimeException("No eligible source files found to upload; all files were filtered as generated or oversized artifacts");
            }
            return;
        }

        if (!skippedFiles.isEmpty()) {
        }

        // 2) Read current main branch state to append commits incrementally
        BranchState branchState = fetchMainBranchState(repo);
        String currentCommitSha = branchState.commitSha();
        String currentTreeSha = branchState.treeSha();

        // 3) Build commits in batches to keep tree payload small
        int totalBatches = (int) Math.ceil(fileRefs.size() / (double) TREE_BATCH_SIZE);
        for (int start = 0, batch = 1; start < fileRefs.size(); start += TREE_BATCH_SIZE, batch++) {
            int end = Math.min(start + TREE_BATCH_SIZE, fileRefs.size());
            List<Map<String, Object>> treeEntries = new ArrayList<>(end - start);

            for (int i = start; i < end; i++) {
                FileBlobRef ref = fileRefs.get(i);
                Map<String, Object> entry = new HashMap<>();
                entry.put("path", ref.path());
                entry.put("mode", "100644");
                entry.put("type", "blob");
                entry.put("sha", ref.sha());
                treeEntries.add(entry);
            }

            Map<String, Object> treeMap = Map.of(
                    "base_tree", currentTreeSha,
                    "tree", treeEntries
            );
            JsonNode treeRes = sendRequest(
                    "POST",
                    githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/trees",
                    objectMapper.writeValueAsString(treeMap)
            );
            String newTreeSha = treeRes.get("sha").asText();

            Map<String, Object> commitMap = Map.of(
                    "message", "Provision upload batch " + batch + "/" + totalBatches,
                    "tree", newTreeSha,
                    "parents", List.of(currentCommitSha)
            );
            JsonNode commitRes = sendRequest(
                    "POST",
                    githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/commits",
                    objectMapper.writeValueAsString(commitMap)
            );

            currentCommitSha = commitRes.get("sha").asText();
            currentTreeSha = newTreeSha;
        }

        // 4) Move main branch to the latest commit
        Map<String, Object> refMap = Map.of("sha", currentCommitSha, "force", false);
        sendRequest(
                "PATCH",
                githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/refs/heads/main",
                objectMapper.writeValueAsString(refMap)
        );

            // ...existing code...
    }

    private BranchState fetchMainBranchState(String repo) throws IOException {
        JsonNode refRes = sendRequest(
                "GET",
                githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/ref/heads/main",
                null
        );
        String headCommitSha = refRes.path("object").path("sha").asText();
        if (headCommitSha == null || headCommitSha.isBlank()) {
            throw new RuntimeException("Failed to resolve main branch HEAD for repo: " + repo);
        }

        JsonNode commitRes = sendRequest(
                "GET",
                githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/commits/" + headCommitSha,
                null
        );
        String headTreeSha = commitRes.path("tree").path("sha").asText();
        if (headTreeSha == null || headTreeSha.isBlank()) {
            throw new RuntimeException("Failed to resolve main branch tree for repo: " + repo);
        }

        return new BranchState(headCommitSha, headTreeSha);
    }

    private String createBlob(String repo, byte[] content) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(content);
        Map<String, Object> blobBody = Map.of(
                "content", encoded,
                "encoding", "base64"
        );

        JsonNode blobRes = sendRequest(
                "POST",
                githubApiBaseUrl + "/repos/" + githubOrg + "/" + repo + "/git/blobs",
                objectMapper.writeValueAsString(blobBody)
        );
        String blobSha = blobRes.path("sha").asText();
        if (blobSha == null || blobSha.isBlank()) {
            throw new RuntimeException("GitHub blob SHA missing for repository: " + repo);
        }
        return blobSha;
    }

    private record BranchState(String commitSha, String treeSha) {
    }

    private record FileBlobRef(String path, String sha) {
    }

    private boolean shouldSkipPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return true;
        }
        String posix = relativePath.replace('\\', '/');
        String normalized = "/" + posix.toLowerCase(Locale.ROOT) + "/";
        for (String segment : SKIPPED_PATH_SEGMENTS) {
            if (normalized.contains(segment)) {
                return true;
            }
        }
        for (String part : posix.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.startsWith("._") || ".DS_Store".equals(part)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------
    // GENERIC HTTP HELPER
    // --------------------------------------------------
    private JsonNode sendRequest(String method, String urlStr, String jsonBody) throws IOException {


        HttpRequest.BodyPublisher bodyPublisher = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);

        // Use classic PAT: Authorization: token <token>
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlStr))
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .method(method, bodyPublisher)
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + method + " " + urlStr, e);
        }

        int responseCode = response.statusCode();
        String responseBody = response.body();
        if (responseCode >= 300) {
            throw new RuntimeException("GitHub API error (" + responseCode + ") at " + urlStr + ": " + responseBody);
        }

        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(responseBody);
    }

    private void validateConfig() {
        if (githubToken == null || githubToken.isEmpty())
            throw new IllegalStateException("GITHUB_TOKEN missing");
        if (githubOrg == null || githubOrg.isEmpty())
            throw new IllegalStateException("GITHUB_ORG missing");
    }

    // --------------------------------------------------
    // ZIP UTIL (Using Apache Commons for better compatibility)
    // --------------------------------------------------
    private Path unzip(MultipartFile zipFile) throws IOException {
        Path extractPath = Files.createTempDirectory("upload-extract-");

        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(zipFile.getInputStream())) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextZipEntry()) != null) {
                if (shouldSkipPath(entry.getName())) {
                    continue;
                }
                Path resolvedPath = extractPath.resolve(entry.getName()).normalize();
                if (!resolvedPath.startsWith(extractPath)) {
                    throw new IOException("Zip Slip security violation: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return extractPath;
    }

    // --------------------------------------------------
    // GENERATE AND DEPLOY K8S MANIFESTS
    // --------------------------------------------------
    private void generateAndDeployManifests(String repoName, Path tempDir, Integer nodePort) throws Exception {
        if (repoName.endsWith("-backend")) {
            DataStackRequirements stack = detectDataStack(tempDir);
            String k8Name = toK8sName(repoName, 63);
            String manifest = generateBackendK8Manifest(repoName, nodePort, stack, k8Name);
            updatePaxoRepo(repoName + "-deployment.yaml", manifest);
            if (stack.needsPostgres()) {
                updatePaxoRepo(repoName + "-postgres.yaml", generatePostgresManifest(k8Name, stack));
            }
            if (stack.needsRedis()) {
                updatePaxoRepo(repoName + "-redis.yaml", generateRedisManifest(k8Name));
            }
                        updatePaxoRepo("generated-apps/" + repoName + "-app.yaml", generateArgoApplicationManifest(repoName));
        } else if (repoName.endsWith("-frontend")) {
            String manifest = generateFrontendManifest(repoName, nodePort);
            updatePaxoRepo(repoName + "-deployment.yaml", manifest);
                        updatePaxoRepo("generated-apps/" + repoName + "-app.yaml", generateArgoApplicationManifest(repoName));
        }
    }

        private String generateArgoApplicationManifest(String repoName) {
                String appName = toK8sName(repoName + "-app", 63);
                return """
                                apiVersion: argoproj.io/v1alpha1
                                kind: Application
                                metadata:
                                    name: %s
                                    namespace: argocd
                                spec:
                                    project: default
                                    source:
                                        repoURL: https://github.com/%s/%s.git
                                        targetRevision: main
                                        path: k8
                                    destination:
                                        server: https://kubernetes.default.svc
                                        namespace: default
                                    syncPolicy:
                                        automated:
                                            prune: true
                                            selfHeal: true
                                        syncOptions:
                                            - CreateNamespace=true
                                """.formatted(appName, githubOrg, repoName);
        }

    private String generateBackendK8Manifest(
            String repoName,
            Integer nodePort,
            DataStackRequirements stack,
            String k8Name
    ) {
        String imageRepo = generateImageRepository(repoName);
        String imageUpdaterAlias = "app";
        String nodePortLine = nodePort != null ? "      nodePort: " + nodePort + "\n" : "";
        String serviceType = nodePort != null ? "NodePort" : "ClusterIP";
        String postgresHost = k8Name + "-postgres";
        String redisHost = k8Name + "-redis";

        StringBuilder envBlock = new StringBuilder();
        envBlock.append("            - name: SERVER_PORT\n");
        envBlock.append("              value: \"8080\"\n");
        if (stack.needsPostgres()) {
            envBlock.append("            - name: DB_URL\n");
            envBlock.append("              value: jdbc:postgresql://").append(postgresHost).append(":5432/")
                    .append(stack.databaseName()).append("\n");
            envBlock.append("            - name: DB_USERNAME\n");
            envBlock.append("              value: ").append(stack.databaseUser()).append("\n");
            envBlock.append("            - name: DB_PASSWORD\n");
            envBlock.append("              value: ").append(stack.databasePassword()).append("\n");
        }
        if (stack.needsRedis()) {
            envBlock.append("            - name: REDIS_HOST\n");
            envBlock.append("              value: ").append(redisHost).append("\n");
            envBlock.append("            - name: REDIS_PORT\n");
            envBlock.append("              value: \"6379\"\n");
        }

        // Use explicit lines (not text blocks) so incidental-indent stripping cannot break pod spec YAML.
        String initContainers = "";
        if (stack.needsPostgres()) {
            initContainers = "      initContainers:\n" +
                    "        - name: wait-for-postgres\n" +
                    "          image: postgres:16-alpine\n" +
                    "          command:\n" +
                    "            - sh\n" +
                    "            - -c\n" +
                    "            - until pg_isready -h " + postgresHost + " -p 5432 -U " + stack.databaseUser()
                    + "; do echo waiting for postgres; sleep 2; done\n";
        }

        return "apiVersion: apps/v1\n" +
               "kind: Deployment\n" +
               "metadata:\n" +
               "  name: " + k8Name + "\n" +
               "  annotations:\n" +
             "    argocd-image-updater.argoproj.io/image-list: \"" + imageUpdaterAlias + "=" + imageRepo + "\"\n" +
             "    argocd-image-updater.argoproj.io/" + imageUpdaterAlias + ".update-strategy: latest\n" +
               "spec:\n" +
               "  replicas: 1\n" +
               "  selector:\n" +
               "    matchLabels:\n" +
               "      app: " + k8Name + "\n" +
               "  template:\n" +
               "    metadata:\n" +
               "      labels:\n" +
               "        app: " + k8Name + "\n" +
               "    spec:\n" +
               initContainers +
               "      imagePullSecrets:\n" +
               "        - name: dockerhub-secret\n" +
               "      containers:\n" +
               "        - name: " + k8Name + "\n" +
               "          image: \"" + imageRepo + ":latest\"\n" +
               "          imagePullPolicy: Always\n" +
               "          ports:\n" +
               "            - containerPort: 8080\n" +
               "          env:\n" +
               envBlock +
               "---\n" +
               "apiVersion: v1\n" +
               "kind: Service\n" +
               "metadata:\n" +
             "  name: " + k8Name + "\n" +
               "spec:\n" +
               "  selector:\n" +
             "    app: " + k8Name + "\n" +
               "  ports:\n" +
               "    - port: 8080\n" +
               "      targetPort: 8080\n" +
               nodePortLine +
               "  type: " + serviceType + "\n";
    }

    private String generatePostgresManifest(String k8Name, DataStackRequirements stack) {
        String postgresName = k8Name + "-postgres";
        return """
                apiVersion: v1
                kind: PersistentVolumeClaim
                metadata:
                  name: %s-data
                spec:
                  accessModes:
                    - ReadWriteOnce
                  resources:
                    requests:
                      storage: 2Gi
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                spec:
                  replicas: 1
                  selector:
                    matchLabels:
                      app: %s
                  template:
                    metadata:
                      labels:
                        app: %s
                    spec:
                      containers:
                        - name: postgres
                          image: postgres:16-alpine
                          ports:
                            - containerPort: 5432
                          env:
                            - name: POSTGRES_DB
                              value: %s
                            - name: POSTGRES_USER
                              value: %s
                            - name: POSTGRES_PASSWORD
                              value: %s
                          volumeMounts:
                            - name: data
                              mountPath: /var/lib/postgresql/data
                      volumes:
                        - name: data
                          persistentVolumeClaim:
                            claimName: %s-data
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                spec:
                  selector:
                    app: %s
                  ports:
                    - port: 5432
                      targetPort: 5432
                """.formatted(
                postgresName,
                postgresName,
                postgresName,
                postgresName,
                stack.databaseName(),
                stack.databaseUser(),
                stack.databasePassword(),
                postgresName,
                postgresName,
                postgresName
        );
    }

    private String generateRedisManifest(String k8Name) {
        String redisName = k8Name + "-redis";
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                spec:
                  replicas: 1
                  selector:
                    matchLabels:
                      app: %s
                  template:
                    metadata:
                      labels:
                        app: %s
                    spec:
                      containers:
                        - name: redis
                          image: redis:7-alpine
                          ports:
                            - containerPort: 6379
                          command: ["redis-server"]
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                spec:
                  selector:
                    app: %s
                  ports:
                    - port: 6379
                      targetPort: 6379
                """.formatted(redisName, redisName, redisName, redisName, redisName);
    }

    private DataStackRequirements detectDataStack(Path root) {
        boolean needsPostgres = false;
        boolean needsRedis = false;
        String dbName = "app";
        String dbUser = "app_user";
        String dbPassword = "app_pass";

        try (var paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.equals("application.yml") && !name.equals("application.yaml")
                        && !name.equals("docker-compose.yml") && !name.equals("docker-compose.yaml")) {
                    continue;
                }
                String content = Files.readString(file).toLowerCase(Locale.ROOT);
                if (content.contains("postgresql") || content.contains("jdbc:postgresql")) {
                    needsPostgres = true;
                }
                if (content.contains("redis") && (content.contains("spring.data.redis") || content.contains("redis:"))) {
                    needsRedis = true;
                }
                if (name.startsWith("docker-compose")) {
                    dbName = firstMatchGroup(content, "postgres_db:\\s*([a-z0-9_-]+)", dbName);
                    dbUser = firstMatchGroup(content, "postgres_user:\\s*([a-z0-9_-]+)", dbUser);
                    dbPassword = firstMatchGroup(content, "postgres_password:\\s*([a-z0-9_-]+)", dbPassword);
                    if (content.contains("image: postgres") || content.contains("postgres:")) {
                        needsPostgres = true;
                    }
                    if (content.contains("image: redis") || content.contains("\n  redis:")) {
                        needsRedis = true;
                    }
                }
                if (name.startsWith("application")) {
                    dbName = firstMatchGroup(content, "jdbc:postgresql://[^/]+/([a-z0-9_-]+)", dbName);
                    dbUser = firstMatchGroup(content, "db_username:\\s*\\$?\\{?db_username:([a-z0-9_-]+)", dbUser);
                    dbUser = firstMatchGroup(content, "username:\\s*\\$?\\{?db_username:([a-z0-9_-]+)", dbUser);
                    dbPassword = firstMatchGroup(content, "db_password:\\s*\\$?\\{?db_password:([a-z0-9_-]+)", dbPassword);
                    dbPassword = firstMatchGroup(content, "password:\\s*\\$?\\{?db_password:([a-z0-9_-]+)", dbPassword);
                }
            }
        } catch (IOException ex) {
            log.warn("Could not scan backend for data stack requirements: {}", ex.getMessage());
        }

        if (!needsPostgres && !needsRedis) {
            return DataStackRequirements.none();
        }
        return new DataStackRequirements(needsPostgres, needsRedis, dbName, dbUser, dbPassword);
    }

    private String firstMatchGroup(String content, String regex, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return fallback;
    }

    private String generateFrontendManifest(String repoName, Integer nodePort) {
         String k8Name = toK8sName(repoName, 63);
                        String imageRepo = generateImageRepository(repoName);
                        String imageUpdaterAlias = "app";
        String nodePortLine = nodePort != null ? "      nodePort: " + nodePort + "\n" : "";
        String serviceType = nodePort != null ? "NodePort" : "ClusterIP";
        return "apiVersion: apps/v1\n" +
               "kind: Deployment\n" +
               "metadata:\n" +
             "  name: " + k8Name + "\n" +
               "  annotations:\n" +
                             "    argocd-image-updater.argoproj.io/image-list: \"" + imageUpdaterAlias + "=" + imageRepo + "\"\n" +
                             "    argocd-image-updater.argoproj.io/" + imageUpdaterAlias + ".update-strategy: latest\n" +
               "spec:\n" +
               "  replicas: 2\n" +
               "  selector:\n" +
               "    matchLabels:\n" +
             "      app: " + k8Name + "\n" +
               "  template:\n" +
               "    metadata:\n" +
               "      labels:\n" +
             "        app: " + k8Name + "\n" +
               "    spec:\n" +
                             "      imagePullSecrets:\n" +
                             "        - name: dockerhub-secret\n" +
               "      containers:\n" +
             "        - name: " + k8Name + "\n" +
                             "          image: \"" + imageRepo + ":latest\"\n" +
               "          imagePullPolicy: Always\n" +
               "          ports:\n" +
               "            - containerPort: 80\n" +
               "---\n" +
               "apiVersion: v1\n" +
               "kind: Service\n" +
               "metadata:\n" +
               "  name: " + k8Name + "\n" +
               "spec:\n" +
               "  selector:\n" +
               "    app: " + k8Name + "\n" +
               "  ports:\n" +
               "    - port: 80\n" +
               "      targetPort: 80\n" +
               nodePortLine +
               "  type: " + serviceType + "\n";
    }

    private synchronized ProductUrlMapping allocateAndSaveProductUrls(String realmName, String productId) {
        if (realmName == null || realmName.isBlank()) {
            throw new IllegalArgumentException("Realm name cannot be blank");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("Product ID cannot be blank");
        }

        String normalizedRealm = realmName.trim().toLowerCase(Locale.ROOT);
        String normalizedProductId = productId.trim().toLowerCase(Locale.ROOT);

        Optional<ProductUrlMapping> existing =
                productUrlMappingRepository.findByRealmNameIgnoreCaseAndProductIdIgnoreCase(
                        normalizedRealm, normalizedProductId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Set<Integer> usedPorts = new HashSet<>(RESERVED_NODE_PORTS);
        usedPorts.addAll(productUrlMappingRepository.findFrontendNodePorts());
        usedPorts.addAll(productUrlMappingRepository.findBackendNodePorts());

        int frontendNodePort = nextAvailablePort(frontendNodePortStart, frontendNodePortEnd, usedPorts);
        usedPorts.add(frontendNodePort);
        int backendNodePort = nextAvailablePort(backendNodePortStart, backendNodePortEnd, usedPorts);

        ProductUrlMapping mapping = ProductUrlMapping.builder()
                .realmName(normalizedRealm)
                .productId(normalizedProductId)
                .frontendNodePort(frontendNodePort)
                .backendNodePort(backendNodePort)
                .frontendBaseUrl(buildExternalUrl(frontendNodePort))
                .backendBaseUrl(buildExternalUrl(backendNodePort))
                .build();

        return productUrlMappingRepository.save(mapping);
    }

    private int nextAvailablePort(int start, int end, Set<Integer> usedPorts) {
        if (start < 30000 || end > 32767 || start > end) {
            throw new IllegalStateException("Invalid Kubernetes NodePort range: " + start + "-" + end);
        }
        for (int port = start; port <= end; port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }
        throw new IllegalStateException("No available NodePort in configured range: " + start + "-" + end);
    }

    private String buildExternalUrl(int nodePort) {
        String scheme = (externalUrlScheme == null || externalUrlScheme.isBlank()) ? "http" : externalUrlScheme.trim();
        String host = (externalHost == null || externalHost.isBlank()) ? "192.168.49.2" : externalHost.trim();
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        return scheme + "://" + host + ":" + nodePort;
    }

    private void rewriteFrontendBackendUpstream(Path root, String backendServiceName) throws IOException {
        List<String> candidateNames = List.of(
                "nginx.conf",
                "default.conf",
                "default.template",
                "environment.ts",
                "environment.prod.ts",
                "proxy.conf.json",
                "proxy.conf.js"
        );

        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> candidateNames.contains(path.getFileName().toString()))
                    .forEach(path -> replaceBackendServiceReference(path, backendServiceName));
        }
    }

    private void replaceBackendServiceReference(Path path, String backendServiceName) {
        try {
            String content = Files.readString(path);
            String updated = rewriteBackendUpstreamInContent(content, backendServiceName);
            if (!content.equals(updated)) {
                Files.writeString(path, updated);
                log.info("Updated frontend backend upstream in {} to {}", path, backendServiceName);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to update frontend backend upstream in " + path, ex);
        }
    }

    /**
     * Rewrites docker-compose hostname {@code backend} to the Kubernetes Service name.
     * Only replaces the full {@code http://backend:port} form so service names ending in
     * {@code -backend} are not corrupted (e.g. {@code yatrify-admin-x-backend:8080}).
     */
    String rewriteBackendUpstreamInContent(String content, String backendServiceName) {
        if (content == null || content.isBlank() || backendServiceName == null || backendServiceName.isBlank()) {
            return content;
        }
        if (content.contains(backendServiceName)) {
            return content;
        }

        String upstream = "http://" + backendServiceName + ":8080";
        return content
                .replaceAll("(?i)http://backend:8081", upstream)
                .replaceAll("(?i)http://backend:8080", upstream)
                .replaceAll("(?i)(proxy_pass\\s+)http://backend(\\s*;)", "$1" + upstream + "$2");
    }

    private String toK8sName(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return "app";
        }

        String sanitized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        if (sanitized.isBlank()) {
            sanitized = "app";
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "a-" + sanitized;
        }
        if (sanitized.length() > maxLen) {
            sanitized = sanitized.substring(0, maxLen).replaceAll("-+$", "");
        }
        return sanitized;
    }

    private String generateImageRepository(String repoName) {
        // Docker image repos cannot include underscores; normalize to kebab-case.
        String normalizedRepo = repoName.toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return "devopspaxarisglobalrepo/" + normalizedRepo;
    }

    private void updatePaxoRepo(String fileName, String content) throws Exception {
        String repoPath = "k8/" + fileName;
        String existingSha = null;
        String contentsUrl = githubApiBaseUrl + "/repos/" + paxoOrg + "/" + paxoRepo + "/contents/" + repoPath;

        try {
            JsonNode existingNode = sendRequest("GET", contentsUrl, null);
            existingSha = existingNode.path("sha").asText(null);
        } catch (RuntimeException ex) {
            if (!ex.getMessage().contains("(404)")) {
                throw ex;
            }
        }

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("message", (existingSha == null ? "Add" : "Update") + " deployment for " + fileName);
        updateBody.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        updateBody.put("branch", "main");
        if (existingSha != null && !existingSha.isBlank()) {
            updateBody.put("sha", existingSha);
        }

        sendRequest("PUT", contentsUrl, objectMapper.writeValueAsString(updateBody));
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            }
        }
    }
}

