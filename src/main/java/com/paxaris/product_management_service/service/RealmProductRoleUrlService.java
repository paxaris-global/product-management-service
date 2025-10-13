package com.paxaris.product_management_service.service;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.dto.UrlEntry;
import com.paxaris.product_management_service.entities.RealmProductRole;

import java.util.List;

public interface RealmProductRoleUrlService {

    /**
     * Create or update a role (with its URL/URI list).
     */
    void saveOrUpdateRole(RoleRequest request);


    /**
     * Get all roles (with their URLs).
     */
    List<RealmProductRole> getAll();

    /**
     * Get a role by ID.
     */
    RealmProductRole getById(Long id);

    void deleteById(Long id);

    List<UrlEntry> getUrlsByRole(String realmName, String productName, String roleName);

}
