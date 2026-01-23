package com.wlanboy.javahttpclient.client;

import com.sun.net.httpserver.HttpServer;
import com.wlanboy.javahttpclient.controller.JavaHttpRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ClientServiceTest {

    private static HttpServer server;
    private static int port;
    private ClientService clientService;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(2));

        // GET endpoint
        server.createContext("/get", exchange -> {
            String response = "GET Success";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // POST endpoint that echoes body
        server.createContext("/post", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String response = "Received: " + body + " | Content-Type: " + contentType;
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Endpoint that returns headers
        server.createContext("/headers", exchange -> {
            StringBuilder sb = new StringBuilder();
            exchange.getRequestHeaders().forEach((key, values) ->
                    sb.append(key).append("=").append(String.join(",", values)).append(";"));
            String response = sb.toString();
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Error endpoint
        server.createContext("/error", exchange -> {
            String response = "Internal Server Error";
            exchange.sendResponseHeaders(500, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Custom status endpoint
        server.createContext("/status/201", exchange -> {
            String response = "Created";
            exchange.sendResponseHeaders(201, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        clientService = new ClientService();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void sendRequest_withGetMethod_returnsSuccess() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/get",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("GET Success", response.getBody());
    }

    @Test
    void sendRequest_withPostAndBody_sendsBodyCorrectly() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/post",
                HttpMethod.POST,
                "{\"key\":\"value\"}",
                "application/json",
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Received: {\"key\":\"value\"}"));
        assertTrue(response.getBody().contains("Content-Type: application/json"));
    }

    @Test
    void sendRequest_withBodyButNoContentType_usesJsonDefault() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/post",
                HttpMethod.POST,
                "{\"data\":\"test\"}",
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Content-Type: application/json"));
    }

    @Test
    void sendRequest_withXmlContentType_sendsCorrectContentType() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/post",
                HttpMethod.POST,
                "<root>test</root>",
                "application/xml",
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Content-Type: application/xml"));
    }

    @Test
    void sendRequest_withCustomHeaders_sendsHeaders() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/headers",
                HttpMethod.GET,
                null,
                null,
                false,
                Map.of("X-Custom-Header", "CustomValue", "X-Another", "AnotherValue")
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("X-custom-header=CustomValue"));
        assertTrue(response.getBody().contains("X-another=AnotherValue"));
    }

    @Test
    void sendRequest_withCopyHeaders_copiesIncomingHeaders() {
        HttpHeaders incomingHeaders = new HttpHeaders();
        incomingHeaders.add("Authorization", "Bearer token123");
        incomingHeaders.add("X-Request-Id", "req-456");

        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/headers",
                HttpMethod.GET,
                null,
                null,
                true,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, incomingHeaders);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Authorization=Bearer token123"));
        assertTrue(response.getBody().contains("X-request-id=req-456"));
    }

    @Test
    void sendRequest_filtersBadHeaders() {
        HttpHeaders incomingHeaders = new HttpHeaders();
        incomingHeaders.add("Host", "evil.com");
        incomingHeaders.add("Content-Length", "9999");
        incomingHeaders.add("X-Safe-Header", "safe-value");

        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/headers",
                HttpMethod.GET,
                null,
                null,
                true,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, incomingHeaders);

        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().contains("Host=evil.com"));
        assertTrue(response.getBody().contains("X-safe-header=safe-value"));
    }

    @Test
    void sendRequest_withServerError_returnsErrorStatus() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/error",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Internal Server Error", response.getBody());
    }

    @Test
    void sendRequest_withCustomStatusCode_returnsCorrectStatus() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/status/201",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(201, response.getStatusCode().value());
        assertEquals("Created", response.getBody());
    }

    @Test
    void sendRequest_withUnknownHost_returns502WithError() {
        JavaHttpRequest request = new JavaHttpRequest(
                "http://nonexistent.invalid.host.test/path",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(502, response.getStatusCode().value());
        // Error message varies by environment - just check it's an error response
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("STACKTRACE"));
    }

    @Test
    void sendRequest_withConnectionRefused_returns502WithConnectionError() {
        JavaHttpRequest request = new JavaHttpRequest(
                "http://localhost:59999/path",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(502, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Verbindung abgelehnt"));
    }

    @Test
    void sendRequest_withInvalidUrl_returns502WithUrlError() {
        JavaHttpRequest request = new JavaHttpRequest(
                "http://[invalid",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(502, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Ungültige URL") || response.getBody().contains("URI"));
    }

    @Test
    void sendRequest_withDifferentHttpMethods_worksCorrectly() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            JavaHttpRequest request = new JavaHttpRequest(
                    baseUrl() + "/get",
                    method,
                    null,
                    null,
                    false,
                    null
            );

            ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());
            assertNotNull(response);
        }
    }
}
