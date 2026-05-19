package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.dto.CaptureShowcaseRequest;
import com.paxaris.product_management_service.dto.ProductShowcaseResponse;
import com.paxaris.product_management_service.service.ProductShowcaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@RestController
@RequestMapping({"/project/showcases", "/api/v1/project/showcases"})
@RequiredArgsConstructor
@Tag(name = "Product Showcases", description = "Catalog of provisioned products for the Paxo home page")
public class ProductShowcaseController {

    private final ProductShowcaseService showcaseService;

    @GetMapping
    @Operation(summary = "List product showcases", description = "Returns provisioned product cards for the Paxo home page")
    public ResponseEntity<List<ProductShowcaseResponse>> listShowcases(
            @RequestParam(value = "realm", required = false) String realm
    ) {
        return ResponseEntity.ok(showcaseService.listShowcases(realm));
    }

    @GetMapping("/{realmName}/{productId}")
    @Operation(summary = "Get a product showcase")
    public ResponseEntity<ProductShowcaseResponse> getShowcase(
            @PathVariable String realmName,
            @PathVariable String productId
    ) {
        return showcaseService.getShowcase(realmName, productId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Showcase not found for realm='" + realmName + "', product='" + productId + "'"
                ));
    }

    @PostMapping("/{realmName}/{productId}/capture")
    @Operation(
            summary = "Capture product showcase",
            description = "Waits for deployment health, screenshots the live frontend, and stores catalog metadata"
    )
    public ResponseEntity<ProductShowcaseResponse> captureShowcase(
            @PathVariable String realmName,
            @PathVariable String productId,
            @RequestBody(required = false) CaptureShowcaseRequest request
    ) {
        try {
            return ResponseEntity.ok(showcaseService.captureShowcase(realmName, productId, request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Failed to capture showcase for {} / {}: {}", realmName, productId, ex.getMessage(), ex);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to capture product showcase: " + ex.getMessage(),
                    ex
            );
        }
    }
}
