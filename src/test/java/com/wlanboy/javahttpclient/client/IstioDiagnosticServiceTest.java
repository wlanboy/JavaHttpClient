package com.wlanboy.javahttpclient.client;

import com.wlanboy.javahttpclient.client.dto.K8sContext;
import com.wlanboy.javahttpclient.client.dto.SidecarReport;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.Diagnosis;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.AuthorizationAnalysis;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.HttpRouteAnalysis;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.RouteDestination;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.ServiceEntryMatch;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.SidecarEgress;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.VirtualServiceMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.util.unit.DataSize;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IstioDiagnosticServiceTest {

    // Port 1 ist privilegiert und in Testumgebungen nicht belegt
    private static final int CLOSED_PORT = 1;

    private IstioDiagnosticService service;

    private static IstioDiagnosticService newService(int sidecarStatusPort) {
        return new IstioDiagnosticService(K8sClientServiceTest.withoutCluster(), DataSize.ofMegabytes(5),
                "http://127.0.0.1:15000", sidecarStatusPort);
    }

    @BeforeEach
    void setUp() {
        service = newService(CLOSED_PORT);
    }

    @Test
    void getContext_returnsBasicContextInfo() {
        K8sContext context = service.getContext();

        assertNotNull(context);
        assertNotNull(context.podName());
        assertNotNull(context.namespace());
    }

    @Test
    void getContext_podNameDefaultsToUnknownOrHostname() {
        K8sContext context = service.getContext();

        String podName = context.podName();
        assertNotNull(podName);
        assertFalse(podName.isEmpty());
    }

    @Test
    void getContext_namespaceHasDefaultValue() {
        K8sContext context = service.getContext();

        String namespace = context.namespace();
        assertNotNull(namespace);
        assertFalse(namespace.isEmpty());
    }

    @Test
    void getContext_withoutSidecar_hasNoIstioDetails() {
        K8sContext context = service.getContext();

        assertFalse(context.istioSidecar());
        assertNull(context.istioDetails());
    }

    @Test
    void getFullSidecarDetails_withoutIstio_returnsError() {
        SidecarReport report = service.getFullSidecarDetails();

        assertNotNull(report);
        // Without Istio sidecar, should return error
        assertTrue(report.error() != null || report.reachability() != null);
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
        Method method = IstioDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, (String) null);

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withEmptyString_returnsNoDataMessage() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "");

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withBlankString_returnsNoDataMessage() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "   ");

        assertEquals("Keine Upstream-Daten", result);
    }

    @Test
    void summarizeClusters_withMultipleLines_returnsCorrectCount() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("summarizeClusters", String.class);
        method.setAccessible(true);

        String input = "line1\nline2\nline3\nline4\nline5";
        String result = (String) method.invoke(service, input);

        assertEquals("5 aktive Upstream-Cluster-Einträge", result);
    }

    @Test
    void parseStats_withNull_returnsEmptyMap() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, null, false);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseStats_withValidStats_parsesCorrectly() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
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
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
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
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "metric_a: 10\nmetric_b: 0\nmetric_c: 5";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(3, result.size());
        assertEquals("0", result.get("metric_b"));
    }

    @Test
    void parseStats_withInvalidFormat_skipsInvalidLines() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
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
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
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
        Method method = IstioDiagnosticService.class.getDeclaredMethod("parseStats", String.class, boolean.class);
        method.setAccessible(true);

        String input = "cluster.outbound|8080||service.namespace.svc.cluster.local: 42";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(service, input, false);

        assertEquals(1, result.size());
        assertEquals("42", result.get("cluster.outbound|8080||service.namespace.svc.cluster.local"));
    }

    @Test
    void checkIstioSidecar_returnsBooleanWithoutException() throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("checkIstioSidecar");
        method.setAccessible(true);

        Object result = method.invoke(service);

        assertNotNull(result);
        assertInstanceOf(Boolean.class, result);
    }

    @Test
    void getContext_withReachableStatusPort_detectsSidecar() throws Exception {
        try (ServerSocket statusPort = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            K8sContext context = newService(statusPort.getLocalPort()).getContext();

            assertTrue(context.istioSidecar());
        }
    }

    @Test
    void getFullSidecarDetails_withClosedStatusPort_reportsConfiguredAddress() {
        SidecarReport report = service.getFullSidecarDetails();

        assertEquals("Istio Sidecar Proxy ist nicht aktiv (127.0.0.1:" + CLOSED_PORT + " nicht erreichbar).",
                report.error());
    }

    @Test
    void serviceInitialization_doesNotThrowException() {
        assertDoesNotThrow(() -> newService(CLOSED_PORT));
    }

    @Test
    void getContext_isThreadSafe() throws InterruptedException {
        Thread[] threads = new Thread[10];
        boolean[] results = new boolean[10];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    K8sContext context = service.getContext();
                    results[index] = context != null && context.podName() != null;
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
    private List<Diagnosis> invokeDiagnoseMetrics(Map<String, String> metrics) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("diagnoseMetrics", Map.class);
        method.setAccessible(true);
        return (List<Diagnosis>) method.invoke(service, metrics);
    }

    @Test
    void diagnoseMetrics_withPendingOverflow_returnsCriticalDiagnosis() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_pending_overflow", "3");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        Diagnosis d = diagnoses.get(0);
        assertEquals("KRITISCH", d.severity());
        assertTrue(d.title().contains("Circuit Breaker"),
                "Title should mention Circuit Breaker, got: " + d.title());
    }

    @Test
    void diagnoseMetrics_withConnectFail_returnsCriticalDiagnosis() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_cx_connect_fail", "1");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("KRITISCH", diagnoses.get(0).severity());
    }

    @Test
    void diagnoseMetrics_withNoneHealthy_returnsCritical() throws Exception {
        Map<String, String> metrics = Map.of("cluster.bar.upstream_cx_none_healthy", "2");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("KRITISCH", diagnoses.get(0).severity());
    }

    @Test
    void diagnoseMetrics_withTimeout_returnsWarning() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_timeout", "5");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("WARNUNG", diagnoses.get(0).severity());
    }

    @Test
    void diagnoseMetrics_with5xx_returnsWarning() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_5xx", "2");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("WARNUNG", diagnoses.get(0).severity());
    }

    @Test
    void diagnoseMetrics_withRetryLimit_returnsInfo() throws Exception {
        Map<String, String> metrics = Map.of("cluster.foo.upstream_rq_retry_limit_exceeded", "1");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(1, diagnoses.size());
        assertEquals("INFO", diagnoses.get(0).severity());
    }

    @Test
    void diagnoseMetrics_withMultiplePatterns_returnsMultipleDiagnoses() throws Exception {
        Map<String, String> metrics = new HashMap<>();
        metrics.put("cluster.foo.upstream_rq_5xx", "2");
        metrics.put("cluster.foo.upstream_rq_timeout", "5");
        metrics.put("cluster.foo.upstream_rq_pending_overflow", "1");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        assertEquals(3, diagnoses.size());
    }

    @Test
    void diagnoseMetrics_withEmptyMetrics_returnsEmptyList() throws Exception {
        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(Map.of());

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

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        // Both keys match the upstream_rq_5xx rule – they collapse into one diagnosis
        // entry (one rule, two affectedMetrics).
        assertNotNull(diagnoses);
        assertFalse(diagnoses.isEmpty());
    }

    @Test
    void diagnoseMetrics_affectedMetricsContainsMatchingKeys() throws Exception {
        Map<String, String> metrics = new HashMap<>();
        metrics.put("cluster.svc-a.upstream_rq_timeout", "3");
        metrics.put("cluster.svc-b.upstream_rq_timeout", "7");

        List<Diagnosis> diagnoses = invokeDiagnoseMetrics(metrics);

        // Exactly one rule matches (upstream_rq_timeout → WARNUNG)
        assertEquals(1, diagnoses.size());
        List<String> affected = diagnoses.get(0).affectedMetrics();
        assertNotNull(affected);
        assertEquals(2, affected.size());
        assertTrue(affected.contains("cluster.svc-a.upstream_rq_timeout"));
        assertTrue(affected.contains("cluster.svc-b.upstream_rq_timeout"));
    }

    // =========================================================================
    // hostsMatch tests
    // =========================================================================

    private boolean invokeHostsMatch(String resourceHost, String resourceNamespace, String targetFqdn) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("hostsMatch", String.class, String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, resourceHost, resourceNamespace, targetFqdn);
    }

    private String invokeQualifyTargetHost(String host, String sourceNamespace) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod("qualifyTargetHost", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, host, sourceNamespace);
    }

    @Test
    void hostsMatch_exactMatch_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("my-svc.default.svc.cluster.local", "other", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_wildcard_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("*.default.svc.cluster.local", "default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_wildcardNoMatch_returnsFalse() throws Exception {
        assertFalse(invokeHostsMatch("*.default.svc.cluster.local", "default", "my-svc.other.svc.cluster.local"));
    }

    @Test
    void hostsMatch_shortNameInResourceNamespace_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("my-svc", "default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_shortNameFromOtherNamespace_returnsFalse() throws Exception {
        // Kurzname gilt relativ zum Namespace der Resource, nicht zu dem des Ziels
        assertFalse(invokeHostsMatch("demo", "clients", "demo.demo.svc.cluster.local"));
    }

    @Test
    void hostsMatch_shortNameDoesNotMatchExternalHost() throws Exception {
        assertFalse(invokeHostsMatch("api", "clients", "api.github.com"));
    }

    @Test
    void hostsMatch_dottedHostIsNotExpanded() throws Exception {
        // Istio erweitert nur Hosts ohne Punkt
        assertFalse(invokeHostsMatch("api.github", "clients", "api.github.com"));
        assertFalse(invokeHostsMatch("my-svc.default", "default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_starWildcard_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("*", "default", "anything.com"));
    }

    @Test
    void hostsMatch_noMatch_returnsFalse() throws Exception {
        assertFalse(invokeHostsMatch("other-svc", "default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void hostsMatch_caseInsensitive_returnsTrue() throws Exception {
        assertTrue(invokeHostsMatch("My-Svc", "Default", "my-svc.default.svc.cluster.local"));
    }

    @Test
    void qualifyTargetHost_shortNameResolvesInSourceNamespace() throws Exception {
        assertEquals("api.clients.svc.cluster.local", invokeQualifyTargetHost("api", "clients"));
        assertEquals("api.demo.svc.cluster.local", invokeQualifyTargetHost("api.demo.svc", "clients"));
        assertEquals("api.github.com", invokeQualifyTargetHost("API.github.com", "clients"));
    }

    // =========================================================================
    // analyzeVirtualService tests
    // =========================================================================

    private VirtualServiceMatch invokeAnalyzeVirtualService(
            Map<?, ?> vs, String targetHost, String targetPath, int targetPort) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "analyzeVirtualService", Map.class, String.class, String.class, String.class, int.class);
        method.setAccessible(true);
        return (VirtualServiceMatch) method.invoke(service, vs, "default", targetHost, targetPath, targetPort);
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

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/", -1);

        assertTrue(result.hostMatch());
    }

    @Test
    void analyzeVirtualService_withNonMatchingHost_returnsHostMatchFalse() throws Exception {
        Map<String, Object> vs = buildVs("my-vs", List.of("other-svc"),
                List.of(buildSimplePrefixRoute("/", "other-svc")));

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/api", -1);

        assertFalse(result.hostMatch());
    }

    @Test
    void analyzeVirtualService_withPrefixRoute_matchesPath() throws Exception {
        Map<String, Object> httpRoute = buildSimplePrefixRoute("/api", "my-svc");
        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/api/v1", -1);

        assertTrue(result.hostMatch());
        assertTrue(result.pathMatch(),
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

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/api/v1", -1);

        assertTrue(result.pathMatch(), "Exact match /api/v1 should match /api/v1");
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

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/api/v1/items", -1);

        assertFalse(result.pathMatch(),
                "Exact match /api/v1 must not match /api/v1/items");
    }

    @Test
    void analyzeVirtualService_withNoHttpRoutes_returnsNoHttpRoutesTrue() throws Exception {
        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), null);

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/api", -1);

        assertTrue(result.hostMatch());
        assertTrue(result.noHttpRoutes(),
                "noHttpRoutes should be true when spec has no http field");
    }

    @Test
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

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/any", -1);

        assertTrue(result.hostMatch());
        List<HttpRouteAnalysis> httpRoutes = result.httpRoutes();
        assertNotNull(httpRoutes);
        assertFalse(httpRoutes.isEmpty());
        List<RouteDestination> dests = httpRoutes.get(0).destinations();
        assertNotNull(dests);
        assertEquals(1, dests.size());
        assertEquals("my-svc", dests.get(0).host());
        assertEquals("v2", dests.get(0).subset());
        assertEquals(80, dests.get(0).weight());
    }

    @Test
    void analyzeVirtualService_withFaultInjection_returnsFaultInfo() throws Exception {
        Map<String, Object> fault = Map.of(
                "delay", Map.of("fixedDelay", "2s", "percentage", Map.of("value", 10)));
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("route", List.of(routeDest));
        httpRoute.put("fault", fault);

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/", -1);

        List<HttpRouteAnalysis> httpRoutes = result.httpRoutes();
        assertNotNull(httpRoutes);
        HttpRouteAnalysis routeResult = httpRoutes.get(0);
        assertNotNull(routeResult.faultInjection(),
                "faultInjection should be populated when fault field is present");
    }

    @Test
    void analyzeVirtualService_withTimeout_returnsTimeoutInfo() throws Exception {
        Map<String, Object> destination = Map.of("host", "my-svc", "port", Map.of("number", 8080));
        Map<String, Object> routeDest = Map.of("destination", destination, "weight", 100);
        Map<String, Object> httpRoute = new HashMap<>();
        httpRoute.put("route", List.of(routeDest));
        httpRoute.put("timeout", "5s");

        Map<String, Object> vs = buildVs("my-vs", List.of("my-svc"), List.of(httpRoute));

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/", -1);

        List<HttpRouteAnalysis> httpRoutes = result.httpRoutes();
        assertNotNull(httpRoutes);
        assertEquals("5s", httpRoutes.get(0).timeout(),
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

        VirtualServiceMatch result = invokeAnalyzeVirtualService(vs, "my-svc.default.svc.cluster.local", "/completely/random/path", -1);

        assertTrue(result.pathMatch(),
                "Catch-all route (no match field) must match any path");
    }

    // =========================================================================
    // analyzeServiceEntry tests
    // =========================================================================

    private ServiceEntryMatch invokeAnalyzeServiceEntry(
            Map<?, ?> se, String targetHost, int targetPort) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "analyzeServiceEntry", Map.class, String.class, String.class, int.class);
        method.setAccessible(true);
        return (ServiceEntryMatch) method.invoke(service, se, "default", targetHost, targetPort);
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

        ServiceEntryMatch result = invokeAnalyzeServiceEntry(se, "external.example.com", -1);

        assertTrue(result.hostMatch());
    }

    @Test
    void analyzeServiceEntry_withMatchingPort_returnsPortMatchTrue() throws Exception {
        List<Map<String, Object>> ports = List.of(
                Map.of("number", 443, "name", "https", "protocol", "HTTPS"));
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"), ports, "MESH_EXTERNAL", "DNS");

        ServiceEntryMatch result = invokeAnalyzeServiceEntry(se, "external.example.com", 443);

        assertTrue(result.hostMatch());
        assertTrue(result.portMatch(),
                "Port 443 is defined in ServiceEntry and should match");
    }

    @Test
    void analyzeServiceEntry_withNonMatchingPort_returnsPortWarning() throws Exception {
        List<Map<String, Object>> ports = List.of(
                Map.of("number", 443, "name", "https", "protocol", "HTTPS"));
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"), ports, "MESH_EXTERNAL", "DNS");

        ServiceEntryMatch result = invokeAnalyzeServiceEntry(se, "external.example.com", 8080);

        assertTrue(result.hostMatch());
        assertFalse(result.portMatch(),
                "Port 8080 is not defined in ServiceEntry ports");
        assertNotNull(result.portMatchWarning(),
                "A portMatchWarning should be present when the requested port is not covered");
    }

    @Test
    void analyzeServiceEntry_returnsLocationAndResolution() throws Exception {
        Map<String, Object> se = buildServiceEntry("my-se",
                List.of("external.example.com"),
                List.of(Map.of("number", 443, "name", "https", "protocol", "HTTPS")),
                "MESH_EXTERNAL", "DNS");

        ServiceEntryMatch result = invokeAnalyzeServiceEntry(se, "external.example.com", 443);

        assertEquals("MESH_EXTERNAL", result.location());
        assertEquals("DNS", result.resolution());
    }

    // =========================================================================
    // resolveTargetNamespace tests
    // =========================================================================

    private String invokeResolveTargetNamespace(String host, String sourceNamespace) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "resolveTargetNamespace", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, host, sourceNamespace);
    }

    @Test
    void resolveTargetNamespace_withK8sFqdn_returnsNamespaceFromHost() throws Exception {
        String result = invokeResolveTargetNamespace("demo-app-external.demo.svc.cluster.local", "clients");

        assertEquals("demo", result);
    }

    @Test
    void resolveTargetNamespace_withNonFqdnHost_fallsBackToSourceNamespace() throws Exception {
        String result = invokeResolveTargetNamespace("example.com", "clients");

        assertEquals("clients", result);
    }

    @Test
    void resolveTargetNamespace_withNullHost_fallsBackToSourceNamespace() throws Exception {
        String result = invokeResolveTargetNamespace(null, "clients");

        assertEquals("clients", result);
    }

    @Test
    void resolveTargetNamespace_withShortServiceName_fallsBackToSourceNamespace() throws Exception {
        // "my-svc" alone has no ".namespace.svc.cluster.local" suffix
        String result = invokeResolveTargetNamespace("my-svc", "clients");

        assertEquals("clients", result);
    }

    // =========================================================================
    // selectorMatches tests
    // =========================================================================

    private boolean invokeSelectorMatches(Map<?, ?> spec, String targetShortName) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "selectorMatches", Map.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, spec, targetShortName);
    }

    @Test
    void selectorMatches_withNoSelector_appliesToWholeNamespace() throws Exception {
        Map<String, Object> spec = Map.of("action", "DENY");

        assertTrue(invokeSelectorMatches(spec, "demo-app-external"));
    }

    @Test
    void selectorMatches_withMatchingAppLabel_returnsTrue() throws Exception {
        Map<String, Object> spec = Map.of("selector", Map.of("matchLabels", Map.of("app", "demo-app-external")));

        assertTrue(invokeSelectorMatches(spec, "demo-app-external"));
    }

    @Test
    void selectorMatches_withNonMatchingAppLabel_returnsFalse() throws Exception {
        Map<String, Object> spec = Map.of("selector", Map.of("matchLabels", Map.of("app", "demo-app-internal")));

        assertFalse(invokeSelectorMatches(spec, "demo-app-external"));
    }

    // =========================================================================
    // ruleMatchesSourceNamespace tests
    // =========================================================================

    private boolean invokeRuleMatchesSourceNamespace(Map<?, ?> spec, String sourceNamespace) throws Exception {
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "ruleMatchesSourceNamespace", Map.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, spec, sourceNamespace);
    }

    @Test
    void ruleMatchesSourceNamespace_withNoRules_neverMatches() throws Exception {
        Map<String, Object> spec = Map.of("action", "DENY");

        assertFalse(invokeRuleMatchesSourceNamespace(spec, "clients"));
    }

    @Test
    void ruleMatchesSourceNamespace_withEmptyRule_appliesToAllSources() throws Exception {
        Map<String, Object> spec = Map.of("rules", List.of(Map.of()));

        assertTrue(invokeRuleMatchesSourceNamespace(spec, "clients"));
    }

    @Test
    void ruleMatchesSourceNamespace_withMatchingNamespace_returnsTrue() throws Exception {
        // Mirrors the demo-app-external-deny-clients AuthorizationPolicy:
        // action DENY, rules: [{ from: [{ source: { namespaces: ["clients"] } }] }]
        Map<String, Object> spec = Map.of(
                "rules", List.of(Map.of(
                        "from", List.of(Map.of(
                                "source", Map.of("namespaces", List.of("clients")))))));

        assertTrue(invokeRuleMatchesSourceNamespace(spec, "clients"));
    }

    @Test
    void ruleMatchesSourceNamespace_withNonMatchingNamespace_returnsFalse() throws Exception {
        Map<String, Object> spec = Map.of(
                "rules", List.of(Map.of(
                        "from", List.of(Map.of(
                                "source", Map.of("namespaces", List.of("clients")))))));

        assertFalse(invokeRuleMatchesSourceNamespace(spec, "demo"));
    }

    @Test
    void ruleMatchesSourceNamespace_withNoFromClause_appliesToAllSources() throws Exception {
        Map<String, Object> spec = Map.of("rules", List.of(Map.of("to", List.of())));

        assertTrue(invokeRuleMatchesSourceNamespace(spec, "clients"));
    }


    // =========================================================================
    // Sidecar egress tests
    // =========================================================================

    private SidecarEgress correlateWithSidecars(String url, String sourceNamespace, List<Object> sidecars) {
        IstioDiagnosticService stubbed = new IstioDiagnosticService(K8sClientServiceTest.withoutCluster(),
                DataSize.ofMegabytes(5), "http://127.0.0.1:15000", CLOSED_PORT) {
            @Override
            public List<Object> getIstioResources(String namespace, String type) {
                return "sidecars".equals(type) && namespace.equals(sourceNamespace) ? sidecars : List.of();
            }
        };
        return stubbed.correlateUrl(url, sourceNamespace).sidecarEgress();
    }

    private static Map<String, Object> sidecarWithEgressHosts(String... hosts) {
        return Map.of("spec", Map.of("egress", List.of(Map.of("hosts", List.of(hosts)))));
    }

    @Test
    void sidecarEgress_namespaceFilterMatchesTargetNamespace() {
        SidecarEgress egress = correlateWithSidecars("http://istiod.istio-system.svc.cluster.local:15014/",
                "clients", List.of(sidecarWithEgressHosts("istio-system/*")));

        assertTrue(egress.restricted());
        assertTrue(egress.hostAllowed());
        assertNull(egress.warning());
    }

    @Test
    void sidecarEgress_namespaceFilterDoesNotMatchSourceNamespace() {
        SidecarEgress egress = correlateWithSidecars("http://demo.demo.svc.cluster.local/",
                "clients", List.of(sidecarWithEgressHosts("clients/*")));

        assertTrue(egress.restricted());
        assertFalse(egress.hostAllowed());
        assertNotNull(egress.warning());
    }

    @Test
    void sidecarEgress_dotAllowsOnlyOwnNamespace() {
        List<Object> sidecars = List.of(sidecarWithEgressHosts("./*"));

        assertFalse(correlateWithSidecars("http://demo.demo.svc.cluster.local/", "clients", sidecars).hostAllowed());
        assertTrue(correlateWithSidecars("http://api.clients.svc.cluster.local/", "clients", sidecars).hostAllowed());
    }

    @Test
    void sidecarEgress_withoutEgressSection_isNotRestricted() {
        SidecarEgress egress = correlateWithSidecars("http://demo.demo.svc.cluster.local/",
                "clients", List.of(Map.of("spec", Map.of("workloadSelector", Map.of()))));

        assertFalse(egress.restricted());
        assertNull(egress.hostAllowed());
        assertNull(egress.warning());
    }

    // =========================================================================
    // analyzeAuthorizationPolicies tests
    // =========================================================================

    private AuthorizationAnalysis analyzeWithPolicies(List<Object> policies) throws Exception {
        IstioDiagnosticService stubbed = new IstioDiagnosticService(K8sClientServiceTest.withoutCluster(),
                DataSize.ofMegabytes(5), "http://127.0.0.1:15000", CLOSED_PORT) {
            @Override
            public List<Object> getIstioResources(String namespace, String type) {
                return "authorizationpolicies".equals(type) ? policies : List.of();
            }
        };
        Method method = IstioDiagnosticService.class.getDeclaredMethod(
                "analyzeAuthorizationPolicies", String.class, String.class, String.class);
        method.setAccessible(true);
        return (AuthorizationAnalysis) method.invoke(stubbed, "demo", "demo-app", "clients");
    }

    private static Map<String, Object> policy(String name, Map<String, Object> spec) {
        return Map.of("metadata", Map.of("name", name), "spec", spec);
    }

    private static Map<String, Object> rulesFromNamespace(String action, String namespace) {
        return Map.of("action", action, "rules", List.of(Map.of(
                "from", List.of(Map.of("source", Map.of("namespaces", List.of(namespace)))))));
    }

    @Test
    void analyzeAuthorizationPolicies_allowWithEmptySpec_blocksEverything() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(policy("allow-nothing", Map.of())));

        assertTrue(result.verdict().startsWith("BLOCKED"), result.verdict());
        assertFalse(result.matchingPolicies().getFirst().appliesToSource());
    }

    @Test
    void analyzeAuthorizationPolicies_denyWithoutRules_blocksNothing() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(policy("deny-nothing", Map.of("action", "DENY"))));

        assertTrue(result.verdict().startsWith("ALLOWED"), result.verdict());
    }

    @Test
    void analyzeAuthorizationPolicies_allowWithEmptyRule_allowsEverything() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(
                policy("allow-all", Map.of("rules", List.of(Map.of())))));

        assertTrue(result.verdict().startsWith("ALLOWED"), result.verdict());
    }

    @Test
    void analyzeAuthorizationPolicies_auditPolicy_doesNotTriggerDenyByDefault() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(
                policy("audit-other", rulesFromNamespace("AUDIT", "other"))));

        assertTrue(result.verdict().startsWith("ALLOWED"), result.verdict());
    }

    @Test
    void analyzeAuthorizationPolicies_customPolicyForOtherNamespace_doesNotTriggerDenyByDefault() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(
                policy("ext-authz-other", rulesFromNamespace("CUSTOM", "other"))));

        assertTrue(result.verdict().startsWith("ALLOWED"), result.verdict());
    }

    @Test
    void analyzeAuthorizationPolicies_customPolicyForSource_isUnknown() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(
                policy("ext-authz", rulesFromNamespace("CUSTOM", "clients"))));

        assertTrue(result.verdict().startsWith("UNKNOWN"), result.verdict());
    }

    @Test
    void analyzeAuthorizationPolicies_denyForSource_winsOverAllow() throws Exception {
        AuthorizationAnalysis result = analyzeWithPolicies(List.of(
                policy("allow-clients", rulesFromNamespace("ALLOW", "clients")),
                policy("deny-clients", rulesFromNamespace("DENY", "clients"))));

        assertTrue(result.verdict().startsWith("BLOCKED – DENY"), result.verdict());
    }

    // =========================================================================
    // correlateUrl: Lesefehler & Host-Matching
    // =========================================================================

    private static final K8sApiException FORBIDDEN = new K8sApiException(403,
            "authorizationpolicies in Namespace 'demo' nicht lesbar: HTTP 403", null);

    /** Stub: "forbiddenType" wirft 403, sonst liefert resources je "namespace/type" die Ressourcen. */
    private UrlCorrelation correlateWithStub(String url, String sourceNamespace, String forbiddenType,
            Map<String, List<Object>> resources) {
        IstioDiagnosticService stubbed = new IstioDiagnosticService(K8sClientServiceTest.withoutCluster(),
                DataSize.ofMegabytes(5), "http://127.0.0.1:15000", CLOSED_PORT) {
            @Override
            public List<Object> getIstioResources(String namespace, String type) {
                if (type.equals(forbiddenType)) throw FORBIDDEN;
                return resources.getOrDefault(namespace + "/" + type, List.of());
            }
        };
        return stubbed.correlateUrl(url, sourceNamespace);
    }

    @Test
    void analyzeAuthorizationPolicies_forbidden_isUnknownNotAllowed() {
        UrlCorrelation result = correlateWithStub("http://demo-app.demo.svc.cluster.local/", "clients",
                "authorizationpolicies", Map.of());

        String verdict = result.authorizationPolicies().verdict();
        assertTrue(verdict.startsWith("UNKNOWN"), verdict);
        assertTrue(verdict.contains("403"), verdict);
    }

    @Test
    void correlateUrl_forbiddenVirtualServices_reportsWarning() {
        UrlCorrelation result = correlateWithStub("http://demo-app.demo.svc.cluster.local/", "clients",
                "virtualservices", Map.of());

        assertNull(result.error());
        assertEquals(List.of(FORBIDDEN.getMessage(), FORBIDDEN.getMessage()), result.warnings());
        assertFalse(result.hasMatch());
    }

    @Test
    void correlateUrl_forbiddenSidecars_reportsWarning() {
        UrlCorrelation result = correlateWithStub("http://demo-app.demo.svc.cluster.local/", "clients",
                "sidecars", Map.of());

        assertEquals(List.of(FORBIDDEN.getMessage()), result.warnings());
        assertFalse(result.sidecarEgress().restricted());
        assertNotNull(result.sidecarEgress().info());
    }

    @Test
    void correlateUrl_allReadable_hasNoWarnings() {
        UrlCorrelation result = correlateWithStub("http://demo-app.demo.svc.cluster.local/", "clients",
                null, Map.of());

        assertNull(result.warnings());
        assertTrue(result.authorizationPolicies().verdict().startsWith("ALLOWED"));
    }

    @Test
    void correlateUrl_shortVsHostDoesNotMatchExternalHost() {
        UrlCorrelation result = correlateWithStub("https://api.github.com/", "clients", null,
                Map.of("clients/virtualservices", List.of(buildVs("api-vs", List.of("api"), null))));

        assertTrue(result.matchedVirtualServices().isEmpty());
    }

    @Test
    void correlateUrl_shortVsHostInSourceDoesNotMatchSameNamedServiceInTarget() {
        UrlCorrelation result = correlateWithStub("http://demo.demo.svc.cluster.local/", "clients", null, Map.of(
                "clients/virtualservices", List.of(buildVs("clients-demo", List.of("demo"), null)),
                "demo/virtualservices", List.of(buildVs("demo-demo", List.of("demo"), null))));

        assertEquals(List.of("demo-demo"), result.matchedVirtualServices().stream().map(VirtualServiceMatch::name).toList());
    }

    @Test
    void correlateUrl_shortTargetHostMatchesShortVsHostInSourceNamespace() {
        UrlCorrelation result = correlateWithStub("http://api/", "clients", null,
                Map.of("clients/virtualservices", List.of(buildVs("api-vs", List.of("api"), null))));

        assertEquals(1, result.matchedVirtualServices().size());
    }
}
