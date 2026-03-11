package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/project/roles", "/api/v1/project/roles"})
@RequiredArgsConstructor
@Tag(name = "Role URLs", description = "Role to URL mapping APIs")
public class RealmProductRoleUrlController {

    private final RealmProductRoleUrlService service;

    @PostMapping("/save-or-update")
    @Operation(summary = "Save or update role mapping", description = "Creates or updates role URI/method mappings")
    @ApiResponse(responseCode = "200", description = "Role mapping saved")
    public ResponseEntity<Void> saveOrUpdate(@RequestBody RoleRequest request) {
        service.saveOrUpdateRole(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    @Operation(summary = "Get all role mappings", description = "Returns all realm-product-role mappings")
    @ApiResponse(responseCode = "200", description = "Role mappings retrieved")
    public ResponseEntity<List<RealmProductRole>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role mapping by id", description = "Returns one role mapping by identifier")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Role mapping found"),
            @ApiResponse(responseCode = "404", description = "Role mapping not found")
    })
    public ResponseEntity<RealmProductRole> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete role mapping", description = "Deletes role mapping by identifier")
    @ApiResponse(responseCode = "204", description = "Role mapping deleted")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

//    @PostMapping("/get-urls")
//    public ResponseEntity<List<UrlEntry>> getUrls(@RequestBody RoleRequest request) {
//        System.out.println("=== RoleRequest received from Identity Service ===");
//        System.out.println("Realm Name: " + request.getRealmName());
//        System.out.println("Product Name: " + request.getProductName());
//        System.out.println("Role Name: " + request.getRoleName());
//        System.out.println("Full object: " + request);
//
//        List<UrlEntry> urls = new ArrayList<>();
//
//        try {
//            // If you already store roles in DB, get URL for the role
//            if (request.getRoleName() != null) {
//                // Split by comma if multiple roles (optional, if you decide to send all roles in one string)
//                String[] roles = request.getRoleName().split(",");
//                for (String role : roles) {
//                    List<UrlEntry> roleUrls = service.getUrlsByRole(
//                            request.getRealmName(),
//                            request.getProductName(),
//                            role.trim()
//                    );
//                    if (roleUrls != null) {
//                        urls.addAll(roleUrls);
//                    }
//                }
//            }
//            return ResponseEntity.ok(urls);
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
//        }
//    }
}