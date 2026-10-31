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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import tools.jackson.databind.json.JsonMapper;

import com.wlanboy.javahttpclient.client.LimitedBodyHandler.LimitedBody;
import com.wlanboy.javahttpclient.controller.JavaHttpRequest;

@Service
public class ClientService {
	private static final Logger logger = LoggerFactory.getLogger(ClientService.class);
	private static final Set<String> BAD_HEADERS = Set.of(
			"host", "content-length", "connection", "accept-encoding", "upgrade"
	);
	private static final Set<String> IDEMPOTENT_METHODS = Set.of(
			"GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE"
	);
	private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);
	private static final int MAX_REDIRECTS = 10;
	private static final int MAX_REDIRECT_CHAIN_HEADER_LENGTH = 4096;

	private record RedirectStep(String from, int status, String to, String proto) {}

	private final HttpClient client;
	private final HttpClient clientHttp11;
	private final boolean includeStacktrace;
	private final DataSize maxBodySize;
	private final LimitedBodyHandler bodyHandler;
	private final JsonMapper jsonMapper;

	public ClientService(@Value("${httpclient.error.include-stacktrace:true}") boolean includeStacktrace,
			@Value("${httpclient.response.max-body-size:5MB}") DataSize maxBodySize,
			JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		this.includeStacktrace = includeStacktrace;
		this.maxBodySize = maxBodySize;
		this.bodyHandler = new LimitedBodyHandler(Math.toIntExact(maxBodySize.toBytes()));
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
			List<RedirectStep> redirectChain = new ArrayList<>();
			Set<URI> visitedUris = new HashSet<>();
			URI currentUri = URI.create(requestData.url());
			visitedUris.add(currentUri);
			String currentMethod = requestData.method().name();
			String currentBody = requestData.body();

			// DNS-Auflösung vor dem Request (diagnostisch)
			String resolvedIps = resolveDns(currentUri.getHost());

			// Erster Request mit HTTP/2, Fallback auf HTTP/1.1 bei Verbindungs- oder Protokollfehler
			record RequestResult(HttpClient activeClient, HttpResponse<LimitedBody> response, boolean usedFallback) {}
			RequestResult initial;
			try {
				HttpResponse<LimitedBody> h2response = client.send(
						buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
						bodyHandler);
				// 502 mit Envoy-Protokollfehler → HTTP/2 wird vom Upstream nicht unterstützt
				boolean isProtocolError = h2response.statusCode() == 502
						&& h2response.body().text().contains("protocol error");
				if (isProtocolError) {
					logger.warn("HTTP/2 502 protocol error, Fallback auf HTTP/1.1");
					initial = new RequestResult(clientHttp11,
							clientHttp11.send(buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
									bodyHandler),
							true);
				} else {
					initial = new RequestResult(client, h2response, false);
				}
			} catch (IOException e) {
				if (!isSafeToRetry(currentMethod, e)) {
					// Request könnte das Ziel bereits erreicht haben → kein zweiter Versuch
					throw e;
				}
				logger.warn("HTTP/2 Verbindungsfehler ({}), Fallback auf HTTP/1.1", e.getMessage());
				initial = new RequestResult(clientHttp11,
						clientHttp11.send(buildRequest(currentUri, currentMethod, currentBody, requestData, incomingHeaders),
								bodyHandler),
						true);
			}
			HttpClient activeClient = initial.activeClient();
			HttpResponse<LimitedBody> response = initial.response();
			boolean usedFallback = initial.usedFallback();

			// Redirects manuell verfolgen
			while (REDIRECT_CODES.contains(response.statusCode()) && redirectChain.size() < MAX_REDIRECTS) {
				String location = response.headers().firstValue("location").orElse(null);
				if (location == null) break;

				URI nextUri = currentUri.resolve(location);
				if (visitedUris.contains(nextUri)) {
					logger.warn("Redirect-Loop erkannt: {} wurde bereits besucht", nextUri);
					break;
				}
				visitedUris.add(nextUri);

				redirectChain.add(new RedirectStep(currentUri.toString(), response.statusCode(), nextUri.toString(),
						response.version() == Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1"));

				currentUri = nextUri;
				// 307/308: Methode + Body beibehalten; alle anderen → GET ohne Body
				if (response.statusCode() != 307 && response.statusCode() != 308) {
					currentMethod = "GET";
					currentBody = null;
				}

				// Folge-Requests ohne originale Browser-Header (kein Auth-Leak)
				response = activeClient.send(
						buildRequest(currentUri, currentMethod, currentBody, null, null),
						bodyHandler);
			}

			HttpHeaders responseHeaders = new HttpHeaders();
			response.headers().map().forEach(responseHeaders::addAll);
			String protocolVersion = response.version() == Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
			boolean truncated = response.body().truncated();
			if (truncated) {
				// Upstream-Länge passt nicht mehr zum gekürzten Body
				responseHeaders.remove(HttpHeaders.CONTENT_LENGTH);
				responseHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
				logger.warn("Response von {} größer als {}, Body wurde gekürzt", currentUri, maxBodySize);
			}

			return ResponseEntity.status(response.statusCode())
					.headers(h -> {
						h.addAll(responseHeaders);
						h.set("X-Protocol-Version", protocolVersion);
						if (usedFallback) h.set("X-Protocol-Fallback", "HTTP/1.1");
						if (resolvedIps != null) h.set("X-Resolved-IP", resolvedIps);
						if (truncated) h.set("X-Body-Truncated", String.valueOf(maxBodySize.toBytes()));
						if (!redirectChain.isEmpty()) {
							String chain = jsonMapper.writeValueAsString(redirectChain);
							if (chain.length() > MAX_REDIRECT_CHAIN_HEADER_LENGTH) {
								chain = chain.substring(0, MAX_REDIRECT_CHAIN_HEADER_LENGTH - 3) + "...";
							}
							h.set("X-Redirect-Chain", chain);
						}
					})
					.body(response.body().text());

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.error("HTTP Request unterbrochen", e);
			String errorDetail = formatErrorResponse(e);
			return ResponseEntity.status(502)
					.header("Content-Type", "text/plain; charset=UTF-8")
					.body(errorDetail);
		} catch (Exception e) {
			logger.error("HTTP Request fehlgeschlagen", e);
			String errorDetail = formatErrorResponse(e);
			return ResponseEntity.status(502)
					.header("Content-Type", "text/plain; charset=UTF-8")
					.body(errorDetail);
		}
	}

	/**
	 * Fallback nur, wenn eine Wiederholung keine Seiteneffekte doppelt auslösen kann:
	 * idempotente Methoden (RFC 9110) oder Fehler, bevor der Request gesendet wurde.
	 */
	private boolean isSafeToRetry(String method, IOException e) {
		return IDEMPOTENT_METHODS.contains(method)
				|| e instanceof java.net.ConnectException
				|| e instanceof java.net.http.HttpConnectTimeoutException
				|| e instanceof javax.net.ssl.SSLHandshakeException;
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
			Set<String> customKeys = (requestData.customHeaders() != null)
					? requestData.customHeaders().keySet().stream()
							.map(String::toLowerCase)
							.collect(java.util.stream.Collectors.toSet())
					: Set.of();
			// Content-Type des eingehenden Requests beschreibt den JSON-Wrapper an /client, nicht den weitergeleiteten Body
			incomingHeaders.forEach((key, value) -> {
				if (!BAD_HEADERS.contains(key.toLowerCase())
						&& !key.equalsIgnoreCase("content-type")
						&& !customKeys.contains(key.toLowerCase())
						&& !value.isEmpty()) {
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
					// setHeader ersetzt z. B. den Default-Content-Type, statt einen zweiten Wert anzuhängen
					builder.setHeader(key, value);
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

	private String formatErrorResponse(Exception e) {
		String summary = "Unbekannter Fehler";
		String detail = e.getMessage() != null ? e.getMessage() : "Keine Nachricht";

		if (e instanceof java.net.UnknownHostException) {
			summary = "DNS Fehler: Host nicht gefunden. (Service-Name korrekt? Namespace vergessen?)";
		} else if (e instanceof java.net.ConnectException) {
			summary = "Verbindung abgelehnt: Der Ziel-Port ist zu oder der Pod läuft nicht.";
		} else if (e instanceof java.net.http.HttpConnectTimeoutException) {
			summary = "Timeout: Verbindung dauerte zu lange. (NetworkPolicy Blockade?)";
		} else if (e instanceof java.lang.IllegalArgumentException && detail.contains("URI")) {
			summary = "Ungültige URL: Das Format der Ziel-Adresse ist fehlerhaft.";
		}

		String message = String.format("%s\nDetails: %s", summary, detail);
		if (!includeStacktrace) {
			return message;
		}

		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));

		return String.format("%s\n---STACKTRACE---\n%s", message, sw.toString());
	}
}
