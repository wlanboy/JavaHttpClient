package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.IstioDiagnosticService;
import com.wlanboy.javahttpclient.client.TlsInspectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiagnosticControllerTest {

    @Mock
    private IstioDiagnosticService istioService;

    @Mock
    private TlsInspectorService tlsService;

    @InjectMocks
    private DiagnosticController controller;

    @Test
    void getContext_returnsContextFromService() {
        Map<String, Object> context = new HashMap<>();
        context.put("podName", "test-pod-123");
        context.put("namespace", "default");
        context.put("istioSidecar", true);
        when(istioService.getContext()).thenReturn(context);

        ResponseEntity<Map<String, Object>> response = controller.getContext();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("test-pod-123", response.getBody().get("podName"));
        assertEquals("default", response.getBody().get("namespace"));
        assertEquals(true, response.getBody().get("istioSidecar"));
        verify(istioService).getContext();
    }

    @Test
    void getContext_withoutIstioSidecar_returnsContextWithoutIstioDetails() {
        Map<String, Object> context = new HashMap<>();
        context.put("podName", "test-pod-456");
        context.put("namespace", "production");
        context.put("istioSidecar", false);
        when(istioService.getContext()).thenReturn(context);

        ResponseEntity<Map<String, Object>> response = controller.getContext();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("istioSidecar"));
        assertNull(response.getBody().get("istioDetails"));
    }

    @Test
    void getFullReport_returnsReportFromService() {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> reachability = new HashMap<>();
        reachability.put("summary", "10 aktive Upstream-Cluster-Einträge");
        report.put("reachability", reachability);
        report.put("timestamp", new Date());
        when(istioService.getFullSidecarDetails()).thenReturn(report);

        ResponseEntity<Map<String, Object>> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("reachability"));
        assertTrue(response.getBody().containsKey("timestamp"));
        verify(istioService).getFullSidecarDetails();
    }

    @Test
    void getFullReport_withError_returnsOkWithErrorMessage() {
        Map<String, Object> report = new HashMap<>();
        report.put("error", "Istio Sidecar Proxy ist nicht aktiv");
        when(istioService.getFullSidecarDetails()).thenReturn(report);

        ResponseEntity<Map<String, Object>> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        assertEquals("Istio Sidecar Proxy ist nicht aktiv", response.getBody().get("error"));
    }

    @Test
    void getFullReport_withHealthDiagnostics_returnsErrorMetrics() {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> health = new HashMap<>();
        health.put("activeErrorMetrics", Map.of("cluster.outbound.upstream_rq_5xx", "5"));
        health.put("errorCount", 1);
        report.put("healthDiagnostics", health);
        when(istioService.getFullSidecarDetails()).thenReturn(report);

        ResponseEntity<Map<String, Object>> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> healthResult = (Map<String, Object>) response.getBody().get("healthDiagnostics");
        assertEquals(1, healthResult.get("errorCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_withVirtualServices_returnsResources() {
        List<Object> resources = List.of(
                Map.of("metadata", Map.of("name", "vs-test")),
                Map.of("metadata", Map.of("name", "vs-prod"))
        );
        when(istioService.getIstioResources("default", "virtualservices")).thenReturn(resources);

        ResponseEntity<?> response = controller.getIstioResources("virtualservices", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, ((List<Object>) response.getBody()).size());
        verify(istioService).getIstioResources("default", "virtualservices");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_withDestinationRules_returnsResources() {
        List<Object> resources = List.of(
                Map.of("metadata", Map.of("name", "dr-service-a"))
        );
        when(istioService.getIstioResources("production", "destinationrules")).thenReturn(resources);

        ResponseEntity<?> response = controller.getIstioResources("destinationrules", "production");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, ((List<Object>) response.getBody()).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_withDefaultNamespace_usesDefault() {
        when(istioService.getIstioResources("default", "virtualservices")).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.getIstioResources("virtualservices", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(((List<Object>) response.getBody()).isEmpty());
        verify(istioService).getIstioResources("default", "virtualservices");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_noResourcesFound_returnsEmptyList() {
        when(istioService.getIstioResources("test-ns", "gateways")).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.getIstioResources("gateways", "test-ns");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(((List<Object>) response.getBody()).isEmpty());
    }

    @Test
    void getIstioResources_withInvalidType_returnsBadRequest() {
        when(istioService.getIstioResources("default", "pods"))
                .thenThrow(new IllegalArgumentException("Ungültiger Istio-Ressourcentyp: 'pods'"));

        ResponseEntity<?> response = controller.getIstioResources("pods", "default");

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(body.get("error").toString().contains("pods"));
    }

    @Test
    void getIstioResources_withDifferentNamespaces_callsServiceCorrectly() {
        String[] namespaces = {"default", "kube-system", "istio-system", "production"};

        for (String namespace : namespaces) {
            when(istioService.getIstioResources(namespace, "virtualservices")).thenReturn(Collections.emptyList());

            controller.getIstioResources("virtualservices", namespace);

            verify(istioService).getIstioResources(namespace, "virtualservices");
        }
    }

    // =========================================================================
    // correlateUrl tests
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void correlateUrl_withMatchingResources_returnsCorrelationResult() {
        Map<String, Object> correlationResult = new LinkedHashMap<>();
        correlationResult.put("hasMatch", true);
        correlationResult.put("requestedHost", "my-svc");
        correlationResult.put("requestedPath", "/api");
        correlationResult.put("matchedVirtualServices",
                List.of(Map.of("name", "my-vs", "hostMatch", true)));
        correlationResult.put("matchedDestinationRules", List.of());
        correlationResult.put("matchedServiceEntries", List.of());

        when(istioService.correlateUrl("http://my-svc/api", "default")).thenReturn(correlationResult);

        ResponseEntity<Map<String, Object>> response = controller.correlateUrl("http://my-svc/api", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("hasMatch"));
        List<Object> matchedVs = (List<Object>) response.getBody().get("matchedVirtualServices");
        assertNotNull(matchedVs);
        assertEquals(1, matchedVs.size());
        verify(istioService).correlateUrl("http://my-svc/api", "default");
    }

    @Test
    void correlateUrl_withNoMatch_returnsHasMatchFalse() {
        Map<String, Object> correlationResult = new LinkedHashMap<>();
        correlationResult.put("hasMatch", false);
        correlationResult.put("requestedHost", "unknown-svc");
        correlationResult.put("requestedPath", "/");
        correlationResult.put("matchedVirtualServices", List.of());
        correlationResult.put("matchedDestinationRules", List.of());
        correlationResult.put("matchedServiceEntries", List.of());

        when(istioService.correlateUrl("http://unknown-svc/", "default")).thenReturn(correlationResult);

        ResponseEntity<Map<String, Object>> response = controller.correlateUrl("http://unknown-svc/", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("hasMatch"));
    }

    @Test
    void correlateUrl_withError_returnsOkWithError() {
        Map<String, Object> correlationResult = new HashMap<>();
        correlationResult.put("error", "Malformed URL");

        when(istioService.correlateUrl("not-a-url", "default")).thenReturn(correlationResult);

        ResponseEntity<Map<String, Object>> response = controller.correlateUrl("not-a-url", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        assertEquals("Malformed URL", response.getBody().get("error"));
    }

    // =========================================================================
    // inspectTls tests
    // =========================================================================

    @Test
    void inspectTls_withHttpsUrl_returnsTlsInfo() {
        Map<String, Object> tlsResult = new LinkedHashMap<>();
        tlsResult.put("host", "example.com");
        tlsResult.put("port", 443);
        tlsResult.put("tlsVersion", "TLSv1.3");
        tlsResult.put("cipherSuite", "TLS_AES_256_GCM_SHA384");
        tlsResult.put("isMtls", false);
        tlsResult.put("chain", List.of(
                Map.of("index", 0, "type", "leaf", "subject", "CN=example.com")
        ));

        when(tlsService.inspect("https://example.com")).thenReturn(tlsResult);

        ResponseEntity<Map<String, Object>> response = controller.inspectTls("https://example.com");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("TLSv1.3", response.getBody().get("tlsVersion"));
        assertEquals("TLS_AES_256_GCM_SHA384", response.getBody().get("cipherSuite"));
        assertEquals(false, response.getBody().get("isMtls"));
        assertNotNull(response.getBody().get("chain"));
        verify(tlsService).inspect("https://example.com");
    }

    @Test
    void inspectTls_withHttpUrl_returnsError() {
        Map<String, Object> tlsResult = new LinkedHashMap<>();
        tlsResult.put("error", "Kein HTTPS – TLS-Inspektion nicht möglich.");

        when(tlsService.inspect("http://example.com")).thenReturn(tlsResult);

        ResponseEntity<Map<String, Object>> response = controller.inspectTls("http://example.com");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        assertTrue(response.getBody().get("error").toString().contains("Kein HTTPS"));
    }

    @Test
    void inspectTls_withMtlsCert_returnsSpiffeId() {
        String spiffeId = "spiffe://cluster.local/ns/default/sa/mysa";
        Map<String, Object> tlsResult = new LinkedHashMap<>();
        tlsResult.put("host", "my-svc.default.svc.cluster.local");
        tlsResult.put("port", 443);
        tlsResult.put("tlsVersion", "TLSv1.3");
        tlsResult.put("cipherSuite", "TLS_AES_128_GCM_SHA256");
        tlsResult.put("isMtls", true);
        tlsResult.put("spiffeId", spiffeId);
        tlsResult.put("chain", List.of(
                Map.of("index", 0, "type", "leaf",
                        "subject", "O=cluster.local",
                        "subjectAltNames", List.of("URI:" + spiffeId))
        ));

        when(tlsService.inspect("https://my-svc.default.svc.cluster.local"))
                .thenReturn(tlsResult);

        ResponseEntity<Map<String, Object>> response =
                controller.inspectTls("https://my-svc.default.svc.cluster.local");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("isMtls"));
        assertEquals(spiffeId, response.getBody().get("spiffeId"));
    }
}
