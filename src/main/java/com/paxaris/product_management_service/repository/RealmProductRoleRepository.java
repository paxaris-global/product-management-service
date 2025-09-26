package com.paxaris.product_management_service.repository;

import com.paxaris.product_management_service.entities.RealmProductRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RealmProductRoleRepository extends JpaRepository<RealmProductRole, Long> {

}
