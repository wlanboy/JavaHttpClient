package com.wlanboy.javahttpclient.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.wlanboy.javahttpclient.client.ClientService;

import jakarta.validation.Valid;

@RestController
@Validated
@Tag(name = "HTTP Client", description = "Sendet HTTP-Anfragen an beliebige Ziel-URLs weiter")
public class HttpClientController {

	private final ClientService service;

	public HttpClientController(ClientService service) {
		this.service = service;
	}

	@Operation(
		summary = "HTTP-Request weiterleiten",
		description = "Sendet einen HTTP-Request mit der angegebenen Methode, URL und optionalem Body an das Ziel-System"
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Antwort des Zielsystems",
			content = @Content(schema = @Schema(type = "string"))),
		@ApiResponse(responseCode = "400", description = "Ungültige Anfrage (Validierungsfehler)",
			content = @Content),
		@ApiResponse(responseCode = "502", description = "Fehler beim Verbinden mit dem Zielsystem",
			content = @Content)
	})
	@PostMapping(value = "/client")
	public ResponseEntity<String> postMapping(@RequestBody @Valid JavaHttpRequest requestData,
											  @RequestHeader HttpHeaders headers) {
		return service.sendRequest(requestData, headers);
	}
}
