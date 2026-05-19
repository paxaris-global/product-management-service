package com.paxaris.product_management_service.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductShowcaseCaptureService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int DESCRIPTION_MAX = 480;

    /** Fallback element selectors if viewport screenshot fails. */
    private static final List<String> DEFAULT_HOME_PAGE_SELECTORS = List.of(
            "main",
            "app-root",
            "#root",
            "[role='main']",
            ".home-page",
            ".home",
            "body"
    );

    private static final List<String> DEFAULT_HOME_PATHS = List.of("", "/", "/home", "/index");

    private static final List<String> DEFAULT_ABOUT_SELECTORS = List.of(
            "#about",
            "#about-us",
            "[id*='about']",
            "[class*='about-us']",
            "[class*='about-section']",
            "section.about",
            ".about",
            ".hero__subtitle",
            ".hero p",
            ".cta-section p",
            "main p"
    );

    private static final List<String> DEFAULT_PRODUCT_NAME_SELECTORS = List.of(
            ".hero__title",
            ".hero h1",
            "section.hero h1",
            "h1"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${showcase.capture.playwright-enabled:true}")
    private boolean playwrightEnabled;

    @Value("${showcase.capture.timeout-seconds:90}")
    private int captureTimeoutSeconds;

    @Value("${showcase.capture.viewport-width:1280}")
    private int viewportWidth;

    @Value("${showcase.capture.viewport-height:720}")
    private int viewportHeight;

    @Value("${showcase.capture.home-paths:}")
    private String homePathsProperty;

    @Value("${showcase.capture.home-page-selectors:}")
    private String homePageSelectorsProperty;

    @Value("${showcase.capture.about-selectors:}")
    private String aboutSelectorsProperty;

    @Value("${showcase.capture.product-name-selectors:}")
    private String productNameSelectorsProperty;

    @Value("${showcase.capture.about-paths:/about,/about-us}")
    private String aboutPathsProperty;

    public CaptureResult capture(String frontendUrl, String productName, String realmName) {
        String normalizedUrl = normalizeUrl(frontendUrl);
        if (playwrightEnabled) {
            try {
                return captureWithPlaywright(normalizedUrl, productName, realmName);
            } catch (Exception ex) {
                log.warn("Playwright showcase capture failed for {}: {}", normalizedUrl, ex.getMessage());
            }
        }

        return captureWithJsoupFallback(normalizedUrl, productName, realmName);
    }

    private CaptureResult captureWithPlaywright(String url, String fallbackProductName, String realmName) {
        List<String> homePageSelectors = resolveSelectors(homePageSelectorsProperty, DEFAULT_HOME_PAGE_SELECTORS);
        List<String> aboutSelectors = resolveSelectors(aboutSelectorsProperty, DEFAULT_ABOUT_SELECTORS);
        List<String> nameSelectors = resolveSelectors(productNameSelectorsProperty, DEFAULT_PRODUCT_NAME_SELECTORS);
        List<String> homePaths = resolvePaths(homePathsProperty);
        List<String> aboutPaths = resolvePaths(aboutPathsProperty);
        if (homePaths.isEmpty()) {
            homePaths = DEFAULT_HOME_PATHS;
        }

        String baseUrl = resolveBaseUrl(url);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            try (BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setViewportSize(viewportWidth, viewportHeight)
            )) {
                Page page = context.newPage();
                navigateToHomePage(page, baseUrl, homePaths);
                waitForHomePageContent(page);

                String resolvedName = firstVisibleText(page, nameSelectors, fallbackProductName);
                String description = extractDescriptionFromLivePage(page, aboutSelectors, resolvedName, realmName);
                String previewImage = screenshotHomePage(page, homePageSelectors, resolvedName, realmName);

                if (description.equals(defaultDescription(resolvedName, realmName))) {
                    description = tryAboutPages(page, baseUrl, aboutPaths, aboutSelectors, resolvedName, realmName, description);
                }

                return new CaptureResult(resolvedName, description, previewImage);
            } finally {
                browser.close();
            }
        }
    }

    private void navigateToHomePage(Page page, String baseUrl, List<String> homePaths) {
        Exception lastError = null;
        String root = baseUrl.replaceAll("/+$", "");

        for (String path : homePaths) {
            String target = buildUrl(root, path);
            try {
                page.navigate(
                        target,
                        new Page.NavigateOptions()
                                .setTimeout(captureTimeoutSeconds * 1000.0)
                                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                );
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.waitForTimeout(2000);
                log.info("Opened product home page for screenshot: {}", target);
                return;
            } catch (Exception ex) {
                lastError = ex;
                log.debug("Home path '{}' failed: {}", target, ex.getMessage());
            }
        }

        throw new IllegalStateException(
                "Could not open product home page at " + baseUrl,
                lastError
        );
    }

    private void waitForHomePageContent(Page page) {
        List<String> readySelectors = List.of(
                "app-root",
                "main",
                "h1",
                ".hero",
                "section.hero",
                "[role='main']"
        );
        for (String selector : readySelectors) {
            try {
                page.waitForSelector(
                        selector,
                        new Page.WaitForSelectorOptions().setTimeout(20_000)
                );
                return;
            } catch (Exception ex) {
                log.debug("Home ready selector '{}' not found: {}", selector, ex.getMessage());
            }
        }
        page.waitForTimeout(1500);
    }

    private static String buildUrl(String root, String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return root + "/";
        }
        return root + (path.startsWith("/") ? path : "/" + path);
    }

    private static String resolveBaseUrl(String url) {
        URI uri = URI.create(normalizeUrl(url));
        StringBuilder base = new StringBuilder();
        base.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            base.append(":").append(uri.getPort());
        }
        return base.toString();
    }

    private String tryAboutPages(
            Page page,
            String baseUrl,
            List<String> aboutPaths,
            List<String> aboutSelectors,
            String productName,
            String realmName,
            String currentDescription
    ) {
        String root = baseUrl.replaceAll("/+$", "");
        for (String path : aboutPaths) {
            try {
                String aboutUrl = buildUrl(root, path);
                page.navigate(
                        aboutUrl,
                        new Page.NavigateOptions()
                                .setTimeout(captureTimeoutSeconds * 1000.0)
                                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                );
                page.waitForTimeout(1000);
                String fromAbout = firstVisibleText(page, aboutSelectors, null);
                if (fromAbout != null && fromAbout.length() >= 24) {
                    return truncate(fromAbout);
                }
            } catch (Exception ex) {
                log.debug("No about page at {}: {}", path, ex.getMessage());
            }
        }
        return currentDescription;
    }

    private String extractDescriptionFromLivePage(
            Page page,
            List<String> aboutSelectors,
            String productName,
            String realmName
    ) {
        String fromSelectors = firstVisibleText(page, aboutSelectors, null);
        if (fromSelectors != null && fromSelectors.length() >= 24) {
            return truncate(fromSelectors);
        }

        try {
            Document document = Jsoup.parse(page.content());
            return extractDescription(document, productName, realmName);
        } catch (Exception ex) {
            log.debug("Could not parse rendered HTML for description: {}", ex.getMessage());
            return defaultDescription(productName, realmName);
        }
    }

    /**
     * Screenshots the visible home / main / front page (viewport), not a cropped banner only.
     */
    private String screenshotHomePage(
            Page page,
            List<String> homePageSelectors,
            String productName,
            String realmName
    ) {
        try {
            byte[] png = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.PNG)
                    .setFullPage(false));
            log.info("Captured home page viewport screenshot from {}", page.url());
            return toDataUriPng(png);
        } catch (Exception ex) {
            log.warn("Home page viewport screenshot failed for {}: {}", page.url(), ex.getMessage());
        }

        for (String selector : homePageSelectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() == 0 || !locator.isVisible()) {
                    continue;
                }
                byte[] png = locator.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.PNG));
                log.info("Captured home page element screenshot using selector '{}'", selector);
                return toDataUriPng(png);
            } catch (Exception ex) {
                log.debug("Home page selector '{}' failed: {}", selector, ex.getMessage());
            }
        }

        return buildPlaceholderSvg(productName, realmName);
    }

    private String firstVisibleText(Page page, List<String> selectors, String fallback) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() == 0 || !locator.isVisible()) {
                    continue;
                }
                String text = cleanText(locator.innerText());
                if (text.length() >= 3) {
                    return text;
                }
            } catch (Exception ex) {
                log.debug("Selector '{}' text read failed: {}", selector, ex.getMessage());
            }
        }
        return fallback;
    }

    private CaptureResult captureWithJsoupFallback(String url, String productName, String realmName) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Paxo-Showcase-Capture/1.0")
                    .timeout(Math.max(15, captureTimeoutSeconds) * 1000)
                    .get();

            String description = extractDescription(document, productName, realmName);
            String previewImage = resolvePreviewImage(document, url, productName, realmName);
            return new CaptureResult(productName, description, previewImage);
        } catch (Exception ex) {
            log.warn("HTML showcase capture failed for {}: {}", url, ex.getMessage());
            return new CaptureResult(
                    productName,
                    defaultDescription(productName, realmName),
                    buildPlaceholderSvg(productName, realmName)
            );
        }
    }

    String extractDescription(Document document, String productName, String realmName) {
        if (document == null) {
            return defaultDescription(productName, realmName);
        }

        List<String> candidates = new ArrayList<>();
        for (String selector : DEFAULT_ABOUT_SELECTORS) {
            for (Element element : document.select(selector)) {
                addIfPresent(candidates, element.text());
            }
        }

        addIfPresent(candidates, metaContent(document, "description"));
        addIfPresent(candidates, metaProperty(document, "og:description"));
        addIfPresent(candidates, metaProperty(document, "twitter:description"));

        for (String candidate : candidates) {
            String cleaned = cleanText(candidate);
            if (cleaned.length() >= 24) {
                return truncate(cleaned);
            }
        }

        return defaultDescription(productName, realmName);
    }

    private String resolvePreviewImage(
            Document document,
            String pageUrl,
            String productName,
            String realmName
    ) {
        String ogImage = metaProperty(document, "og:image");
        if (ogImage != null && !ogImage.isBlank()) {
            try {
                return downloadAsDataUri(resolveUrl(pageUrl, ogImage));
            } catch (Exception ex) {
                log.debug("Could not download og:image for {}: {}", pageUrl, ex.getMessage());
            }
        }
        return buildPlaceholderSvg(productName, realmName);
    }

    private String downloadAsDataUri(String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Image download HTTP " + response.statusCode());
        }

        String contentType = response.headers()
                .firstValue("content-type")
                .orElse("image/png")
                .split(";")[0]
                .trim();
        if (!contentType.startsWith("image/")) {
            contentType = "image/png";
        }

        String encoded = Base64.getEncoder().encodeToString(response.body());
        return "data:" + contentType + ";base64," + encoded;
    }

    private static String defaultDescription(String productName, String realmName) {
        String name = productName == null || productName.isBlank() ? "Product" : productName.trim();
        String realm = realmName == null || realmName.isBlank() ? "default" : realmName.trim();
        return truncate(name + " — provisioned on the " + realm + " realm and deployed via Paxo GitOps.");
    }

    String buildPlaceholderSvg(String productName, String realmName) {
        String title = escapeXml(productName == null || productName.isBlank() ? "Product" : productName.trim());
        String realm = escapeXml(realmName == null || realmName.isBlank() ? "realm" : realmName.trim());
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="900" height="520" viewBox="0 0 900 520">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0%%" stop-color="#1d4ed8"/>
                      <stop offset="100%%" stop-color="#0f172a"/>
                    </linearGradient>
                  </defs>
                  <rect width="900" height="520" fill="url(#bg)"/>
                  <circle cx="760" cy="90" r="120" fill="rgba(255,255,255,0.08)"/>
                  <text x="48" y="96" fill="#93c5fd" font-family="Arial, sans-serif" font-size="20" font-weight="700">PAXO PRODUCT</text>
                  <text x="48" y="170" fill="#ffffff" font-family="Arial, sans-serif" font-size="52" font-weight="800">%s</text>
                  <text x="48" y="220" fill="#dbeafe" font-family="Arial, sans-serif" font-size="24">Realm: %s</text>
                  <text x="48" y="430" fill="#bfdbfe" font-family="Arial, sans-serif" font-size="18">Live on Kubernetes · Argo CD</text>
                </svg>
                """.formatted(title, realm);
        String encoded = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return "data:image/svg+xml;base64," + encoded;
    }

    private static String toDataUriPng(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static List<String> resolveSelectors(String property, List<String> defaults) {
        if (property == null || property.isBlank()) {
            return defaults;
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> resolvePaths(String property) {
        if (property == null || property.isBlank()) {
            return List.of();
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Frontend URL is required for showcase capture");
        }
        return url.trim().replaceAll("/+$", "");
    }

    private static String resolveUrl(String baseUrl, String candidate) throws Exception {
        URI resolved = URI.create(baseUrl).resolve(candidate.trim());
        return resolved.toString();
    }

    private static String metaContent(Document document, String name) {
        Element element = document.selectFirst("meta[name=" + name + "]");
        return element == null ? null : element.attr("content");
    }

    private static String metaProperty(Document document, String property) {
        Element element = document.selectFirst("meta[property=" + property + "]");
        return element == null ? null : element.attr("content");
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private static String cleanText(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ");
    }

    private static String truncate(String value) {
        if (value.length() <= DESCRIPTION_MAX) {
            return value;
        }
        return value.substring(0, DESCRIPTION_MAX - 1).trim() + "…";
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public record CaptureResult(String productName, String description, String previewImage) {
    }
}
