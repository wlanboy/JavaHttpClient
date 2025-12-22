package com.wlanboy.javahttpclient.controller;

import com.wlanboy.javahttpclient.client.K8sDiagnosticService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/k8s")
public class DiagnosticController {

    private final K8sDiagnosticService diagnosticService;

    public DiagnosticController(K8sDiagnosticService diagnosticService) {
        this.diagnosticService = diagnosticService;
    }

    @GetMapping("/context")
    public Map<String, Object> getContext() {
        return diagnosticService.getContext();
    }

    @GetMapping("/istio/{type}")
    public List<Object> getIstioConfig(@PathVariable String type, @RequestParam String namespace) {
        return diagnosticService.getIstioResources(namespace, type);
    }
}