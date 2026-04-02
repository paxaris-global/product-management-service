package com.paxaris.product_management_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
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

@Slf4j
@Service
public class ProvisioningService {

    private static final int TREE_BATCH_SIZE = 200;
    private static final long MAX_GITHUB_BLOB_BYTES = 50L * 1024 * 1024;
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

    public ProvisioningService(
            @Value("${github.token}") String githubToken,
            @Value("${github.org}") String githubOrg,
            @Value("${github.api.base-url}") String githubApiBaseUrl,
            @Value("${provisioning.default-admin-username}") String defaultAdminUsername,
            @Value("${paxo.org:paxaris-global}") String paxoOrg,
            @Value("${paxo.repo:paxo}") String paxoRepo) {
        this.githubToken = githubToken;
        this.githubOrg = githubOrg;
        this.githubApiBaseUrl = githubApiBaseUrl;
        this.defaultAdminUsername = defaultAdminUsername;
        this.paxoOrg = paxoOrg;
        this.paxoRepo = paxoRepo;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
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
        Path tempDir = unzip(zipFile);
        uploadDirectoryToGitHub(tempDir, repoName);
        generateAndDeployManifests(repoName, tempDir);
        return tempDir;
    }

    public String generateRepositoryName(String realmName, String adminUsername, String productName) {
        String adminPart = adminUsername != null ? adminUsername : defaultAdminUsername;
        return String.format("%s-%s-%s", realmName, adminPart, productName).toLowerCase();
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
        } else if (repoName.endsWith("-frontend")) {
            String manifest = generateFrontendManifest(repoName);
            updatePaxoRepo(repoName + "-deployment.yaml", manifest);
        }
    }

    private String generateBackendManifest(String repoName) {
        return """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: """ + repoName + """
  annotations:
    argocd-image-updater.argoproj.io/image-list: devopspaxarisglobalrepo/""" + repoName + """
    argocd-image-updater.argoproj.io/""" + repoName + """.update-strategy: latest
spec:
  replicas: 2
  selector:
    matchLabels:
      app: """ + repoName + """
  template:
    metadata:
      labels:
        app: """ + repoName + """
    spec:
      containers:
        - name: """ + repoName + """
          image: devopspaxarisglobalrepo/""" + repoName + """:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: prod
---
apiVersion: v1
kind: Service
metadata:
  name: """ + repoName + """
spec:
  selector:
    app: """ + repoName + """
  ports:
    - port: 8080
      targetPort: 8080
  type: ClusterIP
""";
    }

    private String generateFrontendManifest(String repoName) {
        return """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: """ + repoName + """-frontend
  annotations:
    argocd-image-updater.argoproj.io/image-list: devopspaxarisglobalrepo/""" + repoName + """
    argocd-image-updater.argoproj.io/""" + repoName + """.update-strategy: latest
spec:
  replicas: 2
  selector:
    matchLabels:
      app: """ + repoName + """-frontend
  template:
    metadata:
      labels:
        app: """ + repoName + """-frontend
    spec:
      containers:
        - name: """ + repoName + """-frontend
          image: devopspaxarisglobalrepo/""" + repoName + """:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: """ + repoName + """-frontend
spec:
  selector:
    app: """ + repoName + """-frontend
  ports:
    - port: 80
      targetPort: 80
  type: ClusterIP
""";
    }

    private void updatePaxoRepo(String fileName, String content) throws Exception {
        Path tempPaxoDir = Files.createTempDirectory("paxo-clone-");
        try {
            // Clone paxo repo
            ProcessBuilder clonePb = new ProcessBuilder("git", "clone", "https://" + githubToken + "@github.com/" + paxoOrg + "/" + paxoRepo + ".git", tempPaxoDir.toString());
            clonePb.redirectErrorStream(true);
            Process cloneProcess = clonePb.start();
            int cloneExit = cloneProcess.waitFor();
            if (cloneExit != 0) {
                throw new RuntimeException("Failed to clone paxo repo");
            }

            // Write manifest
            Path manifestPath = tempPaxoDir.resolve("k8").resolve(fileName);
            Files.createDirectories(manifestPath.getParent());
            Files.writeString(manifestPath, content);

            // Git add, commit, push
            ProcessBuilder addPb = new ProcessBuilder("git", "add", "k8/" + fileName);
            addPb.directory(tempPaxoDir.toFile());
            Process addProcess = addPb.start();
            addProcess.waitFor();

            ProcessBuilder commitPb = new ProcessBuilder("git", "commit", "-m", "Add deployment for " + fileName);
            commitPb.directory(tempPaxoDir.toFile());
            Process commitProcess = commitPb.start();
            commitProcess.waitFor();

            ProcessBuilder pushPb = new ProcessBuilder("git", "push");
            pushPb.directory(tempPaxoDir.toFile());
            Process pushProcess = pushPb.start();
            int pushExit = pushProcess.waitFor();
            if (pushExit != 0) {
                throw new RuntimeException("Failed to push to paxo repo");
            }
        } finally {
            // Clean up
            deleteDirectory(tempPaxoDir);
        }
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

