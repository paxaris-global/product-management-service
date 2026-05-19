package com.paxaris.product_management_service.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductShowcaseCaptureServiceTest {

    private final ProductShowcaseCaptureService captureService = new ProductShowcaseCaptureService();

    @Test
    void extractDescription_prefersMetaDescription() {
        String html = """
                <html>
                  <head>
                    <meta name="description" content="Plan trips, book stays, and manage your travel profile in one place."/>
                    <title>Yatrify</title>
                  </head>
                  <body><h1>Welcome</h1></body>
                </html>
                """;

        String description = captureService.extractDescription(
                Jsoup.parse(html),
                "Yatrify",
                "testRealm"
        );

        assertTrue(description.contains("Plan trips"));
    }

    @Test
    void buildPlaceholderSvg_returnsDataUri() {
        String preview = captureService.buildPlaceholderSvg("Yatrify", "testRealm");
        assertTrue(preview.startsWith("data:image/svg+xml;base64,"));
    }

    @Test
    void extractDescription_prefersHeroSubtitle() {
        String html = """
                <html><body>
                  <section class="hero">
                    <h1 class="hero__title">Discover India</h1>
                    <p class="hero__subtitle">Curated trips, verified organizers, and seamless bookings for every kind of traveler.</p>
                  </section>
                </body></html>
                """;

        String description = captureService.extractDescription(
                Jsoup.parse(html),
                "Yatrify",
                "testRealm"
        );

        assertTrue(description.contains("Curated trips"));
    }
}
