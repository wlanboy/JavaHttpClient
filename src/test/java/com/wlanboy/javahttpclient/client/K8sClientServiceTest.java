package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class K8sClientServiceTest {

    private K8sClientService service;

    @BeforeEach
    void setUp() {
        service = new K8sClientService();
    }

    @Test
    void serviceInitialization_doesNotThrowException() {
        assertDoesNotThrow(() -> new K8sClientService());
    }

    @Test
    void getCurrentNamespace_returnsValue() {
        String result = service.getCurrentNamespace();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should return "default" if not in K8s environment
    }

    @Test
    void isInitialized_returnsBooleanWithoutException() {
        assertDoesNotThrow(() -> service.isInitialized());
    }

    @Test
    void listNamespacedCustomObject_withoutClusterAccess_returnsEmptyOptional() {
        // In a test environment without a real K8s cluster, this should never throw
        // and should gracefully degrade to Optional.empty().
        Optional<List<Object>> result = service.listNamespacedCustomObject(
                "networking.istio.io", "v1", "default", "virtualservices");

        assertNotNull(result);
        if (!service.isInitialized()) {
            assertTrue(result.isEmpty());
        }
    }
}
