package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Über den Envoy-Sidecar erreichbarer Outbound-Service")
public record ReachableService(
        @Schema(example = "my-service.default.svc.cluster.local")
        String host,
        @Schema(example = "8080")
        int port,
        @Schema(example = "my-service")
        String shortName,
        @Schema(description = "Nur gesetzt, wenn der Cluster ein Subset hat")
        String subset) {}
