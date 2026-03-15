package com.wlanboy.javahttpclient.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.wlanboy.javahttpclient.controller.JavaHttpRequest;

@Service
public class ClientService {
	private static final Logger logger = LoggerFactory.getLogger(ClientService.class);
	private static final Set<String> BAD_HEADERS = Set.of(
			"host", "content-length", "connection", "accept-encoding", "upgrade"
	);
	private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);
	private static final int MAX_REDIRECTS = 10;

	private final HttpClient client;
	private final HttpClient clientHttp11;

	public ClientService() {
		client = HttpClient.newBuilder()
				.version(Version.HTTP_2)
				.followRedirects(Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		clientHttp11 = HttpClient.newBuilder()
				.version(Version.HTTP_1_1)
				.followRedirects(Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	public ResponseEntity<String> sendRequest(JavaHttpRequest requestData, HttpHeaders incomingHeaders) {
		try {
			List<Map<String, Object>> redirectChain = new ArrayList<>();
			URI currentUri = URI.create(requestData.url());
			String currentMethod = requestData.method().name();
			String currentBody = requestData.body();

			// DNS-Auflösung vor dem Request (diagnostisch)
			String resolvedIps = resolveDns(currentUri.getHost());

			// Erster Request mit HTTP/2, Fallback auf HTTP/1.1 bei Verbindungs- oder Protokollfehler
			record RequestResult(HttpClient activeClient, HttpResponse<String> response, boolean usedFallback) {}
			RequestResult initial;
			try {
				HttpResponse<String> h2response = client.send(
						buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
						BodyHandlers.ofString());
				// 502 mit Envoy-Protokollfehler → HTTP/2 wird vom Upstream nicht unterstützt
				boolean isProtocolError = h2response.statusCode() == 502
						&& h2response.body() != null
						&& h2response.body().contains("protocol error");
				if (isProtocolError) {
					logger.warn("HTTP/2 502 protocol error, Fallback auf HTTP/1.1");
					initial = new RequestResult(clientHttp11,
							clientHttp11.send(buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
									BodyHandlers.ofString()),
							true);
				} else {
					initial = new RequestResult(client, h2response, false);
				}
			} catch (IOException e) {
				logger.warn("HTTP/2 Verbindungsfehler ({}), Fallback auf HTTP/1.1", e.getMessage());
				initial = new RequestResult(clientHttp11,
						clientHttp11.send(buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
								BodyHandlers.ofString()),
						true);
			}
			HttpClient activeClient = initial.activeClient();
			HttpResponse<String> response = initial.response();
			boolean usedFallback = initial.usedFallback();

			// Redirects manuell verfolgen
			while (REDIRECT_CODES.contains(response.statusCode()) && redirectChain.size() < MAX_REDIRECTS) {
				String location = response.headers().firstValue("location").orElse(null);
				if (location == null) break;

				URI nextUri = currentUri.resolve(location);

				Map<String, Object> step = new LinkedHashMap<>();
				step.put("from", currentUri.toString());
				step.put("status", response.statusCode());
				step.put("to", nextUri.toString());
				step.put("proto", response.version() == Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1");
				redirectChain.add(step);

				currentUri = nextUri;
				// 307/308: Methode + Body beibehalten; alle anderen → GET ohne Body
				if (response.statusCode() != 307 && response.statusCode() != 308) {
					currentMethod = "GET";
					currentBody = null;
				}

				// Folge-Requests ohne originale Browser-Header (kein Auth-Leak)
				response = activeClient.send(
						buildRequest(currentUri, currentMethod, currentBody, null, null),
						BodyHandlers.ofString());
			}

			HttpHeaders responseHeaders = new HttpHeaders();
			response.headers().map().forEach(responseHeaders::addAll);
			String protocolVersion = response.version() == Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";

			return ResponseEntity.status(response.statusCode())
					.headers(h -> {
						h.addAll(responseHeaders);
						h.set("X-Protocol-Version", protocolVersion);
						if (usedFallback) h.set("X-Protocol-Fallback", "HTTP/1.1");
						if (resolvedIps != null) h.set("X-Resolved-IP", resolvedIps);
						if (!redirectChain.isEmpty()) {
							h.set("X-Redirect-Chain", serializeChain(redirectChain));
						}
					})
					.body(response.body());

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

	private HttpRequest buildRequest(URI uri, String method, String body,
			JavaHttpRequest requestData, HttpHeaders incomingHeaders) {

		boolean hasBody = body != null && !body.isBlank()
				&& !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");

		HttpRequest.BodyPublisher bodyPublisher = hasBody
				? HttpRequest.BodyPublishers.ofString(body)
				: HttpRequest.BodyPublishers.noBody();

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(Duration.ofSeconds(30))
				.method(method, bodyPublisher);

		if (hasBody && requestData != null) {
			String ct = requestData.contentType();
			builder.header("Content-Type", ct != null && !ct.isBlank() ? ct : "application/json");
		}

		if (requestData != null && requestData.copyHeaders() && incomingHeaders != null) {
			incomingHeaders.forEach((key, value) -> {
				if (!BAD_HEADERS.contains(key.toLowerCase()) && !value.isEmpty()) {
					try {
						builder.header(key, value.get(0));
					} catch (IllegalArgumentException e) {
						logger.warn("Header {} ist geschützt und wurde übersprungen", key);
					}
				}
			});
		}

		if (requestData != null && requestData.customHeaders() != null) {
			requestData.customHeaders().forEach((key, value) -> {
				if (key != null && !key.isBlank() && !BAD_HEADERS.contains(key.toLowerCase())) {
					builder.header(key, value);
				}
			});
		}

		return builder.build();
	}

	private String resolveDns(String hostname) {
		if (hostname == null || hostname.isBlank()) return null;
		try {
			InetAddress[] addresses = InetAddress.getAllByName(hostname);
			return java.util.Arrays.stream(addresses)
					.map(InetAddress::getHostAddress)
					.distinct()
					.collect(java.util.stream.Collectors.joining(", "));
		} catch (Exception e) {
			return null;
		}
	}

	private String serializeChain(List<Map<String, Object>> chain) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < chain.size(); i++) {
			if (i > 0) sb.append(",");
			Map<String, Object> step = chain.get(i);
			sb.append("{")
					.append("\"from\":\"").append(escapeJson(step.get("from").toString())).append("\",")
					.append("\"status\":").append(step.get("status")).append(",")
					.append("\"to\":\"").append(escapeJson(step.get("to").toString())).append("\",")
					.append("\"proto\":\"").append(step.get("proto")).append("\"")
					.append("}");
		}
		return sb.append("]").toString();
	}

	private String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String formatErrorResponse(Exception e) {
		String summary = "Unbekannter Fehler";
		String detail = e.getMessage() != null ? e.getMessage() : "Keine Nachricht";

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
