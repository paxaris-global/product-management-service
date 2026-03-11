package com.paxaris.product_management_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Helper utility for Identity Service to properly send multipart files to Product Manager.
 *
 * Usage in Identity Service:
 * <pre>
 * ProvisioningServiceHelper helper = new ProvisioningServiceHelper(
 *     new RestTemplate(),
 *     "http://product-manager:8088"
 * );
 *
 * Map<String, String> response = helper.provisionRepository(repoName, zipFile);
 * </pre>
 */
@Slf4j
public class ProvisioningServiceHelper {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ProvisioningServiceHelper(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * Send a MultipartFile to the Product Manager provisioning endpoint.
     *
     * @param repoName Name of the repository to create
     * @param zipFile  Multipart file containing the project ZIP
     * @return Response from the provisioning endpoint
     */
    public Map<String, String> provisionRepository(String repoName, MultipartFile zipFile) {
        try {
            if (zipFile == null || zipFile.isEmpty()) {
                throw new IllegalArgumentException("ZIP file is empty or null");
            }

            log.info("Preparing to provision repository: {} with file: {}", repoName, zipFile.getOriginalFilename());

            // Create multipart body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("repoName", repoName);
            body.add("zipFile", new MultipartFileResource(zipFile));

            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // Build URL
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/v1/project/provision/upload")
                    .toUriString();

            log.info("Sending POST request to: {}", url);

            // Send request
            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(url, requestEntity, Map.class);

            log.info("✅ Repository provisioning successful for: {}", repoName);
            return response;

        } catch (Exception e) {
            log.error("❌ Failed to provision repository: {}", repoName, e);
            throw new RuntimeException("Provisioning failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send a file (from file path) to the Product Manager provisioning endpoint.
     *
     * @param repoName Name of the repository to create
     * @param zipFilePath Path to the ZIP file
     * @return Response from the provisioning endpoint
     */
    public Map<String, String> provisionRepositoryFromPath(String repoName, Path zipFilePath) {
        try {
            File file = zipFilePath.toFile();
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist: " + zipFilePath);
            }

            log.info("Preparing to provision repository: {} with file: {}", repoName, zipFilePath);

            // Create multipart body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("repoName", repoName);
            body.add("zipFile", new FileSystemResource(file));

            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // Build URL
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/v1/project/provision/upload")
                    .toUriString();

            log.info("Sending POST request to: {}", url);

            // Send request
            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(url, requestEntity, Map.class);

            log.info("✅ Repository provisioning successful for: {}", repoName);
            return response;

        } catch (Exception e) {
            log.error("❌ Failed to provision repository: {}", repoName, e);
            throw new RuntimeException("Provisioning failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wrapper class to make MultipartFile compatible with RestTemplate's multipart handling
     */
    private static class MultipartFileResource extends FileSystemResource {

        private final MultipartFile multipartFile;

        public MultipartFileResource(MultipartFile multipartFile) throws IOException {
            super(Files.createTempFile("upload-", ".zip").toFile());
            this.multipartFile = multipartFile;
            // Write the multipart file to the temporary file
            Files.write(this.getFile().toPath(), multipartFile.getBytes());
        }

        @Override
        public String getFilename() {
            return multipartFile.getOriginalFilename();
        }
    }
}

