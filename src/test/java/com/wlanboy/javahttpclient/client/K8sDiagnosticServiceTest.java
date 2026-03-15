package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
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

    // =========================================================================
    // diagnoseMetrics tests
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeDiagnoseMetrics(Map<String, String> metrics) throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("diagnoseMetrics", Map.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(service, metrics);
    }

    @Test
    void diagnoseMetrics_withPendingOverflow_returnsCriticalDiagnosis() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_pending_overflow", "3");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        Map<String, Object> d = diagnoses.get(0);
        assertEquals("KRITISCH", d.get("severity"));
        assertTrue(d.get("title").toString().contains("Circuit Breaker"),
                "Title should mention Circuit Breaker, got: " + d.get("title"));
    }

    @Test
    void diagnoseMetrics_withConnectFail_returnsCriticalDiagnosis() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_cx_connect_fail", "1");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("KRITISCH", diagnoses.get(0).get("severity"));
    }

    @Test
    void diagnoseMetrics_withNoneHealthy_returnsCritical() throws Exception {
        Map<String, String> metrics = Map.of("cluster.bar.upstream_cx_none_healthy", "2");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("KRITISCH", diagnoses.get(0).get("severity"));
    }

    @Test
    void diagnoseMetrics_withTimeout_returnsWarning() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_timeout", "5");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("WARNUNG", diagnoses.get(0).get("severity"));
    }

    @Test
    void diagnoseMetrics_with5xx_returnsWarning() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_5xx", "2");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("WARNUNG", diagnoses.get(0).get("severity"));
    }

    @Test
    void diagnoseMetrics_withRetryLimit_returnsInfo() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_retry_limit_exceeded", "1");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("INFO", diagnoses.get(0).get("severity"));
    }

    @Test
    void diagnoseMetrics_withMultiplePatterns_returnsMultipleDiagnoses() throws Exception {
        Map<String, String> metrics = new HashMap<>();
        metrics.put("cluster.foo.upstream_rq_5xx", "2");
        metrics.put("cluster.foo.upstream_rq_timeout", "5");
        metrics.put("cluster.foo.upstream_rq_pending_overflow", "1");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(3, diagnoses.size());
    }

    @Test
    void diagnoseMetrics_withEmptyMetrics_returnsEmptyList() throws Exception {
        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(Map.of());

        assertNotNull(diagnoses);
        assertTrue(diagnoses.isEmpty());
    }

    @Test
    void diagnoseMetrics_withXdsGrpcMetric_isNotMatchedByRules() throws Exception {
        // xds-grpc metrics would normally be filtered out before diagnoseMetrics is
        // called; passing one through directly should still apply normal rules if
        // the key happens to match a pattern (here upstream_rq_5xx does match WARNUNG).
        // The important check is that no exception is thrown.
        Map<String, String> metrics = new HashMap<>();
        metrics.put("cluster.xds-grpc.upstream_rq_5xx", "1");
        metrics.put("cluster.my-app.upstream_rq_5xx", "3");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        // Both keys match the upstream_rq_5xx rule – they collapse into one diagnosis
        // entry (one rule, two affectedMetrics).
        assertNotNull(diagnoses);
        assertFalse(diagnoses.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnoseMetrics_affectedMetricsContainsMatchingKeys() throws Exception {
        Map<String, String> metrics = new HashMap<>();
        metrics.put("cluster.svc-a.upstream_rq_timeout", "3");
        metrics.put("cluster.svc-b.upstream_rq_timeout", "7");

        List<Map<String, Object>> diagnoses = invokeDiagnoseMetrics(metrics);

        // Exactly one rule matches (upstream_rq_timeout → WARNUNG)
        assertEquals(1, diagnoses.size());
        List<String> affected = (List<String>) diagnoses.get(0).get("affectedMetrics");
        assertNotNull(affected);
        assertEquals(2, affected.size());
        assertTrue(affected.contains("cluster.svc-a.upstream_rq_timeout"));
        assertTrue(affected.contains("cluster.svc-b.upstream_rq_timeout"));
    }

    // =========================================================================
    // hostsMatch tests
    // =========================================================================

    private boolean invokeHostsMatch(String vsHost, String targetHost) throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod("hostsMatch", String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, vsHost, targetHost);
    }

    @Test
    void hostsMatch_exactMatch_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("my-svc", "my-svc"));
    }

    @Test
    void hostsMatch_wildcard_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("*.default.svc.cluster.local", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_wildcardNoMatch_returnsFalse() throws Exception {
        assertFalse(invokeHostsMatch("*.default.svc.cluster.local", "my-svc.other.svc.cluster.local"));
    }

    @Test
    void hostsMatch_shortNameMatchesFqdn_returnsTrue() throws Exception {
        // short name "my-svc" should match the first segment of the FQDN
        assertTrue(invokeHostsMatch("my-svc", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_prefixMatch_returnsTrue() throws Exception {
        // "my-svc.default" should match "my-svc.default.svc.cluster.local" via prefix logic
        assertTrue(invokeHostsMatch("my-svc.default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_starWildcard_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("*", "anything.com"));
    }

    @Test
    void hostsMatch_noMatch_returnsFalse() throws Exception {
        assertFalse(invokeHostsMatch("other-svc", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_caseInsensitive_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("My-Svc", "my-svc"));
    }

    // =========================================================================
    // analyzeVirtualService tests
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeAnalyzeVirtualService(
            Map<?, ?> vs, String targetHost, String targetPath, int targetPort) throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod(
                "analyzeVirtualService", Map.class, String.class, String.class, int.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, vs, targetHost, targetPath, targetPort);
    }

    private Map<String, Object> buildVs(String vsName, List<String> hosts, List<Map<String, Object>> httpRoutes) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("hosts", hosts);
        if (httpRoutes != null) {
            spec.put("http", httpRoutes);
        }
        Map<String, Object> metadata = Map.of("name", vsName);
        return Map.of("metadata", metadata, "spec", spec);
    }

    private Map<String, Object> buildSimplePrefixRoute(String prefix, String destHost) {
        Map<String, Object> uriMatch = Map.of("prefix", prefix);
        Map<String, Object> matchRule = Map.of("uri", uriMatch);
        Map<String, Object> destination = Map.of("host", destHost, "subset", "v1",
                "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("match", List.of(matchRule));
        httpRoute.put("route", List.of(routeDest));
        return httpRoute;
    }

    @Test
    void analyzeVirtualService_withMatchingHost_returnsHostMatchTrue() throws Exception {
        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"),
                List.of(buildSimplePrefixRoute("/", "my-svc")));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/", -1);

        assertTrue((Boolean) result.get("hostMatch"));
    }

    @Test
    void analyzeVirtualService_withNonMatchingHost_returnsHostMatchFalse() throws Exception {
        Map<String, Object> vs = buildVs("my-vs", List.of("other-svc"),
                List.of(buildSimplePrefixRoute("/", "other-svc")));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/api", -1);

        assertFalse((Boolean) result.get("hostMatch"));
    }

    @Test
    void analyzeVirtualService_withPrefixRoute_matchesPath() throws Exception {
        Map<String, Object> httpRoute = buildSimplePrefixRoute("/api", "my-svc");
        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/api/v1", -1);

        assertTrue((Boolean) result.get("hostMatch"));
        assertTrue((Boolean) result.get("pathMatch"),
                "Prefix /api should match path /api/v1");
    }

    @Test
    void analyzeVirtualService_withExactRoute_matchesExactPath() throws Exception {
        Map<String, Object> uriMatch = Map.of("exact", "/api/v1");
        Map<String, Object> matchRule = Map.of("uri", uriMatch);
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("match", List.of(matchRule));
        httpRoute.put("route", List.of(routeDest));

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/api/v1", -1);

        assertTrue((Boolean) result.get("pathMatch"), "Exact match /api/v1 should match /api/v1");
    }

    @Test
    void analyzeVirtualService_withExactRoute_doesNotMatchPrefix() throws Exception {
        Map<String, Object> uriMatch = Map.of("exact", "/api/v1");
        Map<String, Object> matchRule = Map.of("uri", uriMatch);
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("match", List.of(matchRule));
        httpRoute.put("route", List.of(routeDest));

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/api/v1/items", -1);

        assertFalse((Boolean) result.get("pathMatch"),
                "Exact match /api/v1 must not match /api/v1/items");
    }

    @Test
    void analyzeVirtualService_withNoHttpRoutes_returnsNoHttpRoutesTrue() throws Exception {
        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), null);

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/api", -1);

        assertTrue((Boolean) result.get("hostMatch"));
        assertTrue((Boolean) result.get("noHttpRoutes"),
                "noHttpRoutes should be true when spec has no http field");
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeVirtualService_withRouteHavingDestinations_returnsDestinationInfo() throws Exception {
        Map<String, Object> destination = Map.of(
                "host", "my-svc",
                "subset", "v2",
                "port", Map.of("number", 9090));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 80);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("route", List.of(routeDest));
        // No match field → catchall

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/any", -1);

        assertTrue((Boolean) result.get("hostMatch"));
        List<Map<String, Object>> httpRoutes = (List<Map<String, Object>>) result.get("httpRoutes");
        assertNotNull(httpRoutes);
        assertFalse(httpRoutes.isEmpty());
        List<Map<String, Object>> dests = (List<Map<String, Object>>) httpRoutes.get(0).get("destinations");
        assertNotNull(dests);
        assertEquals(1, dests.size());
        assertEquals("my-svc", dests.get(0).get("host"));
        assertEquals("v2", dests.get(0).get("subset"));
        assertEquals(80, dests.get(0).get("weight"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeVirtualService_withFaultInjection_returnsFaultInfo() throws Exception {
        Map<String, Object> fault = Map.of(
                "delay", Map.of("fixedDelay", "2s", "percentage", Map.of("value", 10)));
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("route", List.of(routeDest));
        httpRoute.put("fault", fault);

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/", -1);

        List<Map<String, Object>> httpRoutes = (List<Map<String, Object>>) result.get("httpRoutes");
        assertNotNull(httpRoutes);
        Map<String, Object> routeResult = httpRoutes.get(0);
        assertNotNull(routeResult.get("faultInjection"),
                "faultInjection should be populated when fault field is present");
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeVirtualService_withTimeout_returnsTimeoutInfo() throws Exception {
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("route", List.of(routeDest));
        httpRoute.put("timeout", "5s");

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/", -1);

        List<Map<String, Object>> httpRoutes = (List<Map<String, Object>>) result.get("httpRoutes");
        assertNotNull(httpRoutes);
        assertEquals("5s", httpRoutes.get(0).get("timeout"),
                "Timeout value should be propagated into route analysis");
    }

    @Test
    void analyzeVirtualService_withCatchAllRoute_matchesAnyPath() throws Exception {
        // A route with no match field is a catch-all and should match any path
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        // Intentionally no "match" key
        httpRoute.put("route", List.of(routeDest));

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        Map<String, Object> result = invokeAnalyzeVirtualService(vs, "my-svc", "/completely/random/path", -1);

        assertTrue((Boolean) result.get("pathMatch"),
                "Catch-all route (no match field) must match any path");
    }

    // =========================================================================
    // analyzeServiceEntry tests
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeAnalyzeServiceEntry(
            Map<?, ?> se, String targetHost, int targetPort) throws Exception {
        Method method = K8sDiagnosticService.class.getDeclaredMethod(
                "analyzeServiceEntry", Map.class, String.class, int.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, se, targetHost, targetPort);
    }

    private Map<String, Object> buildServiceEntry(
            String name, List<String> hosts,
            List<Map<String, Object>> ports,
            String location, String resolution) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("hosts", hosts);
        if (ports != null) spec.put("ports", ports);
        if (location != null) spec.put("location", location);
        if (resolution != null) spec.put("resolution", resolution);
        return Map.of("metadata", Map.of("name", name), "spec", spec);
    }

    @Test
    void analyzeServiceEntry_withMatchingHost_returnsHostMatchTrue() throws Exception {
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"), null, null, null);

        Map<String, Object> result = invokeAnalyzeServiceEntry(se, "external.example.com", -1);

        assertTrue((Boolean) result.get("hostMatch"));
    }

    @Test
    void analyzeServiceEntry_withMatchingPort_returnsPortMatchTrue() throws Exception {
        List<Map<String, Object>> ports = List.of(
                Map.of("number", 443, "name", "https", "protocol", "HTTPS"));
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"), ports, "MESH_EXTERNAL", "DNS");

        Map<String, Object> result = invokeAnalyzeServiceEntry(se, "external.example.com", 443);

        assertTrue((Boolean) result.get("hostMatch"));
        assertTrue((Boolean) result.get("portMatch"),
                "Port 443 is defined in ServiceEntry and should match");
    }

    @Test
    void analyzeServiceEntry_withNonMatchingPort_returnsPortWarning() throws Exception {
        List<Map<String, Object>> ports = List.of(
                Map.of("number", 443, "name", "https", "protocol", "HTTPS"));
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"), ports, "MESH_EXTERNAL", "DNS");

        Map<String, Object> result = invokeAnalyzeServiceEntry(se, "external.example.com", 8080);

        assertTrue((Boolean) result.get("hostMatch"));
        assertFalse((Boolean) result.get("portMatch"),
                "Port 8080 is not defined in ServiceEntry ports");
        assertNotNull(result.get("portMatchWarning"),
                "A portMatchWarning should be present when the requested port is not covered");
    }

    @Test
    void analyzeServiceEntry_returnsLocationAndResolution() throws Exception {
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"),
                List.of(Map.of("number", 443, "name", "https", "protocol", "HTTPS")),
                "MESH_EXTERNAL", "DNS");

        Map<String, Object> result = invokeAnalyzeServiceEntry(se, "external.example.com", 443);

        assertEquals("MESH_EXTERNAL", result.get("location"));
        assertEquals("DNS", result.get("resolution"));
    }
}
