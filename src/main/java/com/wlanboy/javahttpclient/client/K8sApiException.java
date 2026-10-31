package com.wlanboy.javahttpclient.client;

/**
 * Lesefehler gegen die Kubernetes API, der nicht "Version/Ressource existiert nicht" bedeutet,
 * z. B. HTTP 403 durch fehlende RBAC-Rechte oder ein nicht erreichbarer API-Server.
 * Getrennt von "keine Ressourcen", damit fehlende Rechte nicht als leere Liste durchgehen.
 */
public class K8sApiException extends RuntimeException {

    /** HTTP-Status der K8s API, 0 wenn keine Antwort kam. */
    private final int statusCode;

    public K8sApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
