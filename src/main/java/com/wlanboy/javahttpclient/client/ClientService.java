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
		MultiValueMap<String, String> responseHeaders = null;
		String responseBody = "";
		int statusCode = 200;

		try {
			List<String> badHeaders = Arrays.asList("host", "content-length", "connection", "accept-encoding");
			
			HttpRequest.BodyPublisher bodyPublisher = (requestData.body() == null || requestData.body().isBlank()) 
					? HttpRequest.BodyPublishers.noBody() 
					: HttpRequest.BodyPublishers.ofString(requestData.body());

			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(requestData.url()))
					.method(requestData.method().name(), bodyPublisher);

			if (requestData.body() != null && !requestData.body().isBlank()) {
				builder.header("Content-Type", "application/json");
			}

			if (requestData.copyHeaders()) {
				httpHeaders.forEach((key, value) -> {
					if (!badHeaders.contains(key.toLowerCase())) {
						try {
							builder.header(key, value.get(0));
						} catch (IllegalArgumentException e) {
							logger.warn("Header {} ist reserviert und wurde übersprungen", key);
						}
					}
				});
			}

			HttpRequest request = builder.build();
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

			responseBody = response.body();
			statusCode = response.statusCode();
			responseHeaders = CollectionUtils.toMultiValueMap(response.headers().map());

		} catch (IOException | InterruptedException e) {
			responseBody = logError(e);
			statusCode = 500;
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
		
		return new ResponseEntity<>(responseBody, responseHeaders, statusCode);
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
