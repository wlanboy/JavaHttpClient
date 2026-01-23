package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpClientControllerTest {

    @Mock
    private ClientService clientService;

    @InjectMocks
    private HttpClientController controller;

    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        headers = new HttpHeaders();
    }

    @Test
    void postMapping_withValidGetRequest_returnsResponse() {
        JavaHttpRequest request = new JavaHttpRequest(
                "https://example.com",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );
        when(clientService.sendRequest(eq(request), any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.ok("Success"));

        ResponseEntity<String> response = controller.postMapping(request, headers);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Success", response.getBody());
        verify(clientService).sendRequest(eq(request), any(HttpHeaders.class));
    }

    @Test
    void postMapping_withPostRequestAndBody_returnsResponse() {
        JavaHttpRequest request = new JavaHttpRequest(
                "https://api.example.com/data",
                HttpMethod.POST,
                "{\"key\": \"value\"}",
                "application/json",
                false,
                null
        );
        when(clientService.sendRequest(eq(request), any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.ok("{\"result\": \"ok\"}"));

        ResponseEntity<String> response = controller.postMapping(request, headers);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"result\": \"ok\"}", response.getBody());
    }

    @Test
    void postMapping_withCustomHeaders_passesHeadersToService() {
        JavaHttpRequest request = new JavaHttpRequest(
                "https://example.com",
                HttpMethod.GET,
                null,
                null,
                true,
                Map.of("X-Custom-Header", "CustomValue")
        );
        when(clientService.sendRequest(any(), any()))
                .thenReturn(ResponseEntity.ok("Headers sent"));

        ResponseEntity<String> response = controller.postMapping(request, headers);

        assertEquals(200, response.getStatusCode().value());
        verify(clientService).sendRequest(eq(request), eq(headers));
    }

    @Test
    void postMapping_withCopyHeadersEnabled_passesIncomingHeaders() {
        headers.add("Authorization", "Bearer token123");
        JavaHttpRequest request = new JavaHttpRequest(
                "https://example.com",
                HttpMethod.GET,
                null,
                null,
                true,
                null
        );
        when(clientService.sendRequest(any(), any()))
                .thenReturn(ResponseEntity.ok("OK"));

        controller.postMapping(request, headers);

        verify(clientService).sendRequest(eq(request), eq(headers));
    }

    @Test
    void postMapping_serviceReturnsError_returnsErrorStatus() {
        JavaHttpRequest request = new JavaHttpRequest(
                "https://example.com",
                HttpMethod.GET,
                null,
                null,
                false,
                null
        );
        when(clientService.sendRequest(any(), any()))
                .thenReturn(ResponseEntity.status(502).body("Gateway Error"));

        ResponseEntity<String> response = controller.postMapping(request, headers);

        assertEquals(502, response.getStatusCode().value());
        assertEquals("Gateway Error", response.getBody());
    }

    @Test
    void postMapping_withDifferentHttpMethods_callsService() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            JavaHttpRequest request = new JavaHttpRequest(
                    "https://example.com/resource",
                    method,
                    null,
                    null,
                    false,
                    null
            );
            when(clientService.sendRequest(any(), any()))
                    .thenReturn(ResponseEntity.ok("OK"));

            ResponseEntity<String> response = controller.postMapping(request, headers);

            assertEquals(200, response.getStatusCode().value());
        }
        verify(clientService, times(3)).sendRequest(any(), any());
    }

    @Test
    void postMapping_withXmlContentType_passesContentType() {
        JavaHttpRequest request = new JavaHttpRequest(
                "https://example.com/xml",
                HttpMethod.POST,
                "<root><item>test</item></root>",
                "application/xml",
                false,
                null
        );
        when(clientService.sendRequest(any(), any()))
                .thenReturn(ResponseEntity.ok("<response>ok</response>"));

        ResponseEntity<String> response = controller.postMapping(request, headers);

        assertEquals(200, response.getStatusCode().value());
        verify(clientService).sendRequest(eq(request), any());
    }
}
