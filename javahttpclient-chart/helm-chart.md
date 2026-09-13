# Helm chart description

Dieses Helm Chart deployt den JavaHttpClient in einem Kubernetes-Cluster mit Istio-Integration.
Es wird in den Namespace `clients` installiert, konfiguriert ein Istio Gateway mit TLS-Terminierung
und richtet einen VirtualService für das Routing ein.

## Voraussetzungen

- Kubernetes-Cluster mit installiertem Istio
- Helm 3.x
- ClusterIssuer `local-ca-issuer` (cert-manager) vorhanden, sofern `certManager.enabled=true` bleibt

## Installation

Namespace vorab mit dem Istio-Sidecar-Injection-Label anlegen. Ohne dieses Label wird der Sidecar
trotz der `sidecar.istio.io/inject`-Annotation im Deployment nicht injiziert, da der Istio-Webhook
per `namespaceSelector` auf `istio-injection=enabled` filtert:

```bash
kubectl create namespace clients
kubectl label namespace clients istio-injection=enabled
```

Anschließend das Chart installieren:

```bash
helm install httpclient . -n clients
```

Ohne SSL/TLS (kein cert-manager-Certificate, Gateway nur mit HTTP-Server):

```bash
helm install httpclient . -n clients --set certManager.enabled=false
```

## Deployment überprüfen

Nach der Installation den Release-Status sowie TLS-Secret, Gateway und VirtualService prüfen:

```bash
helm status httpclient -n clients
kubectl get secret httpclient-tls -n istio-ingress
kubectl get gateway,virtualservice -n clients
```

## Upgrade

Bei Änderungen an Chart oder Values ein Rolling-Update durchführen:

```bash
helm upgrade httpclient . -n clients
```

## Deinstallation

Chart und alle zugehörigen Kubernetes-Ressourcen entfernen:

```bash
helm uninstall httpclient -n clients
```
