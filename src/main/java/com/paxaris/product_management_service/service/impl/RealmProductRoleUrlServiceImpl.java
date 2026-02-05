package com.paxaris.product_management_service.service.impl;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.dto.UrlEntry;
import com.paxaris.product_management_service.entities.HttpMethodType;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.entities.RealmProductRoleUrl;
import com.paxaris.product_management_service.exception.RoleNotFoundException;
import com.paxaris.product_management_service.repository.RealmProductRoleRepository;
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
            role = existingRole.get();

            // Replace permissions only when sent
            if (request.getUrls() != null && !request.getUrls().isEmpty()) {
                role.getUrls().clear();
            }

        } else {
            role = RealmProductRole.builder()
                    .realmName(request.getRealmName())
                    .productName(request.getProductName())
                    .roleName(request.getRoleName())
                    .urls(new ArrayList<>())
                    .build();
        }

        // Add new URL mappings if provided
        if (request.getUrls() != null) {
            for (UrlEntry entry : request.getUrls()) {

                role.getUrls().add(
                        RealmProductRoleUrl.builder()
                                .url(entry.getUrl())
                                .uri(entry.getUri())
                                .httpMethod(HttpMethodType.valueOf(entry.getHttpMethod()))
                                .role(role)
                                .build()
                );
            }
        }

        roleRepository.save(role);
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

    //get url by role
    @Override
    public List<UrlEntry> getUrlsByRole(String realmName, String productName, String roleName) {
        RealmProductRole role = roleRepository.findByRealmNameAndProductNameAndRoleName(
                realmName, productName, roleName
        ).orElseThrow(() -> new RoleNotFoundException(realmName, productName, roleName));

        List<UrlEntry> urls = new ArrayList<>();
        for (RealmProductRoleUrl url : role.getUrls()) {
            urls.add(new UrlEntry(
                    url.getId(),
                    url.getUrl(),
                    url.getUri(),
                    url.getHttpMethod().name()
            ));
        }
        return urls;
    }



}