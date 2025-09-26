package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
