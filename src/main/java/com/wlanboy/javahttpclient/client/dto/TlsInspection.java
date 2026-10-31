package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ergebnis der TLS-Inspektion")
public record TlsInspection(
        String host,
        Integer port,
        String tlsVersion,
        String cipherSuite,
        Boolean isMtls,
        @Schema(description = "Nur gesetzt bei SPIFFE-Zertifikat (Istio mTLS)")
        String spiffeId,
        List<CertificateInfo> chain,
        String error) {

    public static TlsInspection error(String host, Integer port, String error) {
        return new TlsInspection(host, port, null, null, null, null, null, error);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Zertifikat aus der Server-Chain")
    public record CertificateInfo(
            int index,
            @Schema(allowableValues = {"leaf", "intermediate", "root"})
            String type,
            String subject,
            String issuer,
            String serial,
            String validFrom,
            String validTo,
            long daysUntilExpiry,
            boolean expired,
            List<String> subjectAltNames) {}
}
