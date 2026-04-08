package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


@Service
public class K8sDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(K8sDiagnosticService.class);
    private static final String ISTIO_GROUP = "networking.istio.io";
    private static final List<String> ISTIO_VERSIONS = List.of("v1", "v1beta1", "v1alpha3");
    private static final Set<String> SUPPORTED_ISTIO_TYPES = Set.of(
            "virtualservices", "destinationrules", "gateways", "serviceentries",
            "sidecars", "envoyfilters", "peerauthentications", "requestauthentications",
            "authorizationpolicies");

    private volatile ApiClient apiClient;
    private volatile CustomObjectsApi customObjectsApi;
    private final RestClient restClient;
    private final String envoyAdminUrl;
    private volatile boolean k8sInitialized = false;
    private volatile String k8sInitError = null;

    public K8sDiagnosticService() {
        this.envoyAdminUrl = System.getenv().getOrDefault("ENVOY_ADMIN_URL", "http://127.0.0.1:15000");
        this.restClient = RestClient.builder()
                .baseUrl(this.envoyAdminUrl)
                .build();
        initializeK8sClient();
        logger.info("K8s Diagnostic Service initialisiert (K8s API verfügbar: {}).", k8sInitialized);
    }

    private synchronized void initializeK8sClient() {
        if (k8sInitialized) {
            return;
        }
        try {
            this.apiClient = Config.defaultClient();
            this.customObjectsApi = new CustomObjectsApi(apiClient);
            this.k8sInitialized = true;
            this.k8sInitError = null;
        } catch (IOException e) {
            logger.warn("K8s API Client konnte nicht initialisiert werden: {}. Istio-Ressourcen nicht verfügbar.", e.getMessage());
            this.k8sInitError = e.getMessage();
        }
    }

    public Map<String, Object> getK8sStatus() {
        if (!k8sInitialized) {
            initializeK8sClient();
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", k8sInitialized);
        status.put("supportedIstioTypes", SUPPORTED_ISTIO_TYPES);
        status.put("istioVersionsProbed", ISTIO_VERSIONS);
        if (k8sInitError != null) {
            status.put("initError", k8sInitError);
        }
        return status;
    }

    public Map<String, Object> getContext() {
        Map<String, Object> details = new HashMap<>();
        boolean istioPresent = checkIstioSidecar();

        details.put("podName", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        details.put("namespace", getCurrentNamespace());
        details.put("istioSidecar", istioPresent);

        if (istioPresent) {
            details.put("istioDetails", getEnvoyDetails());
        }

        return details;
    }

    public Map<String, Object> getFullSidecarDetails() {
        Map<String, Object> report = new LinkedHashMap<>();

        if (!checkIstioSidecar()) {
            report.put("error", "Istio Sidecar Proxy ist nicht aktiv (Port 15021 nicht erreichbar).");
            return report;
        }

        try {
            // Sektion A: Erreichbarkeit
            Map<String, Object> reachability = new HashMap<>();
            reachability.put("envoyConfig", restClient.get().uri("/config_dump").retrieve().body(Map.class));
            String clusters = restClient.get().uri("/clusters").retrieve().body(String.class);
            reachability.put("activeEndpoints", clusters);
            reachability.put("summary", summarizeClusters(clusters));
            report.put("reachability", reachability);

            // Sektion B: Gesundheit & Fehler
            Map<String, Object> health = new HashMap<>();
            String rawStats = restClient.get().uri("/stats").retrieve().body(String.class);
            Map<String, String> activeErrors = parseStats(rawStats, false);
            // xds-grpc ist interne Istio Control-Plane – kein App-Traffic, rausfiltern
            activeErrors.entrySet().removeIf(e -> e.getKey().startsWith("cluster.xds-grpc"));
            health.put("activeErrorMetrics", activeErrors);
            health.put("errorCount", activeErrors.size());
            health.put("diagnoses", diagnoseMetrics(activeErrors));
            report.put("healthDiagnostics", health);

            report.put("timestamp", new Date());
        } catch (Exception e) {
            logger.error("Fehler beim Abruf der Envoy-Details: {}", e.getMessage());
            report.put("error", "Envoy Admin API Fehler: " + e.getMessage());
        }
        return report;
    }

    private Map<String, Object> getEnvoyDetails() {
        Map<String, Object> envoy = new HashMap<>();
        try {
            envoy.put("info", restClient.get().uri("/server_info").retrieve().body(Map.class));

            String clusters = restClient.get().uri("/clusters").retrieve().body(String.class);
            envoy.put("clusterSummary", summarizeClusters(clusters));

            String stats = restClient.get()
                    .uri("/stats?filter=cluster.*.upstream_rq_(5xx|timeout|retry)")
                    .retrieve()
                    .body(String.class);
            envoy.put("networkStats", parseStats(stats, false));

        } catch (Exception e) {
            envoy.put("error", "Envoy Admin API nicht erreichbar: " + e.getMessage());
        }
        return envoy;
    }

    public Map<String, Object> correlateUrl(String url, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = (uri.getPath() == null || uri.getPath().isBlank()) ? "/" : uri.getPath();
            int port = uri.getPort();

            result.put("requestedHost", host);
            result.put("requestedPath", path);

            List<Map<String, Object>> matchedVs = new ArrayList<>();
            for (Object vsObj : getIstioResources(namespace, "virtualservices")) {
                if (!(vsObj instanceof Map<?, ?> vs)) continue;
                Map<String, Object> analysis = analyzeVirtualService(vs, host, path, port);
                if (Boolean.TRUE.equals(analysis.get("hostMatch"))) matchedVs.add(analysis);
            }

            List<Map<String, Object>> matchedDrs = new ArrayList<>();
            for (Object drObj : getIstioResources(namespace, "destinationrules")) {
                if (!(drObj instanceof Map<?, ?> dr)) continue;
                Map<String, Object> analysis = analyzeDestinationRule(dr, host);
                if (Boolean.TRUE.equals(analysis.get("hostMatch"))) matchedDrs.add(analysis);
            }

            List<Map<String, Object>> matchedSes = new ArrayList<>();
            for (Object seObj : getIstioResources(namespace, "serviceentries")) {
                if (!(seObj instanceof Map<?, ?> se)) continue;
                Map<String, Object> analysis = analyzeServiceEntry(se, host, port);
                if (Boolean.TRUE.equals(analysis.get("hostMatch"))) matchedSes.add(analysis);
            }

            result.put("matchedVirtualServices", matchedVs);
            result.put("matchedDestinationRules", matchedDrs);
            result.put("matchedServiceEntries", matchedSes);
            result.put("hasMatch", !matchedVs.isEmpty() || !matchedDrs.isEmpty() || !matchedSes.isEmpty());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> analyzeVirtualService(Map<?, ?> vs, String targetHost, String targetPath, int targetPort) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<?, ?> metadata = (Map<?, ?>) vs.get("metadata");
        Map<?, ?> spec = (Map<?, ?>) vs.get("spec");
        result.put("name", metadata != null ? metadata.get("name") : "?");
        result.put("hostMatch", false);
        if (spec == null) return result;

        List<?> vsHosts = (List<?>) spec.get("hosts");
        if (vsHosts == null) return result;

        boolean hostMatch = vsHosts.stream().map(Object::toString).anyMatch(h -> hostsMatch(h, targetHost));
        result.put("hostMatch", hostMatch);
        result.put("vsHosts", vsHosts);
        if (!hostMatch) return result;

        List<?> httpRules = (List<?>) spec.get("http");
        if (httpRules == null || httpRules.isEmpty()) {
            result.put("noHttpRoutes", true);
            return result;
        }

        List<Map<String, Object>> routes = new ArrayList<>();
        for (Object ruleObj : httpRules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) continue;
            routes.add(analyzeHttpRule(rule, targetPath));
        }
        result.put("httpRoutes", routes);
        result.put("pathMatch", routes.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("pathMatch"))));
        return result;
    }

    private Map<String, Object> analyzeHttpRule(Map<?, ?> rule, String targetPath) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rule.get("name") != null) result.put("name", rule.get("name").toString());

        List<?> matches = (List<?>) rule.get("match");
        boolean pathMatch;
        String matchReason;

        if (matches == null || matches.isEmpty()) {
            pathMatch = true;
            matchReason = "Kein Match-Filter – trifft alle Pfade";
        } else {
            String found = null;
            outer:
            for (Object mObj : matches) {
                if (!(mObj instanceof Map<?, ?> m)) continue;
                Map<?, ?> uriMatch = (Map<?, ?>) m.get("uri");
                if (uriMatch == null) { found = "Kein URI-Filter in Match-Regel"; break; }
                if (uriMatch.containsKey("exact")) {
                    if (targetPath.equals(uriMatch.get("exact").toString())) { found = "exact: " + uriMatch.get("exact"); break; }
                } else if (uriMatch.containsKey("prefix")) {
                    if (targetPath.startsWith(uriMatch.get("prefix").toString())) { found = "prefix: " + uriMatch.get("prefix"); break; }
                } else if (uriMatch.containsKey("regex")) {
                    try { if (targetPath.matches(uriMatch.get("regex").toString())) { found = "regex: " + uriMatch.get("regex"); break outer; } }
                    catch (Exception ignored) {}
                }
            }
            pathMatch = found != null;
            matchReason = pathMatch ? "Match: " + found : "Kein URI-Pattern trifft auf '" + targetPath + "'";
        }

        result.put("pathMatch", pathMatch);
        result.put("matchReason", matchReason);

        List<?> routeDests = (List<?>) rule.get("route");
        if (routeDests != null) {
            List<Map<String, Object>> dests = new ArrayList<>();
            for (Object destEntry : routeDests) {
                if (!(destEntry instanceof Map<?, ?> de)) continue;
                Map<?, ?> dest = (Map<?, ?>) de.get("destination");
                if (dest == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("host", dest.get("host"));
                if (dest.get("subset") != null) d.put("subset", dest.get("subset"));
                if (dest.get("port") != null) d.put("port", dest.get("port"));
                if (de.get("weight") != null) d.put("weight", de.get("weight"));
                dests.add(d);
            }
            result.put("destinations", dests);
        }
        if (rule.get("timeout") != null) result.put("timeout", rule.get("timeout"));
        if (rule.get("retries") != null) result.put("retries", rule.get("retries"));
        if (rule.get("fault") != null) result.put("faultInjection", rule.get("fault"));
        return result;
    }

    private Map<String, Object> analyzeDestinationRule(Map<?, ?> dr, String targetHost) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<?, ?> metadata = (Map<?, ?>) dr.get("metadata");
        Map<?, ?> spec = (Map<?, ?>) dr.get("spec");
        result.put("name", metadata != null ? metadata.get("name") : "?");
        result.put("hostMatch", false);
        if (spec == null) return result;

        String drHost = spec.get("host") != null ? spec.get("host").toString() : null;
        if (drHost == null) return result;

        boolean hostMatch = hostsMatch(drHost, targetHost);
        result.put("hostMatch", hostMatch);
        result.put("drHost", drHost);
        if (!hostMatch) return result;

        if (spec.get("trafficPolicy") != null) result.put("trafficPolicy", spec.get("trafficPolicy"));

        List<?> subsets = (List<?>) spec.get("subsets");
        if (subsets != null) {
            List<Map<String, Object>> subsetList = new ArrayList<>();
            for (Object s : subsets) {
                if (!(s instanceof Map<?, ?> sub)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", sub.get("name"));
                entry.put("labels", sub.get("labels"));
                if (sub.get("trafficPolicy") != null) entry.put("trafficPolicy", sub.get("trafficPolicy"));
                subsetList.add(entry);
            }
            result.put("subsets", subsetList);
        }
        return result;
    }

    private Map<String, Object> analyzeServiceEntry(Map<?, ?> se, String targetHost, int targetPort) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<?, ?> metadata = (Map<?, ?>) se.get("metadata");
        Map<?, ?> spec = (Map<?, ?>) se.get("spec");
        result.put("name", metadata != null ? metadata.get("name") : "?");
        result.put("hostMatch", false);
        if (spec == null) return result;

        List<?> seHosts = (List<?>) spec.get("hosts");
        if (seHosts == null) return result;

        boolean hostMatch = seHosts.stream().map(Object::toString).anyMatch(h -> hostsMatch(h, targetHost));
        result.put("hostMatch", hostMatch);
        result.put("seHosts", seHosts);
        if (!hostMatch) return result;

        if (spec.get("location") != null)   result.put("location", spec.get("location"));
        if (spec.get("resolution") != null) result.put("resolution", spec.get("resolution"));

        // Ports prüfen ob der angeforderte Port abgedeckt ist
        List<?> ports = (List<?>) spec.get("ports");
        if (ports != null) {
            result.put("ports", ports);
            if (targetPort > 0) {
                boolean portMatch = ports.stream()
                    .filter(p -> p instanceof Map<?, ?>)
                    .map(p -> (Map<?, ?>) p)
                    .anyMatch(p -> String.valueOf(targetPort).equals(String.valueOf(p.get("number"))));
                result.put("portMatch", portMatch);
                if (!portMatch) result.put("portMatchWarning",
                    "Port " + targetPort + " ist nicht in den ServiceEntry-Ports definiert.");
            }
        }

        if (spec.get("endpoints") != null)        result.put("endpoints", spec.get("endpoints"));
        if (spec.get("subjectAltNames") != null)  result.put("subjectAltNames", spec.get("subjectAltNames"));
        return result;
    }

    private boolean hostsMatch(String vsHost, String targetHost) {
        vsHost = vsHost.toLowerCase().trim();
        targetHost = targetHost.toLowerCase().trim();
        if (vsHost.equals("*") || vsHost.equals(targetHost)) return true;
        if (vsHost.startsWith("*.")) return targetHost.endsWith(vsHost.substring(1));
        // Short name matches first segment of FQDN
        String[] parts = targetHost.split("\\.");
        if (vsHost.equals(parts[0])) return true;
        // "my-svc.ns" matches "my-svc.ns.svc.cluster.local"
        return targetHost.startsWith(vsHost + ".");
    }

    @SuppressWarnings("unchecked")
    public List<Object> getIstioResources(String namespace, String type) {
        String plural = type.toLowerCase().endsWith("s") ? type.toLowerCase() : type.toLowerCase() + "s";

        if (!SUPPORTED_ISTIO_TYPES.contains(plural)) {
            throw new IllegalArgumentException(
                    "Ungültiger Istio-Ressourcentyp: '" + type + "'. Erlaubt: " + SUPPORTED_ISTIO_TYPES);
        }

        if (!k8sInitialized) {
            initializeK8sClient();
        }
        if (!k8sInitialized) {
            logger.warn("K8s API nicht verfügbar: {}", k8sInitError);
            return Collections.emptyList();
        }

        for (String version : ISTIO_VERSIONS) {
            try {
                logger.info("Abfrage Istio API: Group={}, Version={}, Namespace={}, Plural={}",
                        ISTIO_GROUP, version, namespace, plural);

                Object result = customObjectsApi
                        .listNamespacedCustomObject(ISTIO_GROUP, version, namespace, plural)
                        .execute();

                if (result instanceof Map<?, ?> resultMap) {
                    List<Object> items = (List<Object>) resultMap.get("items");
                    if (items != null) {
                        logger.info("API Erfolg ({}): {} Ressourcen gefunden.", version, items.size());
                        return items;
                    }
                }
            } catch (Exception e) {
                logger.debug("Kein Ergebnis für Version {} ({}): {}", version, type, e.getMessage());
            }
        }

        logger.error("Keine Istio-Ressourcen für '{}' in allen Versionen {} gefunden.", type, ISTIO_VERSIONS);
        return Collections.emptyList();
    }

    private List<Map<String, Object>> diagnoseMetrics(Map<String, String> metrics) {
        record Rule(String pattern, String severity, String title, String description, String recommendation) {}

        List<Rule> rules = List.of(
            new Rule("upstream_rq_pending_overflow", "KRITISCH", "Circuit Breaker / Pool-Overflow",
                "Der Connection Pool ist voll oder ein Circuit Breaker ist offen.",
                "DestinationRule.trafficPolicy.connectionPool und outlierDetection prüfen."),
            new Rule("upstream_cx_none_healthy", "KRITISCH", "Keine gesunden Endpoints",
                "Alle Upstream-Endpoints sind nicht erreichbar.",
                "Pod-Status und Readiness-Probes prüfen. Envoy-Cluster-Health in Tab A ansehen."),
            new Rule("upstream_cx_connect_fail", "KRITISCH", "Verbindung abgelehnt (Connection refused)",
                "Envoy kann keine TCP-Verbindung zum Upstream aufbauen.",
                "Prüfen: Pod läuft? Richtiger Port? NetworkPolicy blockiert? Service-Selector korrekt?"),
            new Rule("upstream_rq_timeout", "WARNUNG", "Request Timeouts",
                "Requests zum Upstream überschreiten das Timeout.",
                "NetworkPolicy auf blockierte Ports prüfen. DestinationRule Timeout-Werte anpassen."),
            new Rule("upstream_cx_connect_timeout", "WARNUNG", "Connection Timeout",
                "Verbindungsaufbau zum Upstream schlägt fehl.",
                "Pod läuft möglicherweise nicht oder Port ist falsch. NetworkPolicy prüfen."),
            new Rule("upstream_rq_5xx", "WARNUNG", "5xx Fehler vom Upstream",
                "Der Upstream-Service gibt 5xx-Statuscodes zurück.",
                "Upstream-Logs prüfen. VirtualService-Routing und DestinationRule-Gewichtungen validieren."),
            new Rule("upstream_rq_retry_limit_exceeded", "INFO", "Retry-Limit überschritten",
                "Requests wurden mehrfach wiederholt und das Limit wurde erreicht.",
                "VirtualService retryPolicy anpassen oder Upstream-Stabilität verbessern."),
            new Rule("upstream_cx_destroy_remote_with_active_rq", "INFO", "Verbindung mit aktiven Requests abgebrochen",
                "Die Remote-Seite hat die Verbindung während aktiver Requests getrennt.",
                "Upstream Keep-Alive Konfiguration und Istio idle_timeout prüfen.")
        );

        List<Map<String, Object>> diagnoses = new ArrayList<>();
        for (Rule rule : rules) {
            List<String> matchingKeys = metrics.entrySet().stream()
                .filter(e -> e.getKey().contains(rule.pattern()))
                .map(Map.Entry::getKey)
                .sorted()
                .collect(java.util.stream.Collectors.toList());

            if (!matchingKeys.isEmpty()) {
                Map<String, Object> diagnosis = new LinkedHashMap<>();
                diagnosis.put("severity", rule.severity());
                diagnosis.put("title", rule.title());
                diagnosis.put("description", rule.description());
                diagnosis.put("recommendation", rule.recommendation());
                diagnosis.put("affectedMetrics", matchingKeys);
                diagnoses.add(diagnosis);
            }
        }
        return diagnoses;
    }

    private String summarizeClusters(String rawClusters) {
        if (rawClusters == null || rawClusters.isBlank())
            return "Keine Upstream-Daten";
        long count = rawClusters.lines().count();
        return count + " aktive Upstream-Cluster-Einträge";
    }

    private Map<String, String> parseStats(String rawStats, boolean onlyNonZero) {
        Map<String, String> statsMap = onlyNonZero ? new TreeMap<>() : new HashMap<>();
        if (rawStats != null) {
            rawStats.lines()
                    .filter(line -> line.contains(":"))
                    .forEach(line -> {
                        int colonIndex = line.lastIndexOf(':');
                        if (colonIndex > 0 && colonIndex < line.length() - 1) {
                            String key = line.substring(0, colonIndex).trim();
                            String val = line.substring(colonIndex + 1).trim();
                            if (onlyNonZero) {
                                try {
                                    if (Long.parseLong(val) > 0) {
                                        statsMap.put(key, val);
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            } else {
                                statsMap.put(key, val);
                            }
                        }
                    });
        }
        return statsMap;
    }

    private String getCurrentNamespace() {
        try {
            return Files.readString(Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/namespace")).trim();
        } catch (Exception e) {
            return System.getenv().getOrDefault("KUBERNETES_NAMESPACE", "default");
        }
    }

    private boolean checkIstioSidecar() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 15021), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
