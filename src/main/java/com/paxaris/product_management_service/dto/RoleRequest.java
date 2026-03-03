package com.paxaris.product_management_service.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleRequest {
    private Long id;
    private String realmName;
    private String productName;
    private String roleName;
    private String uri;
    private String httpMethod;
}
