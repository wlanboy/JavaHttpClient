package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void listNamespacedCustomObject_withUnreachableApiServer_throws() {
        // Port 1 auf localhost: Verbindung wird sofort abgelehnt
        K8sClientService unreachable = new K8sClientService(
                () -> new ApiClient().setBasePath("http://127.0.0.1:1"));

        assertTrue(unreachable.isInitialized());
        K8sApiException e = assertThrows(K8sApiException.class, () -> unreachable.listNamespacedCustomObject(
                "networking.istio.io", "v1", "default", "virtualservices"));

        assertEquals(0, e.getStatusCode());
        assertTrue(e.getMessage().contains("nicht erreichbar"), e.getMessage());
    }

    /** Startet einen lokalen API-Server, der jede Anfrage mit status/body beantwortet. */
    private static HttpServer startApiServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static K8sClientService against(HttpServer server) {
        return new K8sClientService(() -> new ApiClient()
                .setBasePath("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void listNamespacedCustomObject_forbidden_throwsWithRbacMessage() throws Exception {
        HttpServer server = startApiServer(403, """
                {"kind":"Status","apiVersion":"v1","status":"Failure","reason":"Forbidden","code":403,
                 "message":"authorizationpolicies.security.istio.io is forbidden: User \\"system:serviceaccount:clients:default\\" cannot list resource"}""");
        try {
            K8sApiException e = assertThrows(K8sApiException.class, () -> against(server).listNamespacedCustomObject(
                    "security.istio.io", "v1", "demo", "authorizationpolicies"));

            assertEquals(403, e.getStatusCode());
            assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
            assertTrue(e.getMessage().contains("cannot list resource"), e.getMessage());
            assertTrue(e.getMessage().contains("'demo'"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listNamespacedCustomObject_versionNotFound_returnsEmptyOptional() throws Exception {
        HttpServer server = startApiServer(404, "{\"kind\":\"Status\",\"code\":404}");
        try {
            assertTrue(against(server).listNamespacedCustomObject(
                    "networking.istio.io", "v1alpha3", "default", "virtualservices").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listNamespacedCustomObject_success_returnsItems() throws Exception {
        HttpServer server = startApiServer(200, "{\"items\":[{\"metadata\":{\"name\":\"vs1\"}}]}");
        try {
            Optional<List<Object>> result = against(server).listNamespacedCustomObject(
                    "networking.istio.io", "v1", "default", "virtualservices");

            assertEquals(1, result.orElseThrow().size());
        } finally {
            server.stop(0);
        }
    }
}
