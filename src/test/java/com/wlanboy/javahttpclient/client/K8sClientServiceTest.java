package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class K8sClientServiceTest {

    /** Simuliert eine Umgebung ohne Kubeconfig / ServiceAccount. */
    static K8sClientService withoutCluster() {
        return new K8sClientService(() -> {
            throw new IOException("kein Cluster im Test");
        });
    }

    private K8sClientService service;

    @BeforeEach
    void setUp() {
        service = withoutCluster();
    }

    @Test
    void serviceInitialization_doesNotThrowException() {
        assertDoesNotThrow(K8sClientServiceTest::withoutCluster);
    }

    @Test
    void getCurrentNamespace_returnsValue() {
        String result = service.getCurrentNamespace();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should return "default" if not in K8s environment
    }

    @Test
    void isInitialized_withoutCluster_returnsFalse() {
        assertFalse(service.isInitialized());
        assertEquals("kein Cluster im Test", service.getInitError());
    }

    @Test
    void listNamespacedCustomObject_withoutClusterAccess_returnsEmptyOptional() {
        Optional<List<Object>> result = service.listNamespacedCustomObject(
                "networking.istio.io", "v1", "default", "virtualservices");

        assertTrue(result.isEmpty());
    }

    @Test
    void listNamespacedCustomObject_withUnreachableApiServer_returnsEmptyOptional() {
        // Port 1 auf localhost: Verbindung wird sofort abgelehnt
        K8sClientService unreachable = new K8sClientService(
                () -> new ApiClient().setBasePath("http://127.0.0.1:1"));

        assertTrue(unreachable.isInitialized());
        Optional<List<Object>> result = unreachable.listNamespacedCustomObject(
                "networking.istio.io", "v1", "default", "virtualservices");

        assertTrue(result.isEmpty());
    }
}
