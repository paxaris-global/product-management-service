package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.ProductShowcase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductShowcaseRepository extends JpaRepository<ProductShowcase, Long> {

    Optional<ProductShowcase> findByRealmNameAndProductId(String realmName, String productId);

    Optional<ProductShowcase> findByRealmNameIgnoreCaseAndProductIdIgnoreCase(String realmName, String productId);

    List<ProductShowcase> findAllByOrderByCapturedAtDesc();

    List<ProductShowcase> findByRealmNameOrderByCapturedAtDesc(String realmName);

    List<ProductShowcase> findByRealmNameIgnoreCaseOrderByCapturedAtDesc(String realmName);
}
