package com.wlanboy.javahttpclient.controller;

import java.util.Map;

import org.springframework.http.HttpMethod;
import io.swagger.v3.oas.annotations.media.Schema;

public record JavaHttpRequest(
    @Schema(description = "Die Ziel-URL", example = "https://github.com")
    String url,
    
    @Schema(description = "HTTP Methode", example = "POST")
    HttpMethod method,
    
    @Schema(description = "Optionaler JSON Body", example = "{\"key\": \"value\"}")
    String body,
    
    @Schema(description = "Header kopieren")
    boolean copyHeaders,

    @Schema(description = "Header hinzufuegen")
    Map<String, String> customHeaders
) {}

