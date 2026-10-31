package com.wlanboy.javahttpclient.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Fehlerantwort")
public record ErrorResponse(String error) {}
