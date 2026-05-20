package com.paxaris.product_management_service.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "product_showcase",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_product_showcase_realm_product",
                columnNames = {"realm_name", "product_id"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShowcase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "realm_name", nullable = false, length = 100)
    private String realmName;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "frontend_url", nullable = false, length = 512)
    private String frontendUrl;

    @Column(name = "description", length = 2000)
    private String description;

    @Lob
    @Column(name = "preview_image", columnDefinition = "LONGTEXT")
    private String previewImage;

    /** When true, catalog uses the uploaded banner instead of Playwright screenshots. */
    @Column(name = "custom_banner", nullable = false)
    @Builder.Default
    private boolean customBanner = false;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
