package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.K8sDiagnosticService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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

    private final K8sDiagnosticService k8sService;

    public DiagnosticController(K8sDiagnosticService k8sService) {
        this.k8sService = k8sService;
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
        return ResponseEntity.ok(k8sService.getContext());
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
        Map<String, Object> report = k8sService.getFullSidecarDetails();
        
        if (report.containsKey("error")) {
            // Wir geben trotzdem 200 OK, damit das Frontend die Fehlermeldung 
            // innerhalb des Panels schön anzeigen kann.
            return ResponseEntity.ok(report);
        }
        
        return ResponseEntity.ok(report);
    }

    /**
     * Listet spezifische Istio Ressourcen (VirtualServices, DestinationRules).
     */
    @Operation(
        summary = "Istio-Ressourcen auflisten",
        description = "Listet spezifische Istio-Ressourcen eines Namespaces auf. Unterstützte Typen: `virtualservices`, `destinationrules`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Liste der Istio-Ressourcen"),
        @ApiResponse(responseCode = "400", description = "Ungültiger Ressourcentyp", content = @Content)
    })
    @GetMapping("/istio/{type}")
    public ResponseEntity<List<Object>> getIstioResources(
            @Parameter(description = "Istio-Ressourcentyp", example = "virtualservices") @PathVariable String type,
            @Parameter(description = "Kubernetes-Namespace", example = "default") @RequestParam(defaultValue = "default") String namespace) {
        return ResponseEntity.ok(k8sService.getIstioResources(namespace, type));
    }
}