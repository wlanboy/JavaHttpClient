package com.wlanboy.javahttpclient.client.dto;

import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.SidecarEgress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stellt sicher, dass die Records dasselbe JSON liefern wie die früheren Maps,
 * da das Frontend auf diese Feldnamen zugreift.
 */
class DtoSerializationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @Test
    void tlsInspection_keepsIsMtlsFieldName() {
        TlsInspection tls = new TlsInspection("example.com", 443, "TLSv1.3", "TLS_AES_256_GCM_SHA384",
                true, "spiffe://cluster.local/ns/default/sa/x", List.of(), null);

        String json = JSON_MAPPER.writeValueAsString(tls);

        assertEquals("{\"host\":\"example.com\",\"port\":443,\"tlsVersion\":\"TLSv1.3\","
                + "\"cipherSuite\":\"TLS_AES_256_GCM_SHA384\",\"isMtls\":true,"
                + "\"spiffeId\":\"spiffe://cluster.local/ns/default/sa/x\",\"chain\":[]}", json);
    }

    @Test
    void errorFactories_omitNullFields() {
        assertEquals("{\"error\":\"boom\"}", JSON_MAPPER.writeValueAsString(TlsInspection.error(null, null, "boom")));
        assertEquals("{\"error\":\"boom\"}", JSON_MAPPER.writeValueAsString(SidecarReport.error("boom")));
        assertEquals("{\"error\":\"boom\"}", JSON_MAPPER.writeValueAsString(UrlCorrelation.error("boom")));
    }

    @Test
    void k8sStatus_omitsInitErrorWhenAbsent() {
        String json = JSON_MAPPER.writeValueAsString(new K8sStatus(true, Set.of("gateways"), List.of("v1"), null));

        assertEquals("{\"initialized\":true,\"supportedIstioTypes\":[\"gateways\"],\"istioVersionsProbed\":[\"v1\"]}", json);
    }

    @Test
    void sidecarEgress_unrestricted_omitsHostFields() {
        String json = JSON_MAPPER.writeValueAsString(new SidecarEgress(false, "kein Scoping", null, null, null));

        assertEquals("{\"restricted\":false,\"info\":\"kein Scoping\"}", json);
    }
}
