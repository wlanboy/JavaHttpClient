package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Vollständiger Istio-Sidecar-Report")
public record SidecarReport(
        Reachability reachability,
        HealthDiagnostics healthDiagnostics,
        String timestamp,
        String error) {

    public static SidecarReport error(String error) {
        return new SidecarReport(null, null, null, error);
    }

    @Schema(description = "Sektion A: Erreichbarkeit")
    public record Reachability(
            @Schema(description = "Geparster Envoy /config_dump oder ConfigDumpTruncated, wenn das Größenlimit überschritten ist")
            Object envoyConfig,
            @Schema(description = "Rohausgabe von /clusters")
            String activeEndpoints,
            String summary) {}

    @Schema(description = "Ersatz für /config_dump, wenn der Dump zu groß zum Parsen ist")
    public record ConfigDumpTruncated(boolean truncated, int maxBytes, String error) {}

    @Schema(description = "Sektion B: Gesundheit & Fehler")
    public record HealthDiagnostics(
            Map<String, String> activeErrorMetrics,
            int errorCount,
            List<Diagnosis> diagnoses) {}

    @Schema(description = "Aus Envoy-Fehlermetriken abgeleitete Diagnose")
    public record Diagnosis(
            @Schema(allowableValues = {"KRITISCH", "WARNUNG", "INFO"})
            String severity,
            String title,
            String description,
            String recommendation,
            List<String> affectedMetrics) {}
}
