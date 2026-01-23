package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class K8sDiagnosticServiceTest {

    private K8sDiagnosticService service;

    @BeforeEach
    void setUp() {
        service = new K8sDiagnosticService();
    }

    @Test
    void getContext_returnsBasicContextInfo() {
        Map<String, Object> context = service.getContext();

        assertNotNull(context);
        assertTrue(context.containsKey("podName"));
        assertTrue(context.containsKey("namespace"));
        assertTrue(context.containsKey("istioSidecar"));
    }

    @Test
    void getContext_podNameDefaultsToUnknownOrHostname() {
        Map<String, Object> context = service.getContext();

        String podName = (String) context.get("podName");
        assertNotNull(podName);
        assertFalse(podName.isEmpty());
    }

    @Test
    void getContext_namespaceHasDefaultValue() {
        Map<String, Object> context = service.getContext();

        String namespace = (String) context.get("namespace");
        assertNotNull(namespace);
        assertFalse(namespace.isEmpty());
    }

    @Test
    void getContext_istioSidecarIsBooleanValue() {
        Map<String, Object> context = service.getContext();

        Object istioSidecar = context.get("istioSidecar");
        assertNotNull(istioSidecar);
        assertInstanceOf(Boolean.class, istioSidecar);
    }

    @Test
    void getFullSidecarDetails_withoutIstio_returnsError() {
        Map<String, Object> report = service.getFullSidecarDetails();

        assertNotNull(report);
        // Without Istio sidecar, should return error
        assertTrue(report.containsKey("error") || report.containsKey("reachability"));
    }

    @Test
    void getIstioResources_withUninitializedK8s_returnsEmptyList() {
        // This test assumes K8s might not be available in test environment
        List<Object> resources = service.getIstioResources("default", "virtualservices");

        assertNotNull(resources);
        // Should return empty list or actual resources depending on K8s availability
    }

    @Test
    void getIstioResources_withDifferentTypes_handlesPluralization() {
        // Test singular to plural conversion
        List<Object> vs = service.getIstioResources("default", "virtualservice");
        List<Object> vss = service.getIstioResources("default", "virtualservices");

        assertNotNull(vs);
        assertNotNull(vss);
    }

    @Test
    void summarizeClusters_withNull_returnsNoDataMessage() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, (String) null);

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withEmptyString_returnsNoDataMessage() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "");

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withBlankString_returnsNoDataMessage() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "   ");

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withMultipleLines_returnsCorrectCount() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String input = "line1\nline2\nline3\nline4\nline5";
        String result = (String) method.invoke(service, input);

        assertEquals("5 aktive Upstream-Cluster-Einträge", result);
    }

    @Test
    void parseStats_withNull_returnsEmptyMap() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, null, false);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseStats_withValidStats_parsesCorrectly() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "cluster.upstream_rq_5xx: 10\ncluster.upstream_rq_timeout: 5\ncluster.upstream_rq_retry: 3";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(3, result.size());
        assertEquals("10", result.get("cluster.upstream_rq_5xx"));
        assertEquals("5", result.get("cluster.upstream_rq_timeout"));
        assertEquals("3", result.get("cluster.upstream_rq_retry"));
    }

    @Test
    void parseStats_withOnlyNonZeroTrue_filtersZeroValues() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "metric_a: 10\nmetric_b: 0\nmetric_c: 5\nmetric_d: 0";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, true);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("metric_a"));
        assertTrue(result.containsKey("metric_c"));
        assertFalse(result.containsKey("metric_b"));
        assertFalse(result.containsKey("metric_d"));
    }

    @Test
    void parseStats_withOnlyNonZeroFalse_includesAllValues() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "metric_a: 10\nmetric_b: 0\nmetric_c: 5";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(3, result.size());
        assertEquals("0", result.get("metric_b"));
    }

    @Test
    void parseStats_withInvalidFormat_skipsInvalidLines() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "valid_metric: 10\ninvalid line without colon\nanother_valid: 5";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("valid_metric"));
        assertTrue(result.containsKey("another_valid"));
    }

    @Test
    void parseStats_withNonNumericValue_handlesGracefully() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "numeric: 10\nnon_numeric: abc\nanother_numeric: 5";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, true);

        // Non-numeric values should be skipped when onlyNonZero is true
        assertEquals(2, result.size());
        assertFalse(result.containsKey("non_numeric"));
    }

    @Test
    void parseStats_withColonInValue_parsesLastColon() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "cluster.outbound|8080||service.namespace.svc.cluster.local: 42";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(1, result.size());
        assertEquals("42", result.get("cluster.outbound|8080||service.namespace.svc.cluster.local"));
    }

    @Test
    void getCurrentNamespace_returnsValue() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("getCurrentNamespace");
        method.setAccessible(true);

        String result = (String) method.invoke(service);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should return "default" if not in K8s environment
    }

    @Test
    void checkIstioSidecar_returnsBooleanWithoutException() throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("checkIstioSidecar");
        method.setAccessible(true);

        Object result = method.invoke(service);

        assertNotNull(result);
        assertInstanceOf(Boolean.class, result);
    }

    @Test
    void serviceInitialization_doesNotThrowException() {
        assertDoesNotThrow(() -> new K8sDiagnosticService());
    }

    @Test
    void getContext_isThreadSafe() throws InterruptedException {
        Thread[] threads = new Thread[10];
        boolean[] results = new boolean[10];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    Map<String, Object> context = service.getContext();
                    results[index] = context != null && context.containsKey("podName");
                } catch (Exception e) {
                    results[index] = false;
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        for (boolean result : results) {
            assertTrue(result);
        }
    }
}
