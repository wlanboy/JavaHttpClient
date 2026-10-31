package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.IstioDiagnosticService;
import com.wlanboy.javahttpclient.client.TlsInspectorService;
import com.wlanboy.javahttpclient.client.dto.ErrorResponse;
import com.wlanboy.javahttpclient.client.dto.K8sContext;
import com.wlanboy.javahttpclient.client.dto.SidecarReport;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.HealthDiagnostics;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.Reachability;
import com.wlanboy.javahttpclient.client.dto.TlsInspection;
import com.wlanboy.javahttpclient.client.dto.TlsInspection.CertificateInfo;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.VirtualServiceMatch;
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
        K8sContext context = new K8sContext("test-pod-123", "default", true, null);
        when(istioService.getContext()).thenReturn(context);

        ResponseEntity<K8sContext> response = controller.getContext();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("test-pod-123", response.getBody().podName());
        assertEquals("default", response.getBody().namespace());
        assertTrue(response.getBody().istioSidecar());
        verify(istioService).getContext();
    }

    @Test
    void getContext_withoutIstioSidecar_returnsContextWithoutIstioDetails() {
        K8sContext context = new K8sContext("test-pod-456", "production", false, null);
        when(istioService.getContext()).thenReturn(context);

        ResponseEntity<K8sContext> response = controller.getContext();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().istioSidecar());
        assertNull(response.getBody().istioDetails());
    }

    @Test
    void getFullReport_returnsReportFromService() {
        SidecarReport report = new SidecarReport(
                new Reachability(Map.of(), "", "10 aktive Upstream-Cluster-Einträge"),
                null,
                "2026-01-01T00:00:00Z",
                null);
        when(istioService.getFullSidecarDetails()).thenReturn(report);

        ResponseEntity<SidecarReport> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().reachability());
        assertNotNull(response.getBody().timestamp());
        verify(istioService).getFullSidecarDetails();
    }

    @Test
    void getFullReport_withError_returnsOkWithErrorMessage() {
        when(istioService.getFullSidecarDetails())
                .thenReturn(SidecarReport.error("Istio Sidecar Proxy ist nicht aktiv"));

        ResponseEntity<SidecarReport> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Istio Sidecar Proxy ist nicht aktiv", response.getBody().error());
    }

    @Test
    void getFullReport_withHealthDiagnostics_returnsErrorMetrics() {
        HealthDiagnostics health = new HealthDiagnostics(
                Map.of("cluster.outbound.upstream_rq_5xx", "5"), 1, List.of());
        when(istioService.getFullSidecarDetails()).thenReturn(new SidecarReport(null, health, null, null));

        ResponseEntity<SidecarReport> response = controller.getFullReport();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().healthDiagnostics().errorCount());
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
        ErrorResponse body = assertInstanceOf(ErrorResponse.class, response.getBody());
        assertTrue(body.error().contains("pods"));
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

    private static UrlCorrelation correlation(String host, String path, List<VirtualServiceMatch> matchedVs, boolean hasMatch) {
        return new UrlCorrelation(host, path, "default", "default", List.of("default"),
                matchedVs, List.of(), List.of(), null, List.of(), null, hasMatch, null);
    }

    @Test
    void correlateUrl_withMatchingResources_returnsCorrelationResult() {
        VirtualServiceMatch vs = new VirtualServiceMatch("my-vs", true, List.of("my-svc"), null, null, null, "default");
        when(istioService.correlateUrl("http://my-svc/api", "default"))
                .thenReturn(correlation("my-svc", "/api", List.of(vs), true));

        ResponseEntity<UrlCorrelation> response = controller.correlateUrl("http://my-svc/api", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().hasMatch());
        assertEquals(1, response.getBody().matchedVirtualServices().size());
        verify(istioService).correlateUrl("http://my-svc/api", "default");
    }

    @Test
    void correlateUrl_withNoMatch_returnsHasMatchFalse() {
        when(istioService.correlateUrl("http://unknown-svc/", "default"))
                .thenReturn(correlation("unknown-svc", "/", List.of(), false));

        ResponseEntity<UrlCorrelation> response = controller.correlateUrl("http://unknown-svc/", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().hasMatch());
    }

    @Test
    void correlateUrl_withError_returnsOkWithError() {
        when(istioService.correlateUrl("not-a-url", "default")).thenReturn(UrlCorrelation.error("Malformed URL"));

        ResponseEntity<UrlCorrelation> response = controller.correlateUrl("not-a-url", "default");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Malformed URL", response.getBody().error());
    }

    // =========================================================================
    // inspectTls tests
    // =========================================================================

    private static CertificateInfo leafCert(String subject, List<String> sans) {
        return new CertificateInfo(0, "leaf", subject, "CN=issuer", "01", "2026-01-01 00:00:00",
                "2027-01-01 00:00:00", 365, false, sans);
    }

    @Test
    void inspectTls_withHttpsUrl_returnsTlsInfo() {
        TlsInspection tlsResult = new TlsInspection("example.com", 443, "TLSv1.3", "TLS_AES_256_GCM_SHA384",
                false, null, List.of(leafCert("CN=example.com", null)), null);
        when(tlsService.inspect("https://example.com")).thenReturn(tlsResult);

        ResponseEntity<TlsInspection> response = controller.inspectTls("https://example.com");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("TLSv1.3", response.getBody().tlsVersion());
        assertEquals("TLS_AES_256_GCM_SHA384", response.getBody().cipherSuite());
        assertFalse(response.getBody().isMtls());
        assertNotNull(response.getBody().chain());
        verify(tlsService).inspect("https://example.com");
    }

    @Test
    void inspectTls_withHttpUrl_returnsError() {
        when(tlsService.inspect("http://example.com"))
                .thenReturn(TlsInspection.error(null, null, "Kein HTTPS – TLS-Inspektion nicht möglich."));

        ResponseEntity<TlsInspection> response = controller.inspectTls("http://example.com");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().error().contains("Kein HTTPS"));
    }

    @Test
    void inspectTls_withMtlsCert_returnsSpiffeId() {
        String spiffeId = "spiffe://cluster.local/ns/default/sa/mysa";
        TlsInspection tlsResult = new TlsInspection("my-svc.default.svc.cluster.local", 443, "TLSv1.3",
                "TLS_AES_128_GCM_SHA256", true, spiffeId,
                List.of(leafCert("O=cluster.local", List.of("URI:" + spiffeId))), null);
        when(tlsService.inspect("https://my-svc.default.svc.cluster.local")).thenReturn(tlsResult);

        ResponseEntity<TlsInspection> response =
                controller.inspectTls("https://my-svc.default.svc.cluster.local");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isMtls());
        assertEquals(spiffeId, response.getBody().spiffeId());
    }
}
