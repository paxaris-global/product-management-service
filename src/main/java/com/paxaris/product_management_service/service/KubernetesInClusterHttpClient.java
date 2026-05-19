package com.paxaris.product_management_service.service;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;

/**
 * HTTP client that trusts the in-cluster Kubernetes API server CA
 * ({@code /var/run/secrets/kubernetes.io/serviceaccount/ca.crt}).
 */
@Slf4j
final class KubernetesInClusterHttpClient {

    private static final Path SERVICE_ACCOUNT_CA =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

    private KubernetesInClusterHttpClient() {}

    static HttpClient create() {
        try {
            if (!Files.isRegularFile(SERVICE_ACCOUNT_CA)) {
                log.debug("Kubernetes service-account CA not found; using default JVM trust store");
                return defaultClient();
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates;
            try (var in = Files.newInputStream(SERVICE_ACCOUNT_CA)) {
                certificates = factory.generateCertificates(in);
            }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            int index = 0;
            for (Certificate certificate : certificates) {
                keyStore.setCertificateEntry("k8s-ca-" + index++, certificate);
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to build Kubernetes in-cluster HTTP client: {}", ex.getMessage());
            return defaultClient();
        }
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
