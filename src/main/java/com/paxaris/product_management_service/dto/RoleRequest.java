package com.paxaris.product_management_service.dto;

import lombok.*;

import java.util.List;

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
    /** Single URI (legacy). Ignored when {@link #urls} is non-empty. */
    private String uri;
    /** Single method (legacy). Ignored when {@link #urls} is non-empty. */
    private String httpMethod;
    /**
     * Bulk permissions from UI / OpenAPI import. Each entry: optional upstream {@link UrlEntry#getUrl()},
     * path {@link UrlEntry#getUri()}, {@link UrlEntry#getHttpMethod()}.
     */
    private List<UrlEntry> urls;
}
