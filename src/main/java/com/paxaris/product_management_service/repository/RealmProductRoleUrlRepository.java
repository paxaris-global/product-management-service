package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.HttpMethodType;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.entities.RealmProductRoleUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RealmProductRoleUrlRepository extends JpaRepository<RealmProductRoleUrl, Long> {
    Optional<RealmProductRoleUrl> findByRoleAndUriAndHttpMethod(RealmProductRole role, String uri, HttpMethodType httpMethod);
}
