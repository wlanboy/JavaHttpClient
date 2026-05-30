# Helm chart description

Dieses Helm Chart deployt den JavaHttpClient in einem Kubernetes-Cluster mit Istio-Integration.
Es erstellt den Namespace `clients`, konfiguriert ein Istio Gateway mit TLS-Terminierung und
richtet einen VirtualService für das Routing ein.

## Voraussetzungen

- Kubernetes-Cluster mit installiertem Istio
- Helm 3.x
- TLS-Secret `httpclient-tls` im Namespace `istio-ingress` vorhanden

## Installation

Beim ersten Deployment den Namespace automatisch erstellen lassen:

```bash
helm install httpclient . -n clients --create-namespace
```

## Deployment überprüfen

Nach der Installation den Status von TLS-Secret, Gateway und VirtualService prüfen:

```bash
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
