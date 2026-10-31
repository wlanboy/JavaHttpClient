package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Initialisierungsstatus des Kubernetes API Clients")
public record K8sStatus(
        boolean initialized,
        Set<String> supportedIstioTypes,
        List<String> istioVersionsProbed,
        @Schema(description = "Nur gesetzt, wenn die Initialisierung fehlgeschlagen ist")
        String initError) {}
