package com.paxaris.product_management_service.exception;

public class RoleNotFoundException extends RuntimeException {
    public RoleNotFoundException(String realmName, String productName, String roleName) {
        super(String.format("Role not found: realm='%s', product='%s', role='%s'",
                realmName, productName, roleName));
    }
}
