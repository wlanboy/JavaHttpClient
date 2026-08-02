package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.IstioDiagnosticService;
import com.wlanboy.javahttpclient.client.TlsInspectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/k8s")
@Tag(name = "Kubernetes Diagnostics", description = "Diagnoseinformationen zu Kubernetes-Kontext und Istio-Sidecar")
public class DiagnosticController {

    private final IstioDiagnosticService istioService;
    private final TlsInspectorService tlsService;

    public DiagnosticController(IstioDiagnosticService istioService, TlsInspectorService tlsService) {
        this.istioService = istioService;
        this.tlsService = tlsService;
    }

    /**
     * Liefert den allgemeinen K8s Kontext (Namespace, Pod, Sidecar Status).
     * Wird beim Laden der Seite für die Navbar verwendet.
     */
    @Operation(
        summary = "K8s-Kontext abrufen",
        description = "Liefert Namespace, Pod-Name und Istio-Sidecar-Status. Wird typischerweise beim Laden der Navbar verwendet."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "K8s-Kontextdaten erfolgreich abgerufen")
    })
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getContext() {
        return ResponseEntity.ok(istioService.getContext());
    }

    /**
     * Der Deep-Dive Report: Holt alle Envoy-Configs und Fehler-Statistiken.
     */
    @Operation(
        summary = "Vollständigen Istio-Report abrufen",
        description = "Holt alle Envoy-Konfigurationen und Fehlerstatistiken des Istio-Sidecars. Im Fehlerfall wird 200 mit Fehlerbeschreibung im Body zurückgegeben."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Report generiert (enthält ggf. Fehlerbeschreibung im Body)")
    })
    @GetMapping("/istio/full-report")
    public ResponseEntity<Map<String, Object>> getFullReport() {
        return ResponseEntity.ok(istioService.getFullSidecarDetails());
    }

    /**
     * Liefert den K8s-Client-Initialisierungsstatus und unterstützte Istio-Typen.
     */
    @Operation(
        summary = "K8s-Client-Status abrufen",
        description = "Zeigt ob der Kubernetes API Client erfolgreich initialisiert wurde, und listet die unterstützten Istio-Ressourcentypen und API-Versionen."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "K8s-Clientstatus")
    })
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getK8sStatus() {
        return ResponseEntity.ok(istioService.getK8sStatus());
    }

    /**
     * Listet spezifische Istio Ressourcen (VirtualServices, DestinationRules, etc.).
     */
    @Operation(
        summary = "Istio-Ressourcen auflisten",
        description = "Listet spezifische Istio-Ressourcen eines Namespaces auf. Unterstützte Typen: `virtualservices`, `destinationrules`, `gateways`, `serviceentries`, `sidecars`, `envoyfilters`, `peerauthentications`, `requestauthentications`, `authorizationpolicies`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liste der Istio-Ressourcen",
            content = @Content(array = @ArraySchema(schema = @Schema(type = "object")))),
        @ApiResponse(responseCode = "400", description = "Ungültiger Ressourcentyp", content = @Content)
    })
    @GetMapping("/istio/{type}")
    public ResponseEntity<?> getIstioResources(
            @Parameter(description = "Istio-Ressourcentyp", example = "virtualservices") @PathVariable String type,
            @Parameter(description = "Kubernetes-Namespace", example = "default") @RequestParam(defaultValue = "default") String namespace) {
        try {
            return ResponseEntity.ok(istioService.getIstioResources(namespace, type));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(
        summary = "URL mit VirtualService/DestinationRule korrelieren",
        description = "Prüft ob die angegebene URL durch einen VirtualService abgedeckt ist und welche Routen/DestinationRules zutreffen."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Korrelationsergebnis erfolgreich abgerufen")
    })
    @GetMapping("/correlate")
    public ResponseEntity<Map<String, Object>> correlateUrl(
            @Parameter(description = "Ziel-URL", example = "http://my-service.default.svc.cluster.local/api/v1")
            @RequestParam String url,
            @Parameter(description = "Kubernetes-Namespace", example = "default")
            @RequestParam(defaultValue = "default") String namespace) {
        return ResponseEntity.ok(istioService.correlateUrl(url, namespace));
    }

    @Operation(
        summary = "Über Envoy erreichbare Services auflisten",
        description = "Parst die Envoy Admin API (/clusters) und liefert alle über den Sidecar erreichbaren Outbound-Services mit Host und Port, z.B. für ein Service-Auswahl-Dropdown."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liste der über Envoy erreichbaren Services",
            content = @Content(array = @ArraySchema(schema = @Schema(type = "object"))))
    })
    @GetMapping("/services")
    public ResponseEntity<List<Map<String, Object>>> getReachableServices() {
        return ResponseEntity.ok(istioService.getReachableServices());
    }

    @Operation(
        summary = "TLS-Zertifikat inspizieren",
        description = "Baut eine separate TLS-Verbindung zum Ziel auf und gibt Protokoll, Cipher Suite, Zertifikatskette und SPIFFE/mTLS-Informationen zurück."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "TLS-Informationen erfolgreich abgerufen")
    })
    @GetMapping("/tls")
    public ResponseEntity<Map<String, Object>> inspectTls(
            @Parameter(description = "Ziel-URL (muss https:// sein)", example = "https://example.com")
            @RequestParam String url) {
        return ResponseEntity.ok(tlsService.inspect(url));
    }
}