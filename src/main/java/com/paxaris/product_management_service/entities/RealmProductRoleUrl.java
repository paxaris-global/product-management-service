package com.paxaris.product_management_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @Column(name = "base_url", length = 191, nullable = false)
    private String url;

    @Column(name = "uri", length = 191, nullable = false)
    private String uri;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RealmProductRole role;
}


