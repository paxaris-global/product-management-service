package com.paxaris.product_management_service.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlEntry {
    private Long id;  // null → new URL
    private String url;
    private String uri;
}
