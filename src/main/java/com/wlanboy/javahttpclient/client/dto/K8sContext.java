package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Pod-Kontext für die Navbar")
public record K8sContext(
        String podName,
        String namespace,
        boolean istioSidecar,
        @Schema(description = "Nur gesetzt, wenn ein Istio-Sidecar aktiv ist")
        EnvoyDetails istioDetails) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Kurzinfo aus der Envoy Admin API")
    public record EnvoyDetails(
            @Schema(description = "Rohdaten von /server_info")
            Map<String, Object> info,
            String clusterSummary,
            Map<String, String> networkStats,
            String error) {

        public static EnvoyDetails error(String error) {
            return new EnvoyDetails(null, null, null, error);
        }
    }
}
