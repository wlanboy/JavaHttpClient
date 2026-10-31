package com.wlanboy.javahttpclient.client;

import com.wlanboy.javahttpclient.client.dto.K8sContext;
import com.wlanboy.javahttpclient.client.dto.K8sContext.EnvoyDetails;
import com.wlanboy.javahttpclient.client.dto.K8sStatus;
import com.wlanboy.javahttpclient.client.dto.ReachableService;
import com.wlanboy.javahttpclient.client.dto.SidecarReport;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.ConfigDumpTruncated;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.Diagnosis;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.HealthDiagnostics;
import com.wlanboy.javahttpclient.client.dto.SidecarReport.Reachability;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.AuthorizationAnalysis;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.DestinationRuleMatch;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.HttpRouteAnalysis;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.PeerAuthenticationMatch;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.PolicyMatch;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.RouteDestination;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.ServiceEntryMatch;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.SidecarEgress;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.Subset;
import com.wlanboy.javahttpclient.client.dto.UrlCorrelation.VirtualServiceMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class IstioDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(IstioDiagnosticService.class);
    private static final String ISTIO_GROUP = "networking.istio.io";
    private static final List<String> ISTIO_VERSIONS = List.of("v1", "v1beta1", "v1alpha3");
    private static final Set<String> SUPPORTED_ISTIO_TYPES = Set.of(
            "virtualservices", "destinationrules", "gateways", "serviceentries",
            "sidecars", "envoyfilters", "peerauthentications", "requestauthentications",
            "authorizationpolicies");
    private static final Duration ENVOY_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ENVOY_READ_TIMEOUT = Duration.ofSeconds(10);

    private final K8sClientService k8sClientService;
    private final RestClient restClient;
    private final String envoyAdminUrl;
    private final String sidecarStatusHost;
    private final int sidecarStatusPort;
    private final DataSize maxConfigDumpSize;

    public IstioDiagnosticService(K8sClientService k8sClientService,
            @Value("${istio.envoy.config-dump.max-size:5MB}") DataSize maxConfigDumpSize,
            @Value("${istio.envoy.admin-url:${ENVOY_ADMIN_URL:http://127.0.0.1:15000}}") String envoyAdminUrl,
            @Value("${istio.sidecar.status-port:15021}") int sidecarStatusPort) {
        this.k8sClientService = k8sClientService;
        this.maxConfigDumpSize = maxConfigDumpSize;
        this.envoyAdminUrl = envoyAdminUrl;
        // Sidecar-Check auf demselben Host wie die Admin-API, damit beide denselben Proxy ansprechen
        this.sidecarStatusHost = URI.create(envoyAdminUrl).getHost();
        this.sidecarStatusPort = sidecarStatusPort;
        // Read-Timeout greift pro Socket-Read, also auch bei hängendem Body
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ENVOY_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(ENVOY_READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(this.envoyAdminUrl)
                .requestFactory(requestFactory)
                .build();
        logger.info("Istio Diagnostic Service initialisiert (K8s API verfügbar: {}).", k8sClientService.isInitialized());
    }

    public K8sStatus getK8sStatus() {
        return new K8sStatus(k8sClientService.isInitialized(), SUPPORTED_ISTIO_TYPES, ISTIO_VERSIONS,
                k8sClientService.getInitError());
    }

    public K8sContext getContext() {
        boolean istioPresent = checkIstioSidecar();
        return new K8sContext(
                System.getenv().getOrDefault("HOSTNAME", "unknown"),
                k8sClientService.getCurrentNamespace(),
                istioPresent,
                istioPresent ? getEnvoyDetails() : null);
    }

    public SidecarReport getFullSidecarDetails() {
        if (!checkIstioSidecar()) {
            return SidecarReport.error("Istio Sidecar Proxy ist nicht aktiv (" + sidecarStatusHost + ":" + sidecarStatusPort
                    + " nicht erreichbar).");
        }

        Reachability reachability = null;
        try {
            // Sektion A: Erreichbarkeit
            Object envoyConfig = fetchConfigDump();
            String clusters = restClient.get().uri("/clusters").retrieve().body(String.class);
            reachability = new Reachability(envoyConfig, clusters, summarizeClusters(clusters));

            // Sektion B: Gesundheit & Fehler
            String rawStats = restClient.get().uri("/stats").retrieve().body(String.class);
            Map<String, String> activeErrors = parseStats(rawStats, true);
            // xds-grpc ist interne Istio Control-Plane – kein App-Traffic, rausfiltern
            activeErrors.entrySet().removeIf(e -> e.getKey().startsWith("cluster.xds-grpc"));
            HealthDiagnostics health = new HealthDiagnostics(activeErrors, activeErrors.size(), diagnoseMetrics(activeErrors));

            return new SidecarReport(reachability, health, java.time.Instant.now().toString(), null);
        } catch (Exception e) {
            logger.error("Fehler beim Abruf der Envoy-Details: {}", e.getMessage());
            return new SidecarReport(reachability, null, null, "Envoy Admin API Fehler: " + e.getMessage());
        }
    }

    /**
     * Lädt /config_dump nur bis maxConfigDumpSize. Größere Dumps werden nicht geparst,
     * weil die Map ein Vielfaches des JSON-Texts im Heap belegt.
     */
    private Object fetchConfigDump() {
        int maxBytes = Math.toIntExact(maxConfigDumpSize.toBytes());
        return restClient.get().uri("/config_dump").exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
                throw new IllegalStateException("config_dump lieferte HTTP " + response.getStatusCode().value());
            }
            byte[] bytes;
            try (InputStream in = response.getBody()) {
                bytes = in.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                logger.warn("Envoy config_dump größer als {}, wird nicht geparst", maxConfigDumpSize);
                return new ConfigDumpTruncated(true, maxBytes, "config_dump ist größer als " + maxConfigDumpSize
                        + " und wurde nicht geladen. Limit über istio.envoy.config-dump.max-size anpassen"
                        + " oder gefiltert abrufen (z. B. /config_dump?resource=dynamic_active_clusters).");
            }
            return JsonParserFactory.getJsonParser().parseMap(new String(bytes, StandardCharsets.UTF_8));
        });
    }

    private static final Pattern OUTBOUND_CLUSTER_PATTERN = Pattern.compile("^outbound\\|(\\d+)\\|([^|]*)\\|(.+)$");

    /**
     * Parst die Envoy Admin API (/clusters) und liefert alle über den Sidecar
     * erreichbaren Outbound-Services (Host + Port), für das Service-Dropdown im UI.
     */
    public List<ReachableService> getReachableServices() {
        Map<String, ReachableService> byKey = new LinkedHashMap<>();
        try {
            String clusters = restClient.get().uri("/clusters").retrieve().body(String.class);
            if (clusters == null) return List.of();

            for (String line : clusters.split("\n")) {
                int idx = line.indexOf("::");
                if (idx <= 0) continue;
                String clusterName = line.substring(0, idx);

                Matcher m = OUTBOUND_CLUSTER_PATTERN.matcher(clusterName);
                if (!m.matches()) continue;

                String port = m.group(1);
                String subset = m.group(2);
                String host = m.group(3);
                String key = host + ":" + port + ":" + subset;
                if (byKey.containsKey(key)) continue;

                byKey.put(key, new ReachableService(host, Integer.parseInt(port), host.split("\\.")[0],
                        subset.isBlank() ? null : subset));
            }
        } catch (Exception e) {
            logger.warn("Konnte Envoy Cluster-Liste für Service-Dropdown nicht abrufen: {}", e.getMessage());
        }

        List<ReachableService> services = new ArrayList<>(byKey.values());
        services.sort(Comparator.comparing(ReachableService::host));
        return services;
    }

    private EnvoyDetails getEnvoyDetails() {
        try {
            Map<String, Object> info = restClient.get().uri("/server_info").retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            String clusters = restClient.get().uri("/clusters").retrieve().body(String.class);

            String stats = restClient.get()
                    .uri("/stats?filter=cluster.*.upstream_rq_(5xx|timeout|retry)")
                    .retrieve()
                    .body(String.class);

            return new EnvoyDetails(info, summarizeClusters(clusters), parseStats(stats, false), null);
        } catch (Exception e) {
            return EnvoyDetails.error("Envoy Admin API nicht erreichbar: " + e.getMessage());
        }
    }

    private static final String CLUSTER_DOMAIN_SUFFIX = ".svc.cluster.local";

    private static final Pattern K8S_FQDN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9-]+\\.([a-zA-Z0-9-]+)\\.svc\\.cluster\\.local$");

    /**
     * Leitet den Ziel-Namespace aus einem "service.namespace.svc.cluster.local"-Host ab.
     * Fällt auf sourceNamespace zurück, wenn der Host kein solches FQDN ist (z. B. externer Host).
     */
    private String resolveTargetNamespace(String host, String sourceNamespace) {
        if (host == null) return sourceNamespace;
        Matcher m = K8S_FQDN_PATTERN.matcher(host);
        return m.matches() ? m.group(1) : sourceNamespace;
    }

    public UrlCorrelation correlateUrl(String url, String sourceNamespace) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = (uri.getPath() == null || uri.getPath().isBlank()) ? "/" : uri.getPath();
            int port = uri.getPort();
            String targetNamespace = resolveTargetNamespace(host, sourceNamespace);
            String targetFqdn = qualifyTargetHost(host, sourceNamespace);
            List<String> namespaces = targetNamespace.equals(sourceNamespace)
                    ? List.of(sourceNamespace)
                    : List.of(sourceNamespace, targetNamespace);
            List<String> warnings = new ArrayList<>();

            List<VirtualServiceMatch> matchedVs = new ArrayList<>();
            for (String ns : namespaces) {
                for (Object vsObj : readResources(ns, "virtualservices", warnings)) {
                    if (!(vsObj instanceof Map<?, ?> vs)) continue;
                    VirtualServiceMatch analysis = analyzeVirtualService(vs, ns, targetFqdn, path, port);
                    if (analysis.hostMatch()) matchedVs.add(analysis);
                }
            }

            List<DestinationRuleMatch> matchedDrs = new ArrayList<>();
            for (String ns : namespaces) {
                for (Object drObj : readResources(ns, "destinationrules", warnings)) {
                    if (!(drObj instanceof Map<?, ?> dr)) continue;
                    DestinationRuleMatch analysis = analyzeDestinationRule(dr, ns, targetFqdn);
                    if (analysis.hostMatch()) matchedDrs.add(analysis);
                }
            }

            List<ServiceEntryMatch> matchedSes = new ArrayList<>();
            for (String ns : namespaces) {
                for (Object seObj : readResources(ns, "serviceentries", warnings)) {
                    if (!(seObj instanceof Map<?, ?> se)) continue;
                    ServiceEntryMatch analysis = analyzeServiceEntry(se, ns, targetFqdn, port);
                    if (analysis.hostMatch()) matchedSes.add(analysis);
                }
            }

            String targetShortName = host != null ? host.split("\\.")[0] : null;
            return new UrlCorrelation(
                    host,
                    path,
                    sourceNamespace,
                    targetNamespace,
                    namespaces,
                    matchedVs,
                    matchedDrs,
                    matchedSes,
                    analyzeAuthorizationPolicies(targetNamespace, targetShortName, sourceNamespace),
                    analyzePeerAuthentications(targetNamespace, targetShortName, warnings),
                    analyzeSidecarEgress(sourceNamespace, targetNamespace, targetFqdn, warnings),
                    warnings.isEmpty() ? null : warnings,
                    !matchedVs.isEmpty() || !matchedDrs.isEmpty() || !matchedSes.isEmpty(),
                    null);
        } catch (Exception e) {
            return UrlCorrelation.error(e.getMessage());
        }
    }

    /**
     * Wie {@link #getIstioResources}, aber ein Lesefehler (z. B. 403) wird als Warnung gesammelt,
     * damit die restliche Korrelation weiterläuft und "nicht lesbar" sichtbar bleibt.
     */
    private List<Object> readResources(String namespace, String type, List<String> warnings) {
        try {
            return getIstioResources(namespace, type);
        } catch (K8sApiException e) {
            warnings.add(e.getMessage());
            return List.of();
        }
    }

    private static String nameOf(Map<?, ?> resource) {
        if (!(resource.get("metadata") instanceof Map<?, ?> metadata) || metadata.get("name") == null) return "?";
        return metadata.get("name").toString();
    }

    private static String stringOrNull(Object value) {
        return value != null ? value.toString() : null;
    }

    private static List<String> toStringList(List<?> values) {
        return values.stream().map(Object::toString).toList();
    }

    /**
     * Prüft AuthorizationPolicies im Ziel-Namespace gegen den Selector (App-Kurzname) und
     * ermittelt, ob der Source-Namespace laut Policy blockiert wird (DENY) oder – bei
     * vorhandenen ALLOW-Policies für den Workload – nicht in der Allow-Liste steht.
     * Auswertung wie in Istio: CUSTOM vor DENY vor ALLOW; AUDIT beeinflusst die Entscheidung nicht.
     */
    private AuthorizationAnalysis analyzeAuthorizationPolicies(String targetNamespace, String targetShortName, String sourceNamespace) {
        List<PolicyMatch> matchingPolicies = new ArrayList<>();
        boolean deniedBySpecificRule = false;
        boolean hasAllowPolicy = false;
        boolean allowedBySpecificRule = false;
        boolean customAppliesToSource = false;

        List<Object> policies;
        try {
            policies = getIstioResources(targetNamespace, "authorizationpolicies");
        } catch (K8sApiException e) {
            // Nicht lesbar heißt nicht "keine Policy": ohne Daten keine Aussage über den Zugriff
            return new AuthorizationAnalysis(matchingPolicies, "UNKNOWN – " + e.getMessage());
        }

        for (Object obj : policies) {
            if (!(obj instanceof Map<?, ?> ap)) continue;
            Map<?, ?> spec = (Map<?, ?>) ap.get("spec");
            if (spec == null || !selectorMatches(spec, targetShortName)) continue;

            String action = spec.get("action") != null ? spec.get("action").toString() : "ALLOW";
            boolean sourceMatches = ruleMatchesSourceNamespace(spec, sourceNamespace);

            matchingPolicies.add(new PolicyMatch(nameOf(ap), action, sourceMatches));

            switch (action.toUpperCase()) {
                case "DENY" -> {
                    if (sourceMatches) deniedBySpecificRule = true;
                }
                case "ALLOW" -> {
                    hasAllowPolicy = true;
                    if (sourceMatches) allowedBySpecificRule = true;
                }
                case "CUSTOM" -> {
                    if (sourceMatches) customAppliesToSource = true;
                }
                default -> {
                    // AUDIT: nur Logging, keine Allow/Deny-Entscheidung
                }
            }
        }

        String verdict;
        if (deniedBySpecificRule) {
            verdict = "BLOCKED – DENY-Policy greift für Source-Namespace '" + sourceNamespace + "'";
        } else if (hasAllowPolicy && !allowedBySpecificRule) {
            verdict = "BLOCKED – ALLOW-Policies vorhanden, aber keine erlaubt Source-Namespace '" + sourceNamespace + "' (Deny-by-default)";
        } else if (customAppliesToSource) {
            verdict = "UNKNOWN – CUSTOM-Policy greift für Source-Namespace '" + sourceNamespace + "', Entscheidung liegt beim externen Authorizer";
        } else {
            verdict = "ALLOWED – keine blockierende AuthorizationPolicy gefunden";
        }
        return new AuthorizationAnalysis(matchingPolicies, verdict);
    }

    private boolean selectorMatches(Map<?, ?> spec, String targetShortName) {
        if (!(spec.get("selector") instanceof Map<?, ?> selector)) return true;
        if (!(selector.get("matchLabels") instanceof Map<?, ?> matchLabels)) return true;
        Object appLabel = matchLabels.get("app");
        if (appLabel == null) return true;
        return targetShortName != null && targetShortName.equalsIgnoreCase(appLabel.toString());
    }

    private boolean ruleMatchesSourceNamespace(Map<?, ?> spec, String sourceNamespace) {
        List<?> rules = (List<?>) spec.get("rules");
        // Ohne Regeln matcht eine Policy nie (bei ALLOW also "allow nothing")
        if (rules == null || rules.isEmpty()) return false;
        for (Object ruleObj : rules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) continue;
            List<?> froms = (List<?>) rule.get("from");
            if (froms == null || froms.isEmpty()) return true;
            for (Object fromObj : froms) {
                if (!(fromObj instanceof Map<?, ?> from)) continue;
                if (!(from.get("source") instanceof Map<?, ?> source)) continue;
                List<?> sourceNamespaces = (List<?>) source.get("namespaces");
                if (sourceNamespaces == null) return true;
                if (sourceNamespaces.stream().map(Object::toString).anyMatch(n -> n.equalsIgnoreCase(sourceNamespace))) return true;
            }
        }
        return false;
    }

    private List<PeerAuthenticationMatch> analyzePeerAuthentications(String targetNamespace, String targetShortName,
            List<String> warnings) {
        List<PeerAuthenticationMatch> results = new ArrayList<>();
        for (Object obj : readResources(targetNamespace, "peerauthentications", warnings)) {
            if (!(obj instanceof Map<?, ?> pa)) continue;
            Map<?, ?> spec = (Map<?, ?>) pa.get("spec");
            if (spec == null || !selectorMatches(spec, targetShortName)) continue;

            Map<?, ?> mtls = (Map<?, ?>) spec.get("mtls");
            String mtlsMode = mtls != null && mtls.get("mode") != null
                    ? mtls.get("mode").toString()
                    : "UNSET (erbt Mesh/Namespace-Default)";
            results.add(new PeerAuthenticationMatch(nameOf(pa), mtlsMode));
        }
        return results;
    }

    /**
     * Prüft, ob ein Sidecar-Resource im Source-Namespace den Egress auf bestimmte Hosts
     * beschränkt (egress-Scoping). Ist der Ziel-Host nicht im Scope, hat der Sidecar
     * keine Cluster-Config dafür – der Request scheitert unabhängig von VS/DR/AuthorizationPolicy.
     * Ein Sidecar ohne egress-Abschnitt schränkt nichts ein.
     */
    private SidecarEgress analyzeSidecarEgress(String sourceNamespace, String targetNamespace, String targetHost,
            List<String> warnings) {
        List<Object> sidecars;
        try {
            sidecars = getIstioResources(sourceNamespace, "sidecars");
        } catch (K8sApiException e) {
            warnings.add(e.getMessage());
            return new SidecarEgress(false, "Sidecar-Resources nicht lesbar – Egress-Scoping unbekannt.", null, null, null);
        }
        if (sidecars.isEmpty()) {
            return new SidecarEgress(false, "Kein Sidecar-Resource im Source-Namespace – kein Egress-Scoping aktiv.",
                    null, null, null);
        }

        List<String> checkedHosts = new ArrayList<>();
        boolean egressScoped = false;
        boolean hostAllowed = false;
        for (Object obj : sidecars) {
            if (!(obj instanceof Map<?, ?> sc)) continue;
            Map<?, ?> spec = (Map<?, ?>) sc.get("spec");
            List<?> egress = spec != null ? (List<?>) spec.get("egress") : null;
            if (egress == null || egress.isEmpty()) {
                hostAllowed = true;
                continue;
            }
            egressScoped = true;
            for (Object egObj : egress) {
                if (!(egObj instanceof Map<?, ?> eg)) continue;
                List<?> hosts = (List<?>) eg.get("hosts");
                if (hosts == null) continue;
                for (Object h : hosts) {
                    String hostEntry = h.toString();
                    checkedHosts.add(hostEntry);
                    if (egressHostMatches(hostEntry, sourceNamespace, targetNamespace, targetHost)) hostAllowed = true;
                }
            }
        }
        if (!egressScoped) {
            return new SidecarEgress(false, "Sidecar-Resource ohne egress-Abschnitt – kein Egress-Scoping aktiv.",
                    null, null, null);
        }
        String warning = hostAllowed ? null
                : "Ziel-Host ist nicht im Sidecar-Egress-Scope des Source-Namespace – Envoy hat evtl. keine Cluster-Config dafür.";
        return new SidecarEgress(true, null, hostAllowed, checkedHosts, warning);
    }

    /**
     * Wertet einen Sidecar-Egress-Eintrag "namespace/dnsName" aus. Der Namespace-Teil bezieht sich
     * auf den Namespace des Ziels: "*" = beliebig, "." = Namespace des Sidecars (Source), "~" = keiner.
     */
    private static boolean egressHostMatches(String hostEntry, String sourceNamespace, String targetNamespace, String targetHost) {
        String[] parts = hostEntry.split("/", 2);
        String nsFilter = parts.length > 1 ? parts[0] : "*";
        String hostFilter = parts.length > 1 ? parts[1] : parts[0];
        boolean nsOk = switch (nsFilter) {
            case "*" -> true;
            case "~" -> false;
            case "." -> targetNamespace.equalsIgnoreCase(sourceNamespace);
            default -> nsFilter.equalsIgnoreCase(targetNamespace);
        };
        boolean hostOk = hostFilter.equals("*")
                || (targetHost != null && targetHost.equalsIgnoreCase(hostFilter))
                || (targetHost != null && hostFilter.startsWith("*.") && targetHost.endsWith(hostFilter.substring(1)));
        return nsOk && hostOk;
    }

    private VirtualServiceMatch analyzeVirtualService(Map<?, ?> vs, String namespace, String targetHost, String targetPath, int targetPort) {
        String name = nameOf(vs);
        Map<?, ?> spec = (Map<?, ?>) vs.get("spec");
        if (spec == null) return new VirtualServiceMatch(name, false, null, null, null, null, namespace);

        List<?> rawHosts = (List<?>) spec.get("hosts");
        if (rawHosts == null) return new VirtualServiceMatch(name, false, null, null, null, null, namespace);

        List<String> vsHosts = toStringList(rawHosts);
        boolean hostMatch = vsHosts.stream().anyMatch(h -> hostsMatch(h, namespace, targetHost));
        if (!hostMatch) return new VirtualServiceMatch(name, false, vsHosts, null, null, null, namespace);

        List<?> httpRules = (List<?>) spec.get("http");
        if (httpRules == null || httpRules.isEmpty()) {
            return new VirtualServiceMatch(name, true, vsHosts, true, null, null, namespace);
        }

        List<HttpRouteAnalysis> routes = new ArrayList<>();
        for (Object ruleObj : httpRules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) continue;
            routes.add(analyzeHttpRule(rule, targetPath));
        }
        boolean pathMatch = routes.stream().anyMatch(HttpRouteAnalysis::pathMatch);
        return new VirtualServiceMatch(name, true, vsHosts, null, routes, pathMatch, namespace);
    }

    private HttpRouteAnalysis analyzeHttpRule(Map<?, ?> rule, String targetPath) {
        List<?> matches = (List<?>) rule.get("match");
        boolean pathMatch;
        String matchReason;

        if (matches == null || matches.isEmpty()) {
            pathMatch = true;
            matchReason = "Kein Match-Filter – trifft alle Pfade";
        } else {
            String found = null;
            outer:
            for (Object mObj : matches) {
                if (!(mObj instanceof Map<?, ?> m)) continue;
                Map<?, ?> uriMatch = (Map<?, ?>) m.get("uri");
                if (uriMatch == null) { found = "Kein URI-Filter in Match-Regel"; break; }
                if (uriMatch.containsKey("exact")) {
                    if (targetPath.equals(uriMatch.get("exact").toString())) { found = "exact: " + uriMatch.get("exact"); break; }
                } else if (uriMatch.containsKey("prefix")) {
                    if (targetPath.startsWith(uriMatch.get("prefix").toString())) { found = "prefix: " + uriMatch.get("prefix"); break; }
                } else if (uriMatch.containsKey("regex")) {
                    try { if (targetPath.matches(uriMatch.get("regex").toString())) { found = "regex: " + uriMatch.get("regex"); break outer; } }
                    catch (Exception ignored) {}
                }
            }
            pathMatch = found != null;
            matchReason = pathMatch ? "Match: " + found : "Kein URI-Pattern trifft auf '" + targetPath + "'";
        }

        List<RouteDestination> dests = null;
        List<?> routeDests = (List<?>) rule.get("route");
        if (routeDests != null) {
            dests = new ArrayList<>();
            for (Object destEntry : routeDests) {
                if (!(destEntry instanceof Map<?, ?> de)) continue;
                Map<?, ?> dest = (Map<?, ?>) de.get("destination");
                if (dest == null) continue;
                Integer weight = de.get("weight") instanceof Number w ? w.intValue() : null;
                dests.add(new RouteDestination(stringOrNull(dest.get("host")), stringOrNull(dest.get("subset")),
                        dest.get("port"), weight));
            }
        }
        return new HttpRouteAnalysis(stringOrNull(rule.get("name")), pathMatch, matchReason, dests,
                stringOrNull(rule.get("timeout")), rule.get("retries"), rule.get("fault"));
    }

    private DestinationRuleMatch analyzeDestinationRule(Map<?, ?> dr, String namespace, String targetHost) {
        String name = nameOf(dr);
        Map<?, ?> spec = (Map<?, ?>) dr.get("spec");
        if (spec == null) return new DestinationRuleMatch(name, false, null, null, null, namespace);

        String drHost = stringOrNull(spec.get("host"));
        if (drHost == null) return new DestinationRuleMatch(name, false, null, null, null, namespace);

        if (!hostsMatch(drHost, namespace, targetHost)) return new DestinationRuleMatch(name, false, drHost, null, null, namespace);

        List<Subset> subsetList = null;
        List<?> subsets = (List<?>) spec.get("subsets");
        if (subsets != null) {
            subsetList = new ArrayList<>();
            for (Object s : subsets) {
                if (!(s instanceof Map<?, ?> sub)) continue;
                subsetList.add(new Subset(stringOrNull(sub.get("name")), sub.get("labels"), sub.get("trafficPolicy")));
            }
        }
        return new DestinationRuleMatch(name, true, drHost, spec.get("trafficPolicy"), subsetList, namespace);
    }

    private ServiceEntryMatch analyzeServiceEntry(Map<?, ?> se, String namespace, String targetHost, int targetPort) {
        String name = nameOf(se);
        Map<?, ?> spec = (Map<?, ?>) se.get("spec");
        if (spec == null) return new ServiceEntryMatch(name, false, null, null, null, null, null, null, null, null, namespace);

        List<?> rawHosts = (List<?>) spec.get("hosts");
        if (rawHosts == null) return new ServiceEntryMatch(name, false, null, null, null, null, null, null, null, null, namespace);

        List<String> seHosts = toStringList(rawHosts);
        boolean hostMatch = seHosts.stream().anyMatch(h -> hostsMatch(h, namespace, targetHost));
        if (!hostMatch) return new ServiceEntryMatch(name, false, seHosts, null, null, null, null, null, null, null, namespace);

        // Ports prüfen ob der angeforderte Port abgedeckt ist
        List<?> ports = (List<?>) spec.get("ports");
        Boolean portMatch = null;
        String portMatchWarning = null;
        if (ports != null && targetPort > 0) {
            portMatch = ports.stream()
                .filter(p -> p instanceof Map<?, ?>)
                .map(p -> (Map<?, ?>) p)
                .anyMatch(p -> String.valueOf(targetPort).equals(String.valueOf(p.get("number"))));
            if (!portMatch) portMatchWarning = "Port " + targetPort + " ist nicht in den ServiceEntry-Ports definiert.";
        }

        return new ServiceEntryMatch(name, true, seHosts,
                stringOrNull(spec.get("location")), stringOrNull(spec.get("resolution")),
                ports, portMatch, portMatchWarning,
                spec.get("endpoints"), spec.get("subjectAltNames"), namespace);
    }

    /**
     * Löst den Ziel-Host so auf, wie der Client ihn über den DNS-Suchpfad erreicht: Ein Kurzname
     * ("api") liegt im Source-Namespace, "svc.ns.svc" wird um die Cluster-Domain ergänzt.
     * Andere Hosts mit Punkt (FQDNs, externe Hosts) bleiben unverändert.
     */
    private static String qualifyTargetHost(String host, String sourceNamespace) {
        if (host == null) return null;
        String h = host.toLowerCase().trim();
        if (!h.contains(".")) return h + "." + sourceNamespace.toLowerCase() + CLUSTER_DOMAIN_SUFFIX;
        if (h.endsWith(".svc")) return h + ".cluster.local";
        return h;
    }

    /**
     * Istio-Semantik: Ein Host ohne Punkt in VS/DR/SE gilt relativ zum Namespace der Resource,
     * nicht zu dem des Ziels. Hosts mit Punkt werden von Istio nicht erweitert.
     */
    private static String qualifyResourceHost(String host, String resourceNamespace) {
        String h = host.toLowerCase().trim();
        if (h.equals("*") || h.contains(".")) return h;
        return h + "." + resourceNamespace.toLowerCase() + CLUSTER_DOMAIN_SUFFIX;
    }

    /** {@code targetFqdn} muss bereits über {@link #qualifyTargetHost} aufgelöst sein. */
    private boolean hostsMatch(String resourceHost, String resourceNamespace, String targetFqdn) {
        if (targetFqdn == null) return false;
        String h = qualifyResourceHost(resourceHost, resourceNamespace);
        if (h.equals("*") || h.equals(targetFqdn)) return true;
        return h.startsWith("*.") && targetFqdn.endsWith(h.substring(1));
    }

    public List<Object> getIstioResources(String namespace, String type) {
        String plural = type.toLowerCase().endsWith("s") ? type.toLowerCase() : type.toLowerCase() + "s";

        if (!SUPPORTED_ISTIO_TYPES.contains(plural)) {
            throw new IllegalArgumentException(
                    "Ungültiger Istio-Ressourcentyp: '" + type + "'. Erlaubt: " + SUPPORTED_ISTIO_TYPES);
        }

        if (!k8sClientService.isInitialized()) {
            logger.warn("K8s API nicht verfügbar: {}", k8sClientService.getInitError());
            return Collections.emptyList();
        }

        for (String version : ISTIO_VERSIONS) {
            logger.info("Abfrage Istio API: Group={}, Version={}, Namespace={}, Plural={}",
                    ISTIO_GROUP, version, namespace, plural);

            Optional<List<Object>> items = k8sClientService.listNamespacedCustomObject(ISTIO_GROUP, version, namespace, plural);
            if (items.isPresent()) {
                logger.info("API Erfolg ({}): {} Ressourcen gefunden.", version, items.get().size());
                return items.get();
            }
        }

        logger.error("Keine Istio-Ressourcen für '{}' in allen Versionen {} gefunden.", type, ISTIO_VERSIONS);
        return Collections.emptyList();
    }

    private List<Diagnosis> diagnoseMetrics(Map<String, String> metrics) {
        record Rule(String pattern, String severity, String title, String description, String recommendation) {}

        List<Rule> rules = List.of(
            new Rule("upstream_rq_pending_overflow", "KRITISCH", "Circuit Breaker / Pool-Overflow",
                "Der Connection Pool ist voll oder ein Circuit Breaker ist offen.",
                "DestinationRule.trafficPolicy.connectionPool und outlierDetection prüfen."),
            new Rule("upstream_cx_none_healthy", "KRITISCH", "Keine gesunden Endpoints",
                "Alle Upstream-Endpoints sind nicht erreichbar.",
                "Pod-Status und Readiness-Probes prüfen. Envoy-Cluster-Health in Tab A ansehen."),
            new Rule("upstream_cx_connect_fail", "KRITISCH", "Verbindung abgelehnt (Connection refused)",
                "Envoy kann keine TCP-Verbindung zum Upstream aufbauen.",
                "Prüfen: Pod läuft? Richtiger Port? NetworkPolicy blockiert? Service-Selector korrekt?"),
            new Rule("upstream_rq_timeout", "WARNUNG", "Request Timeouts",
                "Requests zum Upstream überschreiten das Timeout.",
                "NetworkPolicy auf blockierte Ports prüfen. DestinationRule Timeout-Werte anpassen."),
            new Rule("upstream_cx_connect_timeout", "WARNUNG", "Connection Timeout",
                "Verbindungsaufbau zum Upstream schlägt fehl.",
                "Pod läuft möglicherweise nicht oder Port ist falsch. NetworkPolicy prüfen."),
            new Rule("upstream_rq_5xx", "WARNUNG", "5xx Fehler vom Upstream",
                "Der Upstream-Service gibt 5xx-Statuscodes zurück.",
                "Upstream-Logs prüfen. VirtualService-Routing und DestinationRule-Gewichtungen validieren."),
            new Rule("upstream_rq_retry_limit_exceeded", "INFO", "Retry-Limit überschritten",
                "Requests wurden mehrfach wiederholt und das Limit wurde erreicht.",
                "VirtualService retryPolicy anpassen oder Upstream-Stabilität verbessern."),
            new Rule("upstream_cx_destroy_remote_with_active_rq", "INFO", "Verbindung mit aktiven Requests abgebrochen",
                "Die Remote-Seite hat die Verbindung während aktiver Requests getrennt.",
                "Upstream Keep-Alive Konfiguration und Istio idle_timeout prüfen.")
        );

        List<Diagnosis> diagnoses = new ArrayList<>();
        for (Rule rule : rules) {
            List<String> matchingKeys = metrics.entrySet().stream()
                .filter(e -> e.getKey().contains(rule.pattern()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

            if (!matchingKeys.isEmpty()) {
                diagnoses.add(new Diagnosis(rule.severity(), rule.title(), rule.description(),
                        rule.recommendation(), matchingKeys));
            }
        }
        return diagnoses;
    }

    private String summarizeClusters(String rawClusters) {
        if (rawClusters == null || rawClusters.isBlank())
            return "Keine Upstream-Daten";
        long count = rawClusters.lines().count();
        return count + " aktive Upstream-Cluster-Einträge";
    }

    private Map<String, String> parseStats(String rawStats, boolean onlyNonZero) {
        Map<String, String> statsMap = onlyNonZero ? new TreeMap<>() : new HashMap<>();
        if (rawStats != null) {
            rawStats.lines()
                    .filter(line -> line.contains(":"))
                    .forEach(line -> {
                        int colonIndex = line.lastIndexOf(':');
                        if (colonIndex > 0 && colonIndex < line.length() - 1) {
                            String key = line.substring(0, colonIndex).trim();
                            String val = line.substring(colonIndex + 1).trim();
                            if (onlyNonZero) {
                                try {
                                    if (Long.parseLong(val) > 0) {
                                        statsMap.put(key, val);
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            } else {
                                statsMap.put(key, val);
                            }
                        }
                    });
        }
        return statsMap;
    }

    private boolean checkIstioSidecar() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(sidecarStatusHost, sidecarStatusPort), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
