package com.wlanboy.javahttpclient.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wlanboy.javahttpclient.client.ClientService;

@RestController
public class HttpClientController {

	@Autowired
	ClientService service;

	@PostMapping(value = "/client")
	public ResponseEntity<String> postMapping(@Validated HttpEntity<JavaHttpRequest> requestdata) {
		return service.sendRequest(requestdata.getBody(), requestdata.getHeaders());
	}

}
