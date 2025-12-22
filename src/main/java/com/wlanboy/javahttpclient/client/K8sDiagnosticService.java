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
import java.util.*;

@Service
public class K8sDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(K8sDiagnosticService.class);
    private static final String ENVOY_ADMIN_URL = "http://127.0.0.1:15000";

    private final ApiClient apiClient;
    private final CustomObjectsApi customObjectsApi;
    private final RestTemplate restTemplate;

    public K8sDiagnosticService() throws IOException {
        this.apiClient = Config.defaultClient();
        this.customObjectsApi = new CustomObjectsApi(apiClient);
        this.restTemplate = new RestTemplate(); // Für Envoy Admin API Calls
        logger.info("K8s ApiClient und Diagnose-Service initialisiert.");
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

    /**
     * Fragt den lokalen Envoy Proxy nach netzwerkrelevanten Infos
     */

    public Map<String, Object> getFullSidecarDetails() {
        Map<String, Object> report = new LinkedHashMap<>(); // LinkedHashMap für stabile Reihenfolge im JSON

        if (!checkIstioSidecar()) {
            report.put("error", "Istio Sidecar nicht aktiv. Port 15021 reagiert nicht.");
            return report;
        }

        try {
            // --- SEKTION A: KONFIGURATION & ERREICHBARKEIT ---
            Map<String, Object> reachability = new HashMap<>();

            // Der Config Dump zeigt, was Envoy theoretisch tun sollte
            reachability.put("envoyConfig", restTemplate.getForObject(ENVOY_ADMIN_URL + "/config_dump", Map.class));

            // Der Cluster-Status zeigt, was Envoy real sieht (inkl. IP-Adressen der Pods)
            String clusters = restTemplate.getForObject(ENVOY_ADMIN_URL + "/clusters", String.class);
            reachability.put("activeEndpoints", clusters);
            reachability.put("summary", summarizeClusters(clusters));

            report.put("reachability", reachability);

            // --- SEKTION B: FEHLER & ANOMALIEN ---
            Map<String, Object> health = new HashMap<>();

            // 1. Hole alle Statistiken, die auf Fehler hindeuten
            String rawStats = restTemplate.getForObject(
                    ENVOY_ADMIN_URL + "/stats?filter=.*(errors|5xx|timeout|retry|failed|reset|refused|overflow).*",
                    String.class);

            // 2. Filtere nur die Statistiken, die wirklich Fehler zählen (Wert > 0)
            Map<String, String> activeErrors = parseErrorStatsOnly(rawStats);
            health.put("activeErrorMetrics", activeErrors);
            health.put("errorCount", activeErrors.size());

            report.put("healthDiagnostics", health);
            report.put("timestamp", new Date());

        } catch (Exception e) {
            logger.error("Diagnose fehlgeschlagen", e);
            report.put("error", "Kritischer Diagnosefehler: " + e.getMessage());
        }
        return report;
    }

    /**
     * Filtert Statistiken: Nimmt nur Zeilen mit Werten > 0 auf,
     * um die "Nadel im Heuhaufen" zu finden.
     */
    private Map<String, String> parseErrorStatsOnly(String rawStats) {
        Map<String, String> errorMap = new TreeMap<>(); // TreeMap sortiert alphabetisch
        if (rawStats != null) {
            rawStats.lines()
                    .filter(line -> line.contains(":"))
                    .forEach(line -> {
                        String[] parts = line.split(":");
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        try {
                            // Wir nehmen nur Metriken auf, die einen Wert ungleich 0 haben
                            if (Long.parseLong(value) > 0) {
                                errorMap.put(key, value);
                            }
                        } catch (NumberFormatException e) {
                            // Falls es kein Long ist (z.B. Text-Status), nehmen wir es trotzdem auf
                            errorMap.put(key, value);
                        }
                    });
        }
        return errorMap;
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
            envoy.put("networkStats", parseStats(stats));

        } catch (Exception e) {
            envoy.put("error", "Envoy Admin API nicht erreichbar: " + e.getMessage());
        }
        return envoy;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getIstioResources(String namespace, String type) {
        try {
            String plural = type.toLowerCase().endsWith("s") ? type.toLowerCase() : type.toLowerCase() + "s";
            Object result = customObjectsApi.listNamespacedCustomObject(
                    "networking.istio.io", "v1alpha3", namespace, plural).execute();

            return (result instanceof Map) ? (List<Object>) ((Map<String, Object>) result).get("items")
                    : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Fehler beim Abrufen der Istio-Ressourcen ({}): {}", type, e.getMessage());
            return Collections.singletonList(Map.of("error", e.getMessage()));
        }
    }

    private String summarizeClusters(String rawClusters) {
        if (rawClusters == null)
            return "Keine Daten";
        return (long) rawClusters.split("\n").length + " bekannte Upstream-Endpunkte";
    }

    private Map<String, String> parseStats(String rawStats) {
        Map<String, String> statsMap = new HashMap<>();
        if (rawStats != null) {
            rawStats.lines()
                    .filter(line -> line.contains(":"))
                    .forEach(line -> {
                        String[] parts = line.split(":");
                        statsMap.put(parts[0].trim(), parts[1].trim());
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
            socket.connect(new InetSocketAddress("127.0.0.1", 15021), 150);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}