package com.paxaris.product_management_service.entities;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "realm_product_role")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealmProductRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "realm_name", length = 50, nullable = false)
    private String realmName;

    @Column(name = "product_name", length = 50, nullable = false)
    private String productName;

    @Column(name = "role_name", length = 50, nullable = false)
    private String roleName;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<RealmProductRoleUrl> urls;
}
