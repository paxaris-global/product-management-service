package com.paxaris.product_management_service.entities;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "realm_product_role_url")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealmProductRoleUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", length = 10, nullable = false)
    private HttpMethodType httpMethod;

    @Column(name = "uri", length = 191, nullable = false)
    private String uri;

    /**
     * Gateway forwards matching requests to {@code serviceBaseUrl + requestPath}.
     * Serialized as {@code url} for API Gateway compatibility.
     */
    @Column(name = "service_base_url", length = 512)
    @JsonProperty("url")
    private String serviceBaseUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    @JsonBackReference
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RealmProductRole role;
}