package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class K8sDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(K8sDiagnosticService.class);
    private static final String ENVOY_ADMIN_URL = "http://127.0.0.1:15000";
    private static final String ISTIO_GROUP = "networking.istio.io";
    private static final String ISTIO_VERSION = "v1alpha3";

    private volatile ApiClient apiClient;
    private volatile CustomObjectsApi customObjectsApi;
    private final RestTemplate restTemplate;
    private volatile boolean k8sInitialized = false;
    private volatile String k8sInitError = null;

    public K8sDiagnosticService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
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
        } catch (IOException e) {
            logger.warn("K8s API Client konnte nicht initialisiert werden: {}. Istio-Ressourcen nicht verfügbar.", e.getMessage());
            this.k8sInitError = e.getMessage();
        }
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

            reachability.put("envoyConfig", restTemplate.getForObject(ENVOY_ADMIN_URL + "/config_dump", Map.class));
            String clusters = restTemplate.getForObject(ENVOY_ADMIN_URL + "/clusters", String.class);
            reachability.put("activeEndpoints", clusters);
            reachability.put("summary", summarizeClusters(clusters));
            report.put("reachability", reachability);

            // Sektion B: Gesundheit & Fehler
            Map<String, Object> health = new HashMap<>();
            String rawStats = restTemplate.getForObject(
                    ENVOY_ADMIN_URL + "/stats?filter=.*(errors|5xx|timeout|retry|failed|reset|refused|overflow).*",
                    String.class);
            Map<String, String> activeErrors = parseStats(rawStats, true);
            health.put("activeErrorMetrics", activeErrors);
            health.put("errorCount", activeErrors.size());
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
            // Server Info (Version, State)
            envoy.put("info", restTemplate.getForObject(ENVOY_ADMIN_URL + "/server_info", Map.class));

            // Aktive Netzwerk-Cluster (Ziele, die Envoy kennt)
            // Wir nehmen hier nur eine Zusammenfassung, da der Output riesig sein kann
            String clusters = restTemplate.getForObject(ENVOY_ADMIN_URL + "/clusters", String.class);
            envoy.put("clusterSummary", summarizeClusters(clusters));

            // Wichtige Netzwerk-Stats (Retries, Timeouts, 5xx Fehler)
            String stats = restTemplate.getForObject(
                    ENVOY_ADMIN_URL + "/stats?filter=cluster.*.upstream_rq_(5xx|timeout|retry)", String.class);
            envoy.put("networkStats", parseStats(stats, false));

        } catch (Exception e) {
            envoy.put("error", "Envoy Admin API nicht erreichbar: " + e.getMessage());
        }
        return envoy;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getIstioResources(String namespace, String type) {
        if (!k8sInitialized) {
            logger.warn("K8s API nicht verfügbar: {}", k8sInitError);
            return Collections.emptyList();
        }

        try {
            String plural = type.toLowerCase().endsWith("s") ? type.toLowerCase() : type.toLowerCase() + "s";

            logger.info("Abfrage Istio API: Group={}, Namespace={}, Plural={}", ISTIO_GROUP, namespace, plural);

            Object result = customObjectsApi
                    .listNamespacedCustomObject(ISTIO_GROUP, ISTIO_VERSION, namespace, plural)
                    .execute();

            if (result instanceof Map) {
                List<Object> items = (List<Object>) ((Map<String, Object>) result).get("items");
                logger.info("API Erfolg: {} Ressourcen gefunden.", items.size());
                return items;
            }
        } catch (Exception e) {
            logger.error("Kritischer Fehler beim API-Call ({}): {}", type, e.getMessage());
        }
        return Collections.emptyList();
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
                                } catch (NumberFormatException ignored) {}
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