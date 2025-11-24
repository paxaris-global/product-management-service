package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.dto.UrlEntry;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/project/roles")
@RequiredArgsConstructor
public class RealmProductRoleUrlController {

    private final RealmProductRoleUrlService service;

    @PostMapping("/save-or-update")
    public ResponseEntity<Void> saveOrUpdate(@RequestBody RoleRequest request) {
        service.saveOrUpdateRole(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<RealmProductRole>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RealmProductRole> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/get-urls")
    public ResponseEntity<List<UrlEntry>> getUrls(@RequestBody RoleRequest request) {
        System.out.println("=== RoleRequest received from Identity Service ===");
        System.out.println("Realm Name: " + request.getRealmName());
        System.out.println("Product Name: " + request.getProductName());
        System.out.println("Role Name: " + request.getRoleName());
        System.out.println("Full object: " + request);

        List<UrlEntry> urls = new ArrayList<>();

        try {
            // If you already store roles in DB, get URL for the role
            if (request.getRoleName() != null) {
                // Split by comma if multiple roles (optional, if you decide to send all roles in one string)
                String[] roles = request.getRoleName().split(",");
                for (String role : roles) {
                    List<UrlEntry> roleUrls = service.getUrlsByRole(
                            request.getRealmName(),
                            request.getProductName(),
                            role.trim()
                    );
                    if (roleUrls != null) {
                        urls.addAll(roleUrls);
                    }
                }
            }
            return ResponseEntity.ok(urls);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }



}
