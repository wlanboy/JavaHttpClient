package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.K8sDiagnosticService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/k8s")
public class DiagnosticController {

    @Autowired
    private K8sDiagnosticService k8sService;

    /**
     * Liefert den allgemeinen K8s Kontext (Namespace, Pod, Sidecar Status).
     * Wird beim Laden der Seite für die Navbar verwendet.
     */
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getContext() {
        return ResponseEntity.ok(k8sService.getContext());
    }

    /**
     * Der Deep-Dive Report: Holt alle Envoy-Configs und Fehler-Statistiken.
     */
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
    @GetMapping("/istio/{type}")
    public ResponseEntity<List<Object>> getIstioResources(
            @PathVariable String type,
            @RequestParam(defaultValue = "default") String namespace) {
        return ResponseEntity.ok(k8sService.getIstioResources(namespace, type));
    }
}