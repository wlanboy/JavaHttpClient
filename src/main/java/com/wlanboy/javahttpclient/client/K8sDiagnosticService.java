package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Service
public class K8sDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(K8sDiagnosticService.class);
    
    private final ApiClient apiClient;
    private final CoreV1Api coreV1Api;
    private final CustomObjectsApi customObjectsApi;

    public K8sDiagnosticService() throws IOException {
        this.apiClient = Config.defaultClient();
        this.coreV1Api = new CoreV1Api(apiClient);
        this.customObjectsApi = new CustomObjectsApi(apiClient);
        logger.info("K8s ApiClient mit Fluent-API initialisiert.");
    }

    public Map<String, Object> getContext() {
        Map<String, Object> details = new HashMap<>();
        details.put("podName", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        details.put("namespace", getCurrentNamespace());
        details.put("istioSidecar", checkIstioSidecar());
        return details;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getIstioResources(String namespace, String type) {
        try {
            String plural = type.toLowerCase().endsWith("s") ? type.toLowerCase() : type.toLowerCase() + "s";
            
            // Korrekte Verwendung der Fluent-API:
            // 1. Request-Objekt erstellen
            // 2. execute() aufrufen
            Object result = customObjectsApi.listNamespacedCustomObject(
                "networking.istio.io", 
                "v1alpha3", 
                namespace, 
                plural
            ).execute();

            if (result instanceof Map) {
                return (List<Object>) ((Map<String, Object>) result).get("items");
            }
            return Collections.emptyList();
            
        } catch (Exception e) {
            logger.error("Fehler beim Abrufen der Istio-Ressourcen ({}): {}", type, e.getMessage());
            return Collections.singletonList(Map.of("error", e.getMessage()));
        }
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