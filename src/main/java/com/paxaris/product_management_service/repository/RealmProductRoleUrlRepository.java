package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.RealmProductRoleUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RealmProductRoleUrlRepository extends JpaRepository<RealmProductRoleUrl, Long> {
}

