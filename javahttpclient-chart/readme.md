## Helm chart usage
How to install the java based http client via helm.

```bash
helm install httpclient . -n clients --create-namespace
```

```bash
kubectl get secret httpclient-tls -n istio-ingress
kubectl get gateway,virtualservice -n clients
```

```bash
helm upgrade httpclient . -n clients 
```

```bash
helm uninstall httpclient -n clients
```
