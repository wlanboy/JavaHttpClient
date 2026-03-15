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
        server.setExecutor(Executors.newFixedThreadPool(4));

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

        // Redirect endpoints
        server.createContext("/redirect/301", exchange -> {
            exchange.getResponseHeaders().set("Location", "/get");
            exchange.sendResponseHeaders(301, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/redirect/302", exchange -> {
            exchange.getResponseHeaders().set("Location", "/get");
            exchange.sendResponseHeaders(302, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/redirect/303", exchange -> {
            exchange.getResponseHeaders().set("Location", "/get");
            exchange.sendResponseHeaders(303, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/redirect/307", exchange -> {
            exchange.getResponseHeaders().set("Location", "/post");
            exchange.sendResponseHeaders(307, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/redirect/308", exchange -> {
            exchange.getResponseHeaders().set("Location", "/post");
            exchange.sendResponseHeaders(308, -1);
            exchange.getResponseBody().close();
        });

        server.createContext("/redirect/circular", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect/circular");
            exchange.sendResponseHeaders(302, -1);
            exchange.getResponseBody().close();
        });

        // /redirect/chain → 301 → /redirect/302 → 302 → /get
        server.createContext("/redirect/chain", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect/302");
            exchange.sendResponseHeaders(301, -1);
            exchange.getResponseBody().close();
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

    // -------------------------------------------------------------------------
    // Redirect tests
    // -------------------------------------------------------------------------

    @Test
    void sendRequest_with301Redirect_followsRedirect() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/301",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("GET Success", response.getBody());

        // The redirect chain header must be present and mention status 301
        String chain = response.getHeaders().getFirst("X-Redirect-Chain");
        assertNotNull(chain, "X-Redirect-Chain header should be present after a redirect");
        assertTrue(chain.contains("\"status\":301"),
                "Redirect chain should contain a step with status 301, got: " + chain);
    }

    @Test
    void sendRequest_with302Redirect_followsRedirect() {
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/302",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("GET Success", response.getBody());

        String chain = response.getHeaders().getFirst("X-Redirect-Chain");
        assertNotNull(chain, "X-Redirect-Chain header should be present after a redirect");
        assertTrue(chain.contains("\"status\":302"),
                "Redirect chain should contain a step with status 302, got: " + chain);
    }

    @Test
    void sendRequest_with303Redirect_fromPost_becomeGet() {
        // POST to a 303 redirect; the 303 spec mandates the follow-up is always GET
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/303",
                HttpMethod.POST,
                "{\"data\":\"value\"}",
                "application/json",
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        // Final destination is /get which returns "GET Success"
        assertEquals(200, response.getStatusCode().value());
        assertEquals("GET Success", response.getBody());

        String chain = response.getHeaders().getFirst("X-Redirect-Chain");
        assertNotNull(chain, "X-Redirect-Chain header should be present after a 303 redirect");
        assertTrue(chain.contains("\"status\":303"),
                "Redirect chain should contain a step with status 303, got: " + chain);
    }

    @Test
    void sendRequest_with307Redirect_keepsPOSTMethod() {
        // 307 must keep the original method (POST) and body
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/307",
                HttpMethod.POST,
                "hello-body",
                "text/plain",
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        // Final destination is /post which echoes the body
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Received: hello-body"),
                "307 redirect must preserve the POST body, got: " + response.getBody());

        String chain = response.getHeaders().getFirst("X-Redirect-Chain");
        assertNotNull(chain, "X-Redirect-Chain header should be present after a 307 redirect");
        assertTrue(chain.contains("\"status\":307"),
                "Redirect chain should contain a step with status 307, got: " + chain);
    }

    @Test
    void sendRequest_withRedirectChain_capturesAllHops() {
        // /redirect/chain → 301 → /redirect/302 → 302 → /get (2 hops total)
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/chain",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        ResponseEntity<String> response = clientService.sendRequest(request, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("GET Success", response.getBody());

        String chain = response.getHeaders().getFirst("X-Redirect-Chain");
        assertNotNull(chain, "X-Redirect-Chain header should be present after a redirect chain");

        // Two redirect steps must appear – count occurrences of "\"status\":"
        long hopCount = chain.chars()
                .filter(c -> c == '{')
                .count();
        assertEquals(2, hopCount,
                "Redirect chain should capture exactly 2 hops for /redirect/chain, got chain: " + chain);
    }

    @Test
    void sendRequest_withCircularRedirect_stopsAtMaxRedirects() {
        // /redirect/circular points back to itself – ClientService must stop after MAX_REDIRECTS
        JavaHttpRequest request = new JavaHttpRequest(
                baseUrl() + "/redirect/circular",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );

        // Must not throw and must return a response within a reasonable time
        ResponseEntity<String> response = assertDoesNotThrow(
                () -> clientService.sendRequest(request, new HttpHeaders()),
                "Circular redirect must not cause an infinite loop or exception"
        );

        assertNotNull(response, "Response must not be null for circular redirect");
        // After MAX_REDIRECTS the service returns whatever the last response was (another 302)
        // or a 502 – either way it must be a valid HTTP status code
        assertTrue(response.getStatusCode().value() >= 100 && response.getStatusCode().value() < 600,
                "Response status must be a valid HTTP status code, got: " + response.getStatusCode().value());
    }

    // -------------------------------------------------------------------------
    // Diagnostic header tests
    // -------------------------------------------------------------------------

    @Test
    void sendRequest_success_hasProtocolVersionHeader() {
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
        String protocolVersion = response.getHeaders().getFirst("X-Protocol-Version");
        assertNotNull(protocolVersion, "X-Protocol-Version header must be present");
        // The embedded test server only speaks HTTP/1.1; the Java HttpClient may
        // negotiate HTTP/2 but will fall back to HTTP/1.1 for plain HTTP.
        assertTrue(
                protocolVersion.equals("HTTP/1.1") || protocolVersion.equals("HTTP/2"),
                "X-Protocol-Version must be HTTP/1.1 or HTTP/2, got: " + protocolVersion
        );
    }

    @Test
    void sendRequest_success_hasResolvedIpHeader() {
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
        String resolvedIp = response.getHeaders().getFirst("X-Resolved-IP");
        assertNotNull(resolvedIp, "X-Resolved-IP header must be present when targeting localhost");
        assertFalse(resolvedIp.isBlank(), "X-Resolved-IP header must not be empty");
    }

    @Test
    void sendRequest_withNoRedirect_hasNoRedirectChainHeader() {
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
        assertNull(
                response.getHeaders().getFirst("X-Redirect-Chain"),
                "X-Redirect-Chain header must NOT be present when no redirect occurred"
        );
    }
}
