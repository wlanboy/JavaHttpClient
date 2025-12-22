package com.wlanboy.javahttpclient.client;

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
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	public ResponseEntity<String> sendRequest(JavaHttpRequest requestData, HttpHeaders incomingHeaders) {
		try {
			List<String> badHeaders = Arrays.asList("host", "content-length", "connection", "accept-encoding",
					"upgrade");

			HttpRequest.BodyPublisher bodyPublisher = (requestData.body() == null || requestData.body().isBlank())
					? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(requestData.body());

			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(requestData.url()))
					.method(requestData.method().name(), bodyPublisher);

			if (requestData.body() != null && !requestData.body().isBlank()) {
				builder.header("Content-Type", "application/json");
			}

			if (requestData.copyHeaders() && incomingHeaders != null) {
				incomingHeaders.forEach((key, value) -> {
					if (!badHeaders.contains(key.toLowerCase()) && !value.isEmpty()) {
						try {
							builder.header(key, value.get(0));
						} catch (IllegalArgumentException e) {
							logger.warn("Header {} ist geschützt und wurde übersprungen", key);
						}
					}
				});
			}

			if (requestData.customHeaders() != null) {
				requestData.customHeaders().forEach((key, value) -> {
					if (key != null && !key.isBlank() && !badHeaders.contains(key.toLowerCase())) {
						builder.header(key, value);
					}
				});
			}

			HttpResponse<String> response = client.send(builder.build(), BodyHandlers.ofString());

			MultiValueMap<String, String> responseHeaders = CollectionUtils.toMultiValueMap(response.headers().map());

			return new ResponseEntity<>(response.body(), responseHeaders, response.statusCode());

		} catch (Exception e) {
			String errorDetail = formatErrorResponse(e);
			logger.error("HTTP Request fehlgeschlagen: {}", errorDetail);

			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			return ResponseEntity.status(502)
					.header("Content-Type", "text/plain; charset=UTF-8")
					.body(errorDetail);
		}
	}

	private String formatErrorResponse(Exception e) {
		String summary = "Unbekannter Fehler";
		String detail = e.getMessage() != null ? e.getMessage() : "Keine Nachricht";

		// Spezifische K8s/Netzwerk-Szenarien
		if (e instanceof java.net.UnknownHostException) {
			summary = "DNS Fehler: Host nicht gefunden. (Service-Name korrekt? Namespace vergessen?)";
		} else if (e instanceof java.net.ConnectException) {
			summary = "Verbindung abgelehnt: Der Ziel-Port ist zu oder der Pod läuft nicht.";
		} else if (e instanceof java.net.http.HttpConnectTimeoutException) {
			summary = "Timeout: Verbindung dauerte zu lange. (NetworkPolicy Blockade?)";
		} else if (e instanceof java.lang.IllegalArgumentException && e.getMessage().contains("URI")) {
			summary = "Ungültige URL: Das Format der Ziel-Adresse ist fehlerhaft.";
		}

		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));

		return String.format("%s\nDetails: %s\n---STACKTRACE---\n%s", summary, detail, sw.toString());
	}
}