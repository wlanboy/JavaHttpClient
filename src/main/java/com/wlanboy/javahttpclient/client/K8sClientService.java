package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generischer, Istio-unabhängiger Zugriff auf die Kubernetes API
 * (Client-Initialisierung, aktueller Namespace, CustomObject-Listing).
 */
@Service
public class K8sClientService {

    private static final Logger logger = LoggerFactory.getLogger(K8sClientService.class);

    private volatile ApiClient apiClient;
    private volatile CustomObjectsApi customObjectsApi;
    private volatile boolean initialized = false;
    private volatile String initError = null;

    public K8sClientService() {
        initializeClient();
    }

    private synchronized void initializeClient() {
        if (initialized) {
            return;
        }
        try {
            this.apiClient = Config.defaultClient();
            this.customObjectsApi = new CustomObjectsApi(apiClient);
            this.initialized = true;
            this.initError = null;
        } catch (IOException e) {
            logger.warn("K8s API Client konnte nicht initialisiert werden: {}.", e.getMessage());
            this.initError = e.getMessage();
        }
    }

    public boolean isInitialized() {
        if (!initialized) {
            initializeClient();
        }
        return initialized;
    }

    public String getInitError() {
        return initError;
    }

    public String getCurrentNamespace() {
        try {
            return Files.readString(Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/namespace")).trim();
        } catch (Exception e) {
            return System.getenv().getOrDefault("KUBERNETES_NAMESPACE", "default");
        }
    }

    /**
     * Listet CustomObjects einer Group/Version/Plural im angegebenen Namespace.
     * Liefert {@code Optional.empty()}, wenn der Client nicht initialisiert ist,
     * der Aufruf fehlschlägt, oder die Antwort kein "items"-Feld enthält.
     */
    @SuppressWarnings("unchecked")
    public Optional<List<Object>> listNamespacedCustomObject(String group, String version, String namespace, String plural) {
        if (!isInitialized()) {
            logger.warn("K8s API nicht verfügbar: {}", initError);
            return Optional.empty();
        }
        try {
            Object result = customObjectsApi
                    .listNamespacedCustomObject(group, version, namespace, plural)
                    .execute();

            if (result instanceof Map<?, ?> resultMap && resultMap.get("items") instanceof List<?> items) {
                return Optional.of((List<Object>) items);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Kein Ergebnis für Version {} ({}): {}", version, plural, e.getMessage());
            return Optional.empty();
        }
    }
}
