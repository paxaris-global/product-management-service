package com.paxaris.product_management_service.service.impl;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.entities.HttpMethodType;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.entities.RealmProductRoleUrl;
import com.paxaris.product_management_service.repository.RealmProductRoleRepository;
import com.paxaris.product_management_service.repository.RealmProductRoleUrlRepository;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class RealmProductRoleUrlServiceImpl implements RealmProductRoleUrlService {

    private static final Logger logger = LoggerFactory.getLogger(RealmProductRoleUrlServiceImpl.class);

    private final RealmProductRoleRepository roleRepository;
    private final RealmProductRoleUrlRepository urlRepository;

    @Override
    public void saveOrUpdateRole(RoleRequest request) {
        logger.info("Starting saveOrUpdateRole for realm='{}', product='{}', role='{}', uri='{}', httpMethod='{}'",
                request.getRealmName(), request.getProductName(), request.getRoleName(),
                request.getUri(), request.getHttpMethod());

        // Step 1: Save or Update Role in realm_product_role table
        Optional<RealmProductRole> existingRole =
                roleRepository.findByRealmNameAndProductNameAndRoleName(
                        request.getRealmName(),
                        request.getProductName(),
                        request.getRoleName()
                );

        RealmProductRole role;

        if (existingRole.isPresent()) {
            logger.info("Role exists, updating: realm='{}', product='{}', role='{}'",
                    request.getRealmName(), request.getProductName(), request.getRoleName());
            role = existingRole.get();
            role.setRealmName(request.getRealmName());
            role.setProductName(request.getProductName());
            role.setRoleName(request.getRoleName());

        } else {
            logger.info("Creating new role: realm='{}', product='{}', role='{}'",
                    request.getRealmName(), request.getProductName(), request.getRoleName());
            role = RealmProductRole.builder()
                    .realmName(request.getRealmName())
                    .productName(request.getProductName())
                    .roleName(request.getRoleName())
                    .build();
        }

        // Save the role first
        RealmProductRole savedRole = roleRepository.save(role);
        logger.info("✅ Role saved to realm_product_role table with id='{}'", savedRole.getId());

        String normalizedUri = normalizeUri(request.getUri());
        HttpMethodType normalizedMethod = parseHttpMethod(request.getHttpMethod());

        // Step 2: Save URI and HTTP Method to RealmProductRoleUrl table if provided
        if (normalizedUri != null && normalizedMethod != null) {
            logger.info("Persisting RealmProductRoleUrl: uri='{}', httpMethod='{}', roleId='{}'",
                    normalizedUri, normalizedMethod, savedRole.getId());

            RealmProductRoleUrl roleUrl = urlRepository
                    .findByRoleAndUriAndHttpMethod(savedRole, normalizedUri, normalizedMethod)
                    .orElseGet(() -> RealmProductRoleUrl.builder()
                            .role(savedRole)
                            .uri(normalizedUri)
                            .httpMethod(normalizedMethod)
                            .build());

            roleUrl.setRole(savedRole);
            roleUrl.setUri(normalizedUri);
            roleUrl.setHttpMethod(normalizedMethod);

            RealmProductRoleUrl savedUrl = urlRepository.save(roleUrl);
            logger.info("Saved to realm_product_role_url table -> id='{}', uri='{}', httpMethod='{}', roleId='{}'",
                    savedUrl.getId(), savedUrl.getUri(), savedUrl.getHttpMethod(), savedRole.getId());
        } else {
            logger.warn("Skipping realm_product_role_url save because uri/httpMethod missing or invalid. uri='{}', httpMethod='{}'",
                    request.getUri(), request.getHttpMethod());
        }
    }

    private HttpMethodType parseHttpMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }

        try {
            return HttpMethodType.valueOf(method.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.warn("Invalid httpMethod '{}'. Expected one of {}", method, java.util.Arrays.toString(HttpMethodType.values()));
            return null;
        }
    }

    private String normalizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }

        String trimmed = uri.trim();
        try {
            URI parsed = URI.create(trimmed);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                return "/";
            }
            return path;
        } catch (IllegalArgumentException ex) {
            return trimmed;
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