package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TlsInspectorServiceTest {

    private TlsInspectorService service;

    @BeforeEach
    void setUp() {
        service = new TlsInspectorService();
    }

    @Test
    void inspect_withHttpUrl_returnsError() {
        Map<String, Object> result = service.inspect("http://example.com");

        assertNotNull(result);
        assertTrue(result.containsKey("error"),
                "Result must contain 'error' key for non-HTTPS URL");
        assertTrue(result.get("error").toString().contains("Kein HTTPS"),
                "Error message should indicate HTTPS is required, got: " + result.get("error"));
    }

    @Test
    void inspect_withInvalidUrl_returnsError() {
        // URI.create("not-a-url") throws IllegalArgumentException
        Map<String, Object> result = service.inspect("not-a-url");

        assertNotNull(result);
        // Either an error key is set (caught exception) or "Kein HTTPS" if the
        // scheme check runs first. Either way the result must not be empty and
        // must signal a problem.
        assertTrue(
                result.containsKey("error"),
                "Result must contain 'error' key for an invalid URL, got keys: " + result.keySet()
        );
    }

    @Test
    void inspect_withUnreachableHost_returnsError() {
        // This host will never resolve / accept a TCP connection
        Map<String, Object> result = service.inspect("https://nonexistent.invalid.host.test:443");

        assertNotNull(result);
        assertTrue(result.containsKey("error"),
                "Result must contain 'error' key when the host is unreachable, got keys: " + result.keySet());
    }

    @Test
    void inspect_serviceInitialization_doesNotThrow() {
        assertDoesNotThrow(() -> new TlsInspectorService());
    }
}
