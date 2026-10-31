package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wlanboy.javahttpclient.client.dto.TlsInspection;

import static org.junit.jupiter.api.Assertions.*;

class TlsInspectorServiceTest {

    private TlsInspectorService service;

    @BeforeEach
    void setUp() {
        service = new TlsInspectorService();
    }

    @Test
    void inspect_withHttpUrl_returnsError() {
        TlsInspection result = service.inspect("http://example.com");

        assertNotNull(result);
        assertNotNull(result.error(), "Result must contain an error for non-HTTPS URL");
        assertTrue(result.error().contains("Kein HTTPS"),
                "Error message should indicate HTTPS is required, got: " + result.error());
    }

    @Test
    void inspect_withInvalidUrl_returnsError() {
        // URI.create("not-a-url") throws IllegalArgumentException
        TlsInspection result = service.inspect("not-a-url");

        assertNotNull(result);
        // Either an error key is set (caught exception) or "Kein HTTPS" if the
        // scheme check runs first. Either way the result must not be empty and
        // must signal a problem.
        assertNotNull(result.error(), "Result must contain an error for an invalid URL, got: " + result);
    }

    @Test
    void inspect_withUnreachableHost_returnsError() {
        // This host will never resolve / accept a TCP connection
        TlsInspection result = service.inspect("https://nonexistent.invalid.host.test:443");

        assertNotNull(result);
        assertNotNull(result.error(), "Result must contain an error when the host is unreachable, got: " + result);
    }

    @Test
    void inspect_serviceInitialization_doesNotThrow() {
        assertDoesNotThrow(() -> new TlsInspectorService());
    }
}
