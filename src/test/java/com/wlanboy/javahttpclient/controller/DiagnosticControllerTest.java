package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.K8sDiagnosticService;
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
    private K8sDiagnosticService k8sService;

    @InjectMocks
    private DiagnosticController controller;

    @Test
    void getContext_returnsContextFromService() {
        Map<String, Object> context = new HashMap<>();
        context.put("podName", "test-pod-123");
        context.put("namespace", "default");
        context.put("istioSidecar", true);
        when(k8sService.getContext()).thenReturn(context);

        ResponseEntity<Map<String, Object>> response = controller.getContext();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("test-pod-123", response.getBody().get("podName"));
        assertEquals("default", response.getBody().get("namespace"));
        assertEquals(true, response.getBody().get("istioSidecar"));
        verify(k8sService).getContext();
    }

    @Test
    void getContext_withoutIstioSidecar_returnsContextWithoutIstioDetails() {
        Map<String, Object> context = new HashMap<>();
        context.put("podName", "test-pod-456");
        context.put("namespace", "production");
        context.put("istioSidecar", false);
        when(k8sService.getContext()).thenReturn(context);

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
        when(k8sService.getFullSidecarDetails()).thenReturn(report);

        ResponseEntity<Map<String, Object>> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("reachability"));
        assertTrue(response.getBody().containsKey("timestamp"));
        verify(k8sService).getFullSidecarDetails();
    }

    @Test
    void getFullReport_withError_returnsOkWithErrorMessage() {
        Map<String, Object> report = new HashMap<>();
        report.put("error", "Istio Sidecar Proxy ist nicht aktiv");
        when(k8sService.getFullSidecarDetails()).thenReturn(report);

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
        when(k8sService.getFullSidecarDetails()).thenReturn(report);

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
        when(k8sService.getIstioResources("default", "virtualservices")).thenReturn(resources);

        ResponseEntity<?> response = controller.getIstioResources("virtualservices", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, ((List<Object>) response.getBody()).size());
        verify(k8sService).getIstioResources("default", "virtualservices");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_withDestinationRules_returnsResources() {
        List<Object> resources = List.of(
                Map.of("metadata", Map.of("name", "dr-service-a"))
        );
        when(k8sService.getIstioResources("production", "destinationrules")).thenReturn(resources);

        ResponseEntity<?> response = controller.getIstioResources("destinationrules", "production");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, ((List<Object>) response.getBody()).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_withDefaultNamespace_usesDefault() {
        when(k8sService.getIstioResources("default", "virtualservices")).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.getIstioResources("virtualservices", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(((List<Object>) response.getBody()).isEmpty());
        verify(k8sService).getIstioResources("default", "virtualservices");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIstioResources_noResourcesFound_returnsEmptyList() {
        when(k8sService.getIstioResources("test-ns", "gateways")).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.getIstioResources("gateways", "test-ns");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(((List<Object>) response.getBody()).isEmpty());
    }

    @Test
    void getIstioResources_withInvalidType_returnsBadRequest() {
        when(k8sService.getIstioResources("default", "pods"))
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
            when(k8sService.getIstioResources(namespace, "virtualservices")).thenReturn(Collections.emptyList());

            controller.getIstioResources("virtualservices", namespace);

            verify(k8sService).getIstioResources(namespace, "virtualservices");
        }
    }
}
