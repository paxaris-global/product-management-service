package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.RealmProductRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RealmProductRoleRepository extends JpaRepository<RealmProductRole, Long> {
    Optional<RealmProductRole> findByRealmNameAndProductNameAndRoleName(String realmName, String productName, String roleName);

    @Query("SELECT DISTINCT r FROM RealmProductRole r LEFT JOIN FETCH r.urls")
    List<RealmProductRole> findAllWithUrls();
}
