package com.wlanboy.javahttpclient.controller;

import java.util.Map;

import org.springframework.http.HttpMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record JavaHttpRequest(
    @Schema(description = "Die Ziel-URL", example = "https://github.com")
    @NotBlank(message = "URL darf nicht leer sein")
    @Pattern(regexp = "^https?://.*", message = "URL muss mit http:// oder https:// beginnen")
    String url,

    @Schema(description = "HTTP Methode", example = "POST")
    @NotNull(message = "HTTP Methode ist erforderlich")
    HttpMethod method,

    @Schema(description = "Optionaler Request Body", example = "{\"key\": \"value\"}")
    String body,

    @Schema(description = "Content-Type des Bodys", example = "application/json")
    String contentType,

    @Schema(description = "Header kopieren")
    boolean copyHeaders,

    @Schema(description = "Header hinzufuegen")
    Map<String, String> customHeaders
) {}

