package com.paxaris.product_management_service.service.impl;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.dto.UrlEntry;
import com.paxaris.product_management_service.entities.HttpMethodType;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.entities.RealmProductRoleUrl;
import com.paxaris.product_management_service.exception.RoleNotFoundException;
import com.paxaris.product_management_service.repository.RealmProductRoleRepository;
import com.paxaris.product_management_service.repository.RealmProductRoleUrlRepository;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class RealmProductRoleUrlServiceImpl implements RealmProductRoleUrlService {

    private final RealmProductRoleRepository roleRepository;
    private final RealmProductRoleUrlRepository urlRepository;

    @Override
    public void saveOrUpdateRole(RoleRequest request) {

        Optional<RealmProductRole> existingRole =
                roleRepository.findByRealmNameAndProductNameAndRoleName(
                        request.getRealmName(),
                        request.getProductName(),
                        request.getRoleName()
                );

        RealmProductRole role;

        if (existingRole.isPresent()) {
            // Role already exists → update basic fields
            role = existingRole.get();
            role.setRealmName(request.getRealmName());
            role.setProductName(request.getProductName());
            role.setRoleName(request.getRoleName());

        } else {
            // Create new role
            role = RealmProductRole.builder()
                    .realmName(request.getRealmName())
                    .productName(request.getProductName())
                    .roleName(request.getRoleName())
                    .build();
        }

        // Save the role first
        RealmProductRole savedRole = roleRepository.save(role);

        // Save URI and HTTP Method to RealmProductRoleUrl table if provided
        if (request.getUri() != null && request.getHttpMethod() != null) {
            RealmProductRoleUrl roleUrl = RealmProductRoleUrl.builder()
                    .uri(request.getUri())
                    .httpMethod(HttpMethodType.valueOf(request.getHttpMethod()))
                    .role(savedRole)
                    .build();

            urlRepository.save(roleUrl);
        }
    }



    @Override
    public List<RealmProductRole> getAll() {
        return roleRepository.findAll();
    }

    @Override
    public RealmProductRole getById(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Role not found with id " + id));
    }

    @Override
    public void deleteById(Long id) {
        roleRepository.deleteById(id);
    }




}