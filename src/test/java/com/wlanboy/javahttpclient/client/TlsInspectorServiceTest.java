package com.wlanboy.javahttpclient.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wlanboy.javahttpclient.client.dto.TlsInspection;
import com.wlanboy.javahttpclient.client.dto.TlsInspection.CertificateInfo;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void serverNames_hostname_setsSni() {
        List<SNIServerName> names = TlsInspectorService.serverNames("example.com");

        assertEquals(List.of(new SNIHostName("example.com")), names);
    }

    @Test
    void serverNames_ipv4Literal_setsNoSni() {
        assertTrue(TlsInspectorService.serverNames("127.0.0.1").isEmpty());
    }

    @Test
    void serverNames_ipv6Literal_setsNoSni() {
        assertTrue(TlsInspectorService.serverNames("::1").isEmpty());
        assertTrue(TlsInspectorService.serverNames("2001:db8::1").isEmpty());
    }

    @Test
    void inspect_ipv6Url_sendsClientHello() throws Exception {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 1, InetAddress.getByName("::1"));
        } catch (IOException e) {
            assumeTrue(false, "IPv6-Loopback nicht verfügbar");
            return;
        }
        try (server) {
            CompletableFuture<Integer> received = CompletableFuture.supplyAsync(() -> {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    return s.getInputStream().read(new byte[512]);
                } catch (IOException e) {
                    return -1;
                }
            });

            TlsInspection result = service.inspect("https://[::1]:" + server.getLocalPort() + "/");

            assertEquals("::1", result.host());
            // Vor dem Fix scheiterte new SNIHostName("[::1]") noch vor dem Handshake
            assertTrue(received.get(10, TimeUnit.SECONDS) > 0, "Server muss einen ClientHello empfangen");
        }
    }

    @Test
    void serializeChain_certExpiredHoursAgo_isMarkedExpired() {
        X509Certificate cert = certExpiringAt(Instant.now().minus(Duration.ofHours(10)));

        CertificateInfo info = service.serializeChain(new X509Certificate[]{cert}).getFirst();

        assertEquals(0, info.daysUntilExpiry());
        assertTrue(info.expired(), "Certificate expired 10 hours ago must be marked expired");
    }

    @Test
    void serializeChain_certExpiringInHours_isNotExpired() {
        X509Certificate cert = certExpiringAt(Instant.now().plus(Duration.ofHours(10)));

        CertificateInfo info = service.serializeChain(new X509Certificate[]{cert}).getFirst();

        assertEquals(0, info.daysUntilExpiry());
        assertFalse(info.expired(), "Certificate still valid for 10 hours must not be marked expired");
    }

    private static X509Certificate certExpiringAt(Instant notAfter) {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test"));
        when(cert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=issuer"));
        when(cert.getSerialNumber()).thenReturn(BigInteger.ONE);
        when(cert.getNotBefore()).thenReturn(Date.from(notAfter.minus(Duration.ofDays(90))));
        when(cert.getNotAfter()).thenReturn(Date.from(notAfter));
        return cert;
    }
}
