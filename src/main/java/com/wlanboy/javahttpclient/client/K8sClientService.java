package com.wlanboy.javahttpclient.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
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

    /**
     * Erzeugt den {@link ApiClient}. Austauschbar, damit Tests ohne Kubeconfig
     * und ohne laufenden Cluster auskommen.
     */
    @FunctionalInterface
    interface ApiClientFactory {
        ApiClient create() throws IOException;
    }

    private final ApiClientFactory apiClientFactory;
    private volatile ApiClient apiClient;
    private volatile CustomObjectsApi customObjectsApi;
    private volatile boolean initialized = false;
    private volatile String initError = null;

    public K8sClientService() {
        this(Config::defaultClient);
    }

    K8sClientService(ApiClientFactory apiClientFactory) {
        this.apiClientFactory = apiClientFactory;
        initializeClient();
    }

    private synchronized void initializeClient() {
        if (initialized) {
            return;
        }
        try {
            this.apiClient = apiClientFactory.create();
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
     * die API-Version nicht existiert (HTTP 404) oder die Antwort kein "items"-Feld enthält.
     *
     * @throws K8sApiException bei allen anderen Fehlern (z. B. 403 durch fehlende RBAC-Rechte,
     *         API-Server nicht erreichbar)
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
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                logger.debug("Kein Ergebnis für Version {} ({}): {}", version, plural, e.getMessage());
                return Optional.empty();
            }
            String message = describe(e, plural, namespace);
            logger.warn("K8s API Fehler: {}", message);
            throw new K8sApiException(e.getCode(), message, e);
        }
    }

    private static String describe(ApiException e, String plural, String namespace) {
        String target = plural + " in Namespace '" + namespace + "' nicht lesbar";
        if (e.getCode() == 0) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return target + ": K8s API nicht erreichbar (" + cause + ")";
        }
        String detail = statusMessage(e.getResponseBody());
        return target + ": HTTP " + e.getCode() + (detail != null ? " – " + detail : "");
    }

    /** Liest "message" aus dem K8s-Status-Body, z. B. "... is forbidden: User ... cannot list resource ...". */
    private static String statusMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            Object message = JsonParserFactory.getJsonParser().parseMap(responseBody).get("message");
            return message != null ? message.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
