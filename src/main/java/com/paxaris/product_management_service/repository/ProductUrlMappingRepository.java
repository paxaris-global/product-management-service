package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.ProductUrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductUrlMappingRepository extends JpaRepository<ProductUrlMapping, Long> {

    Optional<ProductUrlMapping> findByRealmNameAndProductId(String realmName, String productId);

    @Query("select p.frontendNodePort from ProductUrlMapping p")
    List<Integer> findFrontendNodePorts();

    @Query("select p.backendNodePort from ProductUrlMapping p")
    List<Integer> findBackendNodePorts();
}
