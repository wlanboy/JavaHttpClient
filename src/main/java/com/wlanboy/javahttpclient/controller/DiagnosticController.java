package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.IstioDiagnosticService;
import com.wlanboy.javahttpclient.client.K8sApiException;
import com.wlanboy.javahttpclient.client.TlsInspectorService;
import com.wlanboy.javahttpclient.client.dto.ErrorResponse;
import com.wlanboy.javahttpclient.client.dto.K8sContext;
import com.wlanboy.javahttpclient.client.dto.K8sStatus;
import com.wlanboy.javahttpclient.client.dto.ReachableService;
import com.wlanboy.javahttpclient.client.dto.SidecarReport;
import com.wlanboy.javahttpclient.client.dto.TlsInspection;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<K8sContext> getContext() {
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
    public ResponseEntity<SidecarReport> getFullReport() {
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
    public ResponseEntity<K8sStatus> getK8sStatus() {
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
        @ApiResponse(responseCode = "400", description = "Ungültiger Ressourcentyp",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "K8s API verweigert den Zugriff (z. B. fehlende RBAC-Rechte) oder ist nicht erreichbar",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/istio/{type}")
    public ResponseEntity<?> getIstioResources(
            @Parameter(description = "Istio-Ressourcentyp", example = "virtualservices") @PathVariable String type,
            @Parameter(description = "Kubernetes-Namespace", example = "default") @RequestParam(defaultValue = "default") String namespace) {
        try {
            return ResponseEntity.ok(istioService.getIstioResources(namespace, type));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (K8sApiException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
        summary = "URL mit Istio-Config korrelieren",
        description = "Prüft ob die angegebene URL durch VirtualService/DestinationRule/ServiceEntry abgedeckt ist und ob AuthorizationPolicy/PeerAuthentication/Sidecar-Egress den Zugriff erlauben. Der Ziel-Namespace wird automatisch aus dem FQDN-Host abgeleitet (service.namespace.svc.cluster.local) und zusätzlich zum übergebenen Source-Namespace abgefragt."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Korrelationsergebnis erfolgreich abgerufen")
    })
    @GetMapping("/correlate")
    public ResponseEntity<UrlCorrelation> correlateUrl(
            @Parameter(description = "Ziel-URL", example = "http://my-service.default.svc.cluster.local/api/v1")
            @RequestParam String url,
            @Parameter(description = "Source-Namespace (Namespace des Aufrufers)", example = "default")
            @RequestParam(defaultValue = "default") String namespace) {
        return ResponseEntity.ok(istioService.correlateUrl(url, namespace));
    }

    @Operation(
        summary = "Über Envoy erreichbare Services auflisten",
        description = "Parst die Envoy Admin API (/clusters) und liefert alle über den Sidecar erreichbaren Outbound-Services mit Host und Port, z.B. für ein Service-Auswahl-Dropdown."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liste der über Envoy erreichbaren Services")
    })
    @GetMapping("/services")
    public ResponseEntity<List<ReachableService>> getReachableServices() {
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
    public ResponseEntity<TlsInspection> inspectTls(
            @Parameter(description = "Ziel-URL (muss https:// sein)", example = "https://example.com")
            @RequestParam String url) {
        return ResponseEntity.ok(tlsService.inspect(url));
    }
}