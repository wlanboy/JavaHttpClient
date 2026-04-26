package com.wlanboy.javahttpclient.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
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
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public Map<String, Object> inspect(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                result.put("error", "Kein HTTPS – TLS-Inspektion nicht möglich.");
                return result;
            }

            String host = uri.getHost();
            int port = uri.getPort() != -1 ? uri.getPort() : 443;
            result.put("host", host);
            result.put("port", port);

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

            try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
                socket.setSoTimeout(5000);

                // SNI setzen
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(params);

                SSLSession session = socket.getSession(); // löst Handshake aus

                result.put("tlsVersion", session.getProtocol());
                result.put("cipherSuite", session.getCipherSuite());

                X509Certificate[] chain = chainRef.get();
                if (chain != null && chain.length > 0) {
                    String spiffe = extractSpiffe(chain[0]);
                    result.put("hasSpiffeIdentity", spiffe != null);
                    if (spiffe != null) result.put("spiffeId", spiffe);
                    result.put("chain", serializeChain(chain));
                }
            }

        } catch (Exception e) {
            logger.warn("TLS-Inspektion fehlgeschlagen für {}: {}", url, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> serializeChain(X509Certificate[] chain) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < chain.length; i++) {
            X509Certificate cert = chain[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            entry.put("type", i == 0 ? "leaf" : (i == chain.length - 1 ? "root" : "intermediate"));
            entry.put("subject", cert.getSubjectX500Principal().getName());
            entry.put("issuer", cert.getIssuerX500Principal().getName());
            entry.put("serial", cert.getSerialNumber().toString(16).toUpperCase());
            entry.put("validFrom", ISO.format(cert.getNotBefore().toInstant()));
            entry.put("validTo",   ISO.format(cert.getNotAfter().toInstant()));

            long daysLeft = ChronoUnit.DAYS.between(Instant.now(), cert.getNotAfter().toInstant());
            entry.put("daysUntilExpiry", daysLeft);
            entry.put("expired", daysLeft < 0);

            List<String> sans = extractSans(cert);
            if (!sans.isEmpty()) entry.put("subjectAltNames", sans);

            list.add(entry);
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
