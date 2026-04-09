package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.*;

@Slf4j
@Service
public class ProvisioningService {

    private static final int TREE_BATCH_SIZE = 200;
    private static final long MAX_GITHUB_BLOB_BYTES = 50L * 1024 * 1024;
    private static final int REPO_READY_MAX_RETRIES = 20;
    private static final long REPO_READY_RETRY_DELAY_MS = 1500;
    private static final List<String> SKIPPED_PATH_SEGMENTS = List.of(
            "/.git/", "/node_modules/", "/target/", "/build/", "/dist/", "/out/", "/.idea/", "/.vscode/"
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

    public ProvisioningService(
            @Value("${github.token}") String githubToken,
            @Value("${github.org}") String githubOrg,
            @Value("${github.api.base-url}") String githubApiBaseUrl,
            @Value("${provisioning.default-admin-username}") String defaultAdminUsername,
            @Value("${paxo.org:paxaris-global}") String paxoOrg,
            @Value("${paxo.repo:paxo}") String paxoRepo,
            @Value("${docker.hub.username:}") String dockerHubUsername,
            @Value("${docker.hub.token:}") String dockerHubToken) {
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
        createRepo(repoName);
        waitForRepositoryReady(repoName);
        Path tempDir = unzip(zipFile);
                    setRepoGithubActionsSecrets(repoName);
                ensureRepositoryTemplates(repoName, tempDir);
        uploadDirectoryToGitHub(tempDir, repoName);
        generateAndDeployManifests(repoName, tempDir);
        return tempDir;
    }

    private void waitForRepositoryReady(String repoName) throws IOException {
        String repoUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= REPO_READY_MAX_RETRIES; attempt++) {
            try {
                JsonNode repoNode = sendRequest("GET", repoUrl, null);
                String fullName = repoNode.path("full_name").asText();
                if (fullName != null && !fullName.isBlank()) {
                    log.info("Repository is ready on GitHub: {}", fullName);
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

        private void ensureRepositoryTemplates(String repoName, Path tempDir) throws IOException {
                boolean isBackend = repoName.endsWith("-backend");
                int containerPort = isBackend ? 8080 : 80;

                Path dockerfilePath = tempDir.resolve("Dockerfile");
                if (Files.notExists(dockerfilePath)) {
                        String dockerfile = isBackend ? generateBackendDockerfile() : generateFrontendDockerfile();
                        Files.writeString(dockerfilePath, dockerfile);
                        log.info("Generated default Dockerfile for {}", repoName);
                }

                Path k8ManifestPath = tempDir.resolve("k8").resolve("deployment.yaml");
                if (Files.notExists(k8ManifestPath)) {
                        Files.createDirectories(k8ManifestPath.getParent());
                        Files.writeString(k8ManifestPath, generateRepositoryK8Manifest(repoName, containerPort));
                        log.info("Generated default Kubernetes manifest for {}", repoName);
                }

                Path workflowPath = tempDir.resolve(".github").resolve("workflows").resolve("gitops-deploy.yml");
                if (Files.notExists(workflowPath)) {
                        Files.createDirectories(workflowPath.getParent());
                        Files.writeString(workflowPath, generateRepositoryWorkflow());
                        log.info("Generated default CI workflow for {}", repoName);
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

        private String generateRepositoryK8Manifest(String repoName, int containerPort) {
            String k8Name = toK8sName(repoName, 63);
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
                                                - name: %s
                                                    image: devopspaxarisglobalrepo/%s:latest
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
                                    type: ClusterIP
                                """.formatted(k8Name, k8Name, k8Name, k8Name, repoName, containerPort, k8Name, k8Name, containerPort, containerPort);
        }

        private String generateRepositoryWorkflow() {
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
                                                    REPO_NAME="${GITHUB_REPOSITORY#*/}"
                                                    IMAGE_REPO="${{ secrets.DOCKERHUB_USERNAME }}/$REPO_NAME"
                                                    IMAGE_TAG="${GITHUB_SHA}"
                                                    echo "image_repo=$IMAGE_REPO" >> "$GITHUB_OUTPUT"
                                                    echo "image_tag=$IMAGE_TAG" >> "$GITHUB_OUTPUT"

                                            - name: Login to Docker Hub
                                                uses: docker/login-action@v3
                                                with:
                                                    username: ${{ secrets.DOCKERHUB_USERNAME }}
                                                    password: ${{ secrets.DOCKERHUB_TOKEN }}

                                            - name: Set up Docker Buildx
                                                uses: docker/setup-buildx-action@v3

                                            - name: Build and push image
                                                uses: docker/build-push-action@v6
                                                with:
                                                    context: .
                                                    file: ./Dockerfile
                                                    push: true
                                                    tags: |
                                                        ${{ steps.vars.outputs.image_repo }}:latest
                                                        ${{ steps.vars.outputs.image_repo }}:${{ steps.vars.outputs.image_tag }}

                                            - name: Update k8 image tag
                                                run: |
                                                    sed -i.bak "s|^[[:space:]]*image:[[:space:]].*|          image: ${{ steps.vars.outputs.image_repo }}:${{ steps.vars.outputs.image_tag }}|" k8/deployment.yaml
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
                                """;
        }

    public String generateRepositoryName(String realmName, String adminUsername, String productName) {
        String adminPart = adminUsername != null ? adminUsername : defaultAdminUsername;
        return String.format("%s-%s-%s", realmName, adminPart, productName).toLowerCase();
    }

    // --------------------------------------------------
    // CREATE GITHUB REPO
    // --------------------------------------------------
        // --------------------------------------------------
        // SET GITHUB ACTIONS SECRETS (DockerHub)
        // --------------------------------------------------
        private void setRepoGithubActionsSecrets(String repoName) {
            if (dockerHubUsername == null || dockerHubUsername.isBlank()
                    || dockerHubToken == null || dockerHubToken.isBlank()) {
                log.warn("Docker Hub credentials not configured. Skipping GitHub Actions secret injection for {}", repoName);
                return;
            }
            try {
                String pubKeyUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName + "/actions/secrets/public-key";
                JsonNode pubKeyNode = sendRequest("GET", pubKeyUrl, null);
                String keyId = pubKeyNode.get("key_id").asText();
                String publicKeyB64 = pubKeyNode.get("key").asText();
                byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyB64);

                LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
                Map<String, String> secrets = new LinkedHashMap<>();
                secrets.put("DOCKERHUB_USERNAME", dockerHubUsername);
                secrets.put("DOCKERHUB_TOKEN", dockerHubToken);
                secrets.put("GH_ACCESS_TOKEN", githubToken);
                for (Map.Entry<String, String> entry : secrets.entrySet()) {
                    byte[] messageBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    byte[] sealed = new byte[Box.SEALBYTES + messageBytes.length];
                    lazySodium.cryptoBoxSeal(sealed, messageBytes, messageBytes.length, publicKeyBytes);
                    String encryptedValue = Base64.getEncoder().encodeToString(sealed);
                    String secretUrl = githubApiBaseUrl + "/repos/" + githubOrg + "/" + repoName
                            + "/actions/secrets/" + entry.getKey();
                    String body = "{\"encrypted_value\":\"" + encryptedValue + "\",\"key_id\":\"" + keyId + "\"}";
                    sendRequest("PUT", secretUrl, body);
                    log.info("Set GitHub Actions secret {} for repo {}", entry.getKey(), repoName);
                }
            } catch (Exception e) {
                log.error("Failed to set GitHub Actions secrets for {}: {}", repoName, e.getMessage());
            }
        }

        // --------------------------------------------------
        // CREATE GITHUB REPO
        // --------------------------------------------------
    public void createRepo(String repoName) throws IOException {
        validateConfig();

        String apiUrl = githubApiBaseUrl + "/orgs/" + githubOrg + "/repos";

        // auto_init: true is required to create the 'main' branch so we can update it
        // later
        String body = """
                {
                  "name": "%s",
                  "private": true,
                  "auto_init": true
                }
                """.formatted(repoName);

        sendRequest("POST", apiUrl, body);
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
                    try {
                        String path = root.relativize(file).toString().replace("\\", "/");

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
            log.info("Skipped {} generated/oversized files before GitHub upload", skippedFiles.size());
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

        log.info("Successfully pushed {} files to {}/{} in {} commit batches. Head commit: {}",
                fileRefs.size(), githubOrg, repo, totalBatches, currentCommitSha);
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
        String normalized = "/" + relativePath.toLowerCase(Locale.ROOT) + "/";
        for (String segment : SKIPPED_PATH_SEGMENTS) {
            if (normalized.contains(segment)) {
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
    private void generateAndDeployManifests(String repoName, Path tempDir) throws Exception {
        if (repoName.endsWith("-backend")) {
            String manifest = generateBackendManifest(repoName);
            updatePaxoRepo(repoName + "-deployment.yaml", manifest);
                        updatePaxoRepo("generated-apps/" + repoName + "-app.yaml", generateArgoApplicationManifest(repoName));
        } else if (repoName.endsWith("-frontend")) {
            String manifest = generateFrontendManifest(repoName);
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

    private String generateBackendManifest(String repoName) {
        String k8Name = toK8sName(repoName, 63);
        return "apiVersion: apps/v1\n" +
               "kind: Deployment\n" +
               "metadata:\n" +
               "  name: " + k8Name + "\n" +
               "  annotations:\n" +
               "    argocd-image-updater.argoproj.io/image-list: devopspaxarisglobalrepo/" + repoName + "\n" +
               "    argocd-image-updater.argoproj.io/" + repoName + ".update-strategy: latest\n" +
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
               "      containers:\n" +
               "        - name: " + k8Name + "\n" +
               "          image: devopspaxarisglobalrepo/" + repoName + ":latest\n" +
               "          imagePullPolicy: Always\n" +
               "          ports:\n" +
               "            - containerPort: 8080\n" +
               "          env:\n" +
               "            - name: SPRING_PROFILES_ACTIVE\n" +
               "              value: prod\n" +
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
               "  type: ClusterIP\n";
    }

    private String generateFrontendManifest(String repoName) {
         String k8Name = toK8sName(repoName, 63);
        return "apiVersion: apps/v1\n" +
               "kind: Deployment\n" +
               "metadata:\n" +
             "  name: " + k8Name + "\n" +
               "  annotations:\n" +
               "    argocd-image-updater.argoproj.io/image-list: devopspaxarisglobalrepo/" + repoName + "\n" +
               "    argocd-image-updater.argoproj.io/" + repoName + ".update-strategy: latest\n" +
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
               "      containers:\n" +
             "        - name: " + k8Name + "\n" +
               "          image: devopspaxarisglobalrepo/" + repoName + ":latest\n" +
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
               "  type: ClusterIP\n";
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
        log.info("Updated {}/{} path {} via GitHub Contents API", paxoOrg, paxoRepo, repoPath);
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

