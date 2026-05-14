package com.paxaris.product_management_service.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "product_url_mapping",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_url_mapping_realm_product",
        columnNames = {"realm_name", "product_id"}
    )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductUrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "s_no")
    private Long sNo;

    @Column(name = "realm_name", nullable = false, length = 100)
    private String realmName;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(name = "frontend_node_port", nullable = false)
    private Integer frontendNodePort;

    @Column(name = "backend_node_port", nullable = false)
    private Integer backendNodePort;

    @Column(name = "frontend_base_url", nullable = false, length = 512)
    private String frontendBaseUrl;

    @Column(name = "backend_base_url", nullable = false, length = 512)
    private String backendBaseUrl;
}
