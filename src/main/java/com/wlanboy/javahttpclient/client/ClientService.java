package com.wlanboy.javahttpclient.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;

import com.wlanboy.javahttpclient.controller.JavaHttpRequest;

@Service
public class ClientService {
	private static final Logger logger = LoggerFactory.getLogger(ClientService.class);
	
	private final HttpClient client;
	
	public ClientService() {
		client = HttpClient.newBuilder()
				.version(Version.HTTP_1_1)
				.followRedirects(Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
	}
	
	public ResponseEntity<String> sendRequest(JavaHttpRequest requestData, HttpHeaders httpHeaders) {
		MultiValueMap<String, String> headerlist = null;
		String responsestring = "";
		int responsecode = 200;
		
		try {
			List<String> badheaders = new ArrayList<>(Arrays.asList("Host","Content-Type","Content-Length","Accept-Encoding","host","content-type","content-length", "connection","accept-encoding"));
			List<String> headers = new ArrayList<>(Arrays.asList("test","wlanboy"));
			if (requestData.copyHeaders) {
				httpHeaders.entrySet().stream().filter( (entry) -> !badheaders.contains(entry.getKey()) ).forEach((entry) -> {
					headers.add(entry.getKey()); 
					headers.add(entry.getValue().get(0));
					});
			}
			
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(requestData.url))
					.headers(headers.toArray(String[]::new))
					.GET()
					.build();
					
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
			
			responsestring = response.body();
			responsecode = response.statusCode();
			headerlist = CollectionUtils.toMultiValueMap(response.headers().map());
			
		} catch (IOException e) {
			responsestring = logError(e);
			responsecode = 500;
		} catch (InterruptedException e) {
			responsestring = logError(e);
			responsecode = 500;
		}
		return new ResponseEntity<String>(responsestring, headerlist, responsecode);
	}

	private String logError(Exception e) {
		logger.error(e.getMessage());
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		String stacktrace = pw.toString();
		pw.close();
		logger.error(stacktrace);
		return e.getMessage() + stacktrace;
	}
}
