package com.paxaris.product_management_service.controller;

import com.paxaris.product_management_service.dto.RoleRequest;
import com.paxaris.product_management_service.entities.RealmProductRole;
import com.paxaris.product_management_service.service.RealmProductRoleUrlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealmProductRoleUrlControllerTest {

    @Mock
    private RealmProductRoleUrlService service;

    @InjectMocks
    private RealmProductRoleUrlController controller;

    @Test
    void saveOrUpdateReturnsOk() {
        RoleRequest request = RoleRequest.builder()
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .build();

        doNothing().when(service).saveOrUpdateRole(request);

        ResponseEntity<Void> response = controller.saveOrUpdate(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service, times(1)).saveOrUpdateRole(request);
    }

    @Test
    void saveOrUpdateWithUriAndHttpMethod() {
        RoleRequest request = RoleRequest.builder()
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .uri("/api/products")
                .httpMethod("GET")
                .build();

        doNothing().when(service).saveOrUpdateRole(request);

        ResponseEntity<Void> response = controller.saveOrUpdate(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service, times(1)).saveOrUpdateRole(request);
    }

    @Test
    void saveOrUpdateWithMultipleHttpMethods() {
        // Test POST request
        RoleRequest postRequest = RoleRequest.builder()
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .uri("/api/products")
                .httpMethod("POST")
                .build();

        doNothing().when(service).saveOrUpdateRole(postRequest);

        ResponseEntity<Void> response = controller.saveOrUpdate(postRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        // Test PUT request
        RoleRequest putRequest = RoleRequest.builder()
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .uri("/api/products/123")
                .httpMethod("PUT")
                .build();

        doNothing().when(service).saveOrUpdateRole(putRequest);
        response = controller.saveOrUpdate(putRequest);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        // Test DELETE request
        RoleRequest deleteRequest = RoleRequest.builder()
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .uri("/api/products/123")
                .httpMethod("DELETE")
                .build();

        doNothing().when(service).saveOrUpdateRole(deleteRequest);
        response = controller.saveOrUpdate(deleteRequest);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getAllReturnsServiceData() {
        RealmProductRole role = RealmProductRole.builder()
                .id(1L)
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .build();

        when(service.getAll()).thenReturn(List.of(role));

        ResponseEntity<List<RealmProductRole>> response = controller.getAll();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("admin", response.getBody().getFirst().getRoleName());
        verify(service, times(1)).getAll();
    }

    @Test
    void deleteByIdReturnsNoContent() {
        Long id = 5L;
        doNothing().when(service).deleteById(id);

        ResponseEntity<Void> response = controller.deleteById(id);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service, times(1)).deleteById(id);
    }

    @Test
    void getByIdReturnsRole() {
        Long id = 1L;
        RealmProductRole role = RealmProductRole.builder()
                .id(id)
                .realmName("demo")
                .productName("pm")
                .roleName("admin")
                .build();

        when(service.getById(id)).thenReturn(role);

        ResponseEntity<RealmProductRole> response = controller.getById(id);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("admin", response.getBody().getRoleName());
        assertEquals("demo", response.getBody().getRealmName());
        verify(service, times(1)).getById(id);
    }
}
