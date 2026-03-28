# JavaHttpClient – Projektdokumentation

Diagnose-Tool für HTTP-Konnektivitätsprobleme in Kubernetes-Clustern mit Istio Service Mesh.
Läuft als Pod im Cluster und ermöglicht:

- HTTP-Requests über einen Java-HTTP-Client an beliebige Ziel-URLs zu senden
- Istio-Sidecar (Envoy) Konfiguration und Fehler-Metriken auszuwerten
- VirtualService/DestinationRule/ServiceEntry-Konfiguration mit einer Ziel-URL zu korrelieren
- TLS-Zertifikatsketten und mTLS/SPIFFE-Identitäten zu inspizieren
- Redirect-Chains, Protokollversionen und DNS-Auflösungen sichtbar zu machen

---

## Tech Stack

| Schicht | Technologie |
|---------|-------------|
| Backend | Spring Boot 4.0.3, Java 25, Jetty |
| HTTP-Client | `java.net.http.HttpClient` (JDK built-in) |
| K8s-Integration | Official Kubernetes Java Client v25.0.0 |
| Frontend | Vanilla JS, Bootstrap 5.3, Bootstrap Icons |
| API-Doku | SpringDoc OpenAPI 3.0.1 (`/swagger-ui.html`, `/api-docs`) |
| Deployment | Docker (AOT-Build), Helm, Istio Gateway |

---

## Architektur

```txt
Browser
  │
  ├─ POST /client              → HttpClientController → ClientService
  │                                                       ├─ HTTP/2-Client (NEVER redirect)
  │                                                       ├─ Manuelle Redirect-Chain
  │                                                       ├─ DNS-Auflösung (InetAddress)
  │                                                       └─ Response-Header:
  │                                                           X-Protocol-Version
  │                                                           X-Redirect-Chain
  │                                                           X-Resolved-IP
  │
  ├─ GET /api/k8s/context      → DiagnosticController → K8sDiagnosticService
  ├─ GET /api/k8s/status       →        ↑
  ├─ GET /api/k8s/istio/{type} →        ↑
  ├─ GET /api/k8s/istio/full-report →   ↑  (Envoy Admin API: /stats, /clusters, /config_dump)
  ├─ GET /api/k8s/correlate    →        ↑  (VS/DR/SE-Matching gegen URL)
  └─ GET /api/k8s/tls          → DiagnosticController → TlsInspectorService (SSLSocket-Probe)
```

---

## Backend – Klassen

### `ClientService`

Sendet HTTP-Requests über `java.net.http.HttpClient`.

- **HTTP/2** aktiv (`Version.HTTP_2`), fällt automatisch auf HTTP/1.1 zurück (ALPN)
- **`Redirect.NEVER`** – Redirects werden manuell verfolgt (bis zu 10 Hops)
  - 307/308: Methode + Body beibehalten
  - 301/302/303: → GET, kein Body
  - Folge-Requests erhalten **keine** originalen Browser-Header (verhindert Auth-Token-Leak)
- **DNS-Auflösung** vor dem Request: `InetAddress.getAllByName()` → alle A/AAAA-Records
- **Response-Header** die zurückgegeben werden:
  - `X-Protocol-Version`: `HTTP/2` oder `HTTP/1.1`
  - `X-Redirect-Chain`: JSON-Array der Zwischenschritte `[{from, status, to, proto}]`
  - `X-Resolved-IP`: kommagetrennte IPs

Gefilterte Request-Header (werden nie weitergeleitet):
`host`, `content-length`, `connection`, `accept-encoding`, `upgrade`

Fehlermeldungen (502):

| Exception | Meldung |
|-----------|---------|
| `UnknownHostException` | DNS Fehler: Host nicht gefunden |
| `ConnectException` | Verbindung abgelehnt: Port zu / Pod läuft nicht |
| `HttpConnectTimeoutException` | Timeout: NetworkPolicy Blockade? |
| `IllegalArgumentException` (URI) | Ungültige URL |

---

### `K8sDiagnosticService`

**Envoy Admin API** (Standard: `http://127.0.0.1:15000`, konfigurierbar via `ENVOY_ADMIN_URL`):

- `/config_dump` → vollständige Envoy-Konfiguration
- `/clusters` → aktive Upstream-Cluster
- `/stats` → **alle** Metriken (kein serverseitiger Filter), nur Werte ≥ 0 (non-zero)

**Istio-Sidecar-Erkennung**: Socket-Test auf `127.0.0.1:15021`

**Unterstützte Istio-Ressourcentypen** (API-Versionen v1 → v1beta1 → v1alpha3 Fallback):
`virtualservices`, `destinationrules`, `gateways`, `serviceentries`, `sidecars`,
`envoyfilters`, `peerauthentications`, `requestauthentications`, `authorizationpolicies`

**Gefilterte Metriken**: `cluster.xds-grpc.*` wird aus `activeErrorMetrics` entfernt
(interner Istio Control-Plane-Kanal, kein App-Traffic)

**`diagnoseMetrics()`** – automatische Problemerkennung aus Envoy-Stats:

| Pattern | Severity | Diagnose |
|---------|----------|----------|
| `upstream_cx_connect_fail` | KRITISCH | Verbindung abgelehnt (Connection refused) |
| `upstream_rq_pending_overflow` | KRITISCH | Circuit Breaker / Pool-Overflow |
| `upstream_cx_none_healthy` | KRITISCH | Keine gesunden Endpoints |
| `upstream_rq_timeout` | WARNUNG | Request Timeouts (NetworkPolicy?) |
| `upstream_cx_connect_timeout` | WARNUNG | Connection Timeout |
| `upstream_rq_5xx` | WARNUNG | 5xx Fehler vom Upstream |
| `upstream_rq_retry_limit_exceeded` | INFO | Retry-Limit überschritten |
| `upstream_cx_destroy_remote_with_active_rq` | INFO | Verbindung mit aktiven Requests abgebrochen |

**`correlateUrl(url, namespace)`** – URL-Korrelation gegen Istio-Ressourcen:

*Host-Matching-Logik* (VS-Host vs. URL-Hostname):

- Exakter Match
- Wildcard: `*.namespace` matcht `foo.namespace`
- Short-Name: `my-svc` matcht `my-svc.ns.svc.cluster.local`
- Prefix: `my-svc.ns` matcht `my-svc.ns.svc.cluster.local`

*VirtualService*: prüft HTTP-Routen auf Pfad-Match (`exact` / `prefix` / `regex`)
→ gibt matched Routes inkl. Destinations, Timeout, Retries, Fault-Injection zurück

*DestinationRule*: prüft Host-Match
→ gibt TrafficPolicy (connectionPool, outlierDetection, loadBalancer) und Subsets zurück

*ServiceEntry*: prüft Host-Match + Port-Match gegen `spec.ports`
→ gibt location, resolution, endpoints, subjectAltNames zurück
→ Warnung wenn angeforderter Port nicht in `spec.ports` definiert

---

### `TlsInspectorService`

Separater `SSLSocket`-basierter TLS-Probe (unabhängig vom Haupt-Request):

- Eigener `SSLContext` mit `X509ExtendedTrustManager` der **alles akzeptiert** (auch self-signed, expired) → Chain immer sichtbar
- SNI korrekt gesetzt via `SSLParameters`
- Gibt zurück:
  - `tlsVersion`: `TLSv1.3` / `TLSv1.2`
  - `cipherSuite`
  - `isMtls`: `true` wenn Leaf-Cert eine `URI:spiffe://`-SAN hat
  - `spiffeId`: extrahierte SPIFFE-URI (Istio-Workload-Identität)
  - `chain`: Array pro Zertifikat mit subject, issuer, serial, validFrom, validTo, daysUntilExpiry, expired, subjectAltNames
- Wird nur für `https://`-URLs aufgerufen

**Wichtig**: Separate Verbindung – mögliche marginale Abweichung zu vom HttpClient genutzter Verbindung bei sehr kurzlebigen DNS-Einträgen.

---

## API-Endpoints

| Method | Path | Beschreibung |
|--------|------|-------------|
| `POST` | `/client` | HTTP-Request weiterleiten |
| `GET` | `/api/k8s/context` | Pod-Name, Namespace, Istio-Status |
| `GET` | `/api/k8s/status` | K8s-Client-Initialisierungsstatus |
| `GET` | `/api/k8s/istio/full-report` | Envoy Config + Fehler-Metriken + Diagnosen |
| `GET` | `/api/k8s/istio/{type}?namespace=` | Istio-Ressourcen eines Typs |
| `GET` | `/api/k8s/correlate?url=&namespace=` | URL gegen VS/DR/SE korrelieren |
| `GET` | `/api/k8s/tls?url=` | TLS-Zertifikatskette inspizieren |

---

## Frontend – Tabs & Features

### Hauptbereich (linke Spalte)

- HTTP-Method-Dropdown + URL-Feld
- Dynamische Custom-Header (Key/Value, add/remove)
- Body-Textarea (JSON)
- Option: Browser-Header kopieren
- Buttons: **K8s/Istio Diagnose** | **Send Request**

**Response-Bereich** nach jedem Request:

- `HTTP {status}`-Badge (grün/rot)
- `HTTP/2` / `HTTP/1.1`-Badge (blau/grau) – tatsächlich verwendetes Protokoll
- Resolved-IP-Badge (alle aufgelösten IPs)
- Dauer in ms
- **Redirect-Chain**: aufklappbare Schritte `URL → 301 HTTP/1.1 → URL → 302 HTTP/2 → ...`
- **TLS-Panel** (nur HTTPS): TLS-Version, Cipher Suite, mTLS/SPIFFE-Badge, aufklappbare Cert-Cards pro Zertifikat (leaf/intermediate/root) mit Ablauf-Countdown

### Historie (rechte Spalte)

- Bis zu 50 Einträge in `localStorage`
- Grüner/roter Rand je nach Status
- Klick stellt Formular wieder her
- Export als JSON

### Istio-Panel (nach Diagnose-Klick)

**Tab A – Config & Erreichbarkeit**

- Envoy Config JSON (aufklappbar)
- Cluster-Suche mit Zähler
- Aktive Endpoints als scrollbares Konsolen-Output

**Tab B – Aktive Fehler-Metriken**

- Ziel-URL-Korrelation: hebt Metriken hervor die den Hostnamen der URL enthalten
- Diagnose-Karten (KRITISCH/WARNUNG/INFO) mit Beschreibung + Empfehlung + betroffene Metriken
- Vollständige Metriken-Tabelle (alle Werte inkl. 0)

**Tab C – Pod Kontext & Istio**

- Navbar-Badges: Pod-Name, Namespace, Istio ON/OFF
- Kontext-Tabelle: podName, namespace, istioSidecar
- Istio Sidecar Details (clusterSummary, networkStats)
- **URL-Korrelations-Report** (wenn URL eingegeben):
  - `Match gefunden` (grün) oder `Kein Match` (gelb)
  - VirtualServices: pro Route ob Pfad-Match + Destinations + Timeout/Retries/FaultInjection
  - DestinationRules: TrafficPolicy-Badges + Subsets mit Labels
  - ServiceEntries: location, resolution, Ports, Port-Match-Warnung
- Istio-Ressourcen-Liste (alle 9 Typen):
  - Gematchte Ressourcen: grüner Rand + `URL-Match`-Badge + automatisch aufgeklappt
  - Nicht gematchte: zugeklappt
- K8s-Client-Status

---

## Konfiguration

| Variable | Default | Beschreibung |
|----------|---------|-------------|
| `ENVOY_ADMIN_URL` | `http://127.0.0.1:15000` | Envoy Admin API Adresse |
| `KUBERNETES_NAMESPACE` | `default` | Fallback wenn Namespace-Datei nicht lesbar |

Namespace wird primär aus `/var/run/secrets/kubernetes.io/serviceaccount/namespace` gelesen.

---

## Deployment

```txt
Namespace:   clients
Service:     ClusterIP :8080
Istio:       Gateway in istio-ingress
TLS:         cert-manager ClusterIssuer
Hosts:       javahttpclient.tp.lan / javahttpclient.gmk.lan
```

**Docker-Builds:**

- `Dockerfile25` – Java 25 mit JRE
- `Dockerfile25Jlink` – Java 25 mit JLink Custom Image (~295MB vs ~510MB)
- AOT aktiv: `spring-boot:process-aot` im Build, `-Dspring.aot.enabled=true` zur Laufzeit

---

## Bekannte Einschränkungen

- **TLS-Inspektion**: Separate Verbindung – bei extrem kurzlebigen DNS-Einträgen kann die angezeigte IP marginal abweichen
- **mTLS via Istio**: Wenn Traffic durch Envoy-Sidecar (iptables intercept) geht, handelt Envoy das TLS – Java sieht dann ggf. plain HTTP intern. TLS-Panel zeigt in dem Fall das Cert des Envoy-Proxys (SPIFFE-Cert), nicht des Zielservices direkt.
- **HTTP/2 h2c**: Cleartext HTTP/2 funktioniert nur wenn der Zielserver h2c-Upgrade unterstützt; sonst automatischer Fallback auf HTTP/1.1
- **Redirect Auth-Header**: Browser-Header werden nur beim ersten Hop mitgeschickt (Schutz vor Token-Leak bei Cross-Domain-Redirects)
- **Envoy-Stats**: `upstream_cx_connect_fail` in den Diagnosen greift nur wenn der Traffic durch den Envoy-Sidecar proxied wird
- **K8s-API**: ServiceAccount muss RBAC-Rechte auf Istio Custom Resources haben (get/list)
