package com.wlanboy.javahttpclient.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.wlanboy.javahttpclient.client.ClientService;

@RestController
public class HttpClientController {

	@Autowired
	ClientService service;

	@PostMapping(value = "/client")
	public ResponseEntity<String> postMapping(@RequestBody @Validated JavaHttpRequest requestData,
											  @RequestHeader HttpHeaders headers
	) {
		return service.sendRequest(requestData, headers);
	}

}
