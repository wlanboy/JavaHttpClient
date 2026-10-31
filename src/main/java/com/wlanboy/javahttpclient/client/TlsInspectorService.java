package com.wlanboy.javahttpclient.client;

import com.wlanboy.javahttpclient.client.dto.TlsInspection;
import com.wlanboy.javahttpclient.client.dto.TlsInspection.CertificateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TlsInspectorService {

    private static final Logger logger = LoggerFactory.getLogger(TlsInspectorService.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public TlsInspection inspect(String url) {
        String host = null;
        Integer port = null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return TlsInspection.error(null, null, "Kein HTTPS – TLS-Inspektion nicht möglich.");
            }

            host = uri.getHost();
            port = uri.getPort() != -1 ? uri.getPort() : 443;

            AtomicReference<X509Certificate[]> chainRef = new AtomicReference<>();

            // TrustManager der alles akzeptiert, aber die Chain immer captured
            X509ExtendedTrustManager capturingTM = new X509ExtendedTrustManager() {
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) { chainRef.set(chain); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket s) { chainRef.set(chain); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { chainRef.set(chain); }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket s) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{capturingTM}, new SecureRandom());
            SSLSocketFactory factory = ctx.getSocketFactory();

            // TCP-Verbindung mit Timeout aufbauen, TLS danach darüberlegen
            try (Socket raw = connect(host, port);
                 SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true)) {

                // SNI setzen
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(params);

                SSLSession session = socket.getSession(); // löst Handshake aus

                Boolean isMtls = null;
                String spiffe = null;
                List<CertificateInfo> serializedChain = null;
                X509Certificate[] chain = chainRef.get();
                if (chain != null && chain.length > 0) {
                    spiffe = extractSpiffe(chain[0]);
                    isMtls = spiffe != null;
                    serializedChain = serializeChain(chain);
                }
                return new TlsInspection(host, port, session.getProtocol(), session.getCipherSuite(),
                        isMtls, spiffe, serializedChain, null);
            }

        } catch (Exception e) {
            logger.warn("TLS-Inspektion fehlgeschlagen für {}: {}", url, e.getMessage());
            return TlsInspection.error(host, port, e.getMessage());
        }
    }

    private Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    List<CertificateInfo> serializeChain(X509Certificate[] chain) {
        List<CertificateInfo> list = new ArrayList<>();
        for (int i = 0; i < chain.length; i++) {
            X509Certificate cert = chain[i];
            Instant now = Instant.now();
            Instant notAfter = cert.getNotAfter().toInstant();
            long daysLeft = ChronoUnit.DAYS.between(now, notAfter);
            List<String> sans = extractSans(cert);
            list.add(new CertificateInfo(
                    i,
                    i == 0 ? "leaf" : (i == chain.length - 1 ? "root" : "intermediate"),
                    cert.getSubjectX500Principal().getName(),
                    cert.getIssuerX500Principal().getName(),
                    cert.getSerialNumber().toString(16).toUpperCase(),
                    ISO.format(cert.getNotBefore().toInstant()),
                    ISO.format(cert.getNotAfter().toInstant()),
                    daysLeft,
                    // DAYS.between schneidet ab: seit wenigen Stunden abgelaufen ergäbe daysLeft = 0
                    notAfter.isBefore(now),
                    sans.isEmpty() ? null : sans));
        }
        return list;
    }

    private String extractSpiffe(X509Certificate cert) {
        List<String> sans = extractSans(cert);
        return sans.stream()
                .filter(s -> s.startsWith("URI:spiffe://"))
                .findFirst()
                .map(s -> s.substring(4)) // "URI:" prefix entfernen
                .orElse(null);
    }

    private List<String> extractSans(X509Certificate cert) {
        List<String> result = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return result;
            for (List<?> san : sans) {
                int type = (Integer) san.get(0);
                String prefix = switch (type) {
                    case 0 -> "OtherName";
                    case 1 -> "Email";
                    case 2 -> "DNS";
                    case 4 -> "DirName";
                    case 6 -> "URI";
                    case 7 -> "IP";
                    default -> "Type" + type;
                };
                Object raw = san.get(1);
                String value = (raw instanceof byte[] bytes)
                        ? HexFormat.of().formatHex(bytes)
                        : raw.toString();
                result.add(prefix + ":" + value);
            }
        } catch (CertificateParsingException ignored) {}
        return result;
    }
}
