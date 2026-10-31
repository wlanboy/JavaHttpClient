package com.wlanboy.javahttpclient.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ergebnis der Korrelation einer URL mit der Istio-Konfiguration")
public record UrlCorrelation(
        String requestedHost,
        String requestedPath,
        String sourceNamespace,
        String targetNamespace,
        List<String> queriedNamespaces,
        List<VirtualServiceMatch> matchedVirtualServices,
        List<DestinationRuleMatch> matchedDestinationRules,
        List<ServiceEntryMatch> matchedServiceEntries,
        AuthorizationAnalysis authorizationPolicies,
        List<PeerAuthenticationMatch> peerAuthentications,
        SidecarEgress sidecarEgress,
        @Schema(description = "Ressourcen, die nicht gelesen werden konnten (z. B. fehlende RBAC-Rechte) – das Ergebnis ist dann unvollständig")
        List<String> warnings,
        Boolean hasMatch,
        String error) {

    public static UrlCorrelation error(String error) {
        return new UrlCorrelation(null, null, null, null, null, null, null, null, null, null, null, null, null, error);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Analyse eines VirtualService gegen Host und Pfad")
    public record VirtualServiceMatch(
            String name,
            boolean hostMatch,
            List<String> vsHosts,
            Boolean noHttpRoutes,
            List<HttpRouteAnalysis> httpRoutes,
            Boolean pathMatch,
            String namespace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Analyse einer HTTP-Route eines VirtualService")
    public record HttpRouteAnalysis(
            String name,
            boolean pathMatch,
            String matchReason,
            List<RouteDestination> destinations,
            String timeout,
            @Schema(description = "Rohdaten aus spec.http[].retries")
            Object retries,
            @Schema(description = "Rohdaten aus spec.http[].fault")
            Object faultInjection) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Routing-Ziel einer HTTP-Route")
    public record RouteDestination(
            String host,
            String subset,
            @Schema(description = "Rohdaten aus destination.port")
            Object port,
            Integer weight) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Analyse einer DestinationRule gegen den Host")
    public record DestinationRuleMatch(
            String name,
            boolean hostMatch,
            String drHost,
            @Schema(description = "Rohdaten aus spec.trafficPolicy")
            Object trafficPolicy,
            List<Subset> subsets,
            String namespace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Subset einer DestinationRule")
    public record Subset(
            String name,
            @Schema(description = "Rohdaten aus subsets[].labels")
            Object labels,
            @Schema(description = "Rohdaten aus subsets[].trafficPolicy")
            Object trafficPolicy) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Analyse eines ServiceEntry gegen Host und Port")
    public record ServiceEntryMatch(
            String name,
            boolean hostMatch,
            List<String> seHosts,
            String location,
            String resolution,
            @Schema(description = "Rohdaten aus spec.ports")
            Object ports,
            Boolean portMatch,
            String portMatchWarning,
            @Schema(description = "Rohdaten aus spec.endpoints")
            Object endpoints,
            @Schema(description = "Rohdaten aus spec.subjectAltNames")
            Object subjectAltNames,
            String namespace) {}

    @Schema(description = "Bewertung der AuthorizationPolicies im Ziel-Namespace")
    public record AuthorizationAnalysis(List<PolicyMatch> matchingPolicies, String verdict) {}

    @Schema(description = "AuthorizationPolicy, deren Selector auf den Ziel-Workload passt")
    public record PolicyMatch(String name, String action, boolean appliesToSource) {}

    @Schema(description = "PeerAuthentication, deren Selector auf den Ziel-Workload passt")
    public record PeerAuthenticationMatch(String name, String mtlsMode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Egress-Scoping durch Sidecar-Resources im Source-Namespace")
    public record SidecarEgress(
            boolean restricted,
            String info,
            Boolean hostAllowed,
            List<String> checkedEgressHosts,
            String warning) {}
}
