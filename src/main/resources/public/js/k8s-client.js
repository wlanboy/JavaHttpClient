/**
 * k8s-client.js
 * Istio & Kubernetes Diagnose-Client
 */

const K8sClient = (() => {

    // =========================================================================
    // API Layer
    // =========================================================================

    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) {
            let errorMsg = `HTTP ${response.status}`;
            try {
                const body = await response.json();
                if (body.error) errorMsg = body.error;
            } catch (_) {}
            throw new Error(errorMsg);
        }
        return await response.json();
    }

    // =========================================================================
    // Tab A: Config & Erreichbarkeit
    // =========================================================================

    function renderTabA(reachability, configDiv) {
        const r = reachability ?? {};
        const clusterLines = (r.activeEndpoints ?? '').split('\n').filter(l => l.trim());

        configDiv.innerHTML = `
            <div class="spread mb-2">
                <span class="badge badge-secondary">${escapeHtml(r.summary)}</span>
                <button type="button" class="btn btn-sm btn-outline" onclick="this.parentElement.nextElementSibling.toggleAttribute('hidden')">Envoy Config (JSON)</button>
            </div>
            <pre class="code-block mb-2" hidden>${escapeHtml(JSON.stringify(r.envoyConfig, null, 2))}</pre>
            <div class="input-group is-dark x-small">
                <input type="text" id="clusterSearch" class="input input-sm" placeholder="Cluster filtern..." aria-label="Cluster filtern">
                <span id="clusterCount" class="input-addon" title="Anzahl Cluster">${clusterLines.length}</span>
            </div>
            <pre id="clusterOutput" class="code-block tall p-3">${escapeHtml(clusterLines.join('\n'))}</pre>`;

        document.getElementById('clusterSearch').addEventListener('input', function () {
            const term = this.value.toLowerCase();
            const filtered = term ? clusterLines.filter(l => l.toLowerCase().includes(term)) : clusterLines;
            document.getElementById('clusterOutput').textContent = filtered.join('\n');
            document.getElementById('clusterCount').textContent = filtered.length;
        });
    }

    // =========================================================================
    // Tab B: Fehler-Metriken
    // =========================================================================

    // Nur Metriken, deren Name auf ein tatsächliches Problem hindeutet (Timeouts, Fehlschläge,
    // 4xx/5xx, offene Circuit Breaker etc.) UND einen aktiven (nonzero) Wert haben, gelten als Fehler.
    const ERROR_METRIC_PATTERN = /fail|timeout|overflow|reject|none_healthy|destroy_remote|destroy_local|reset|abort|_5xx|_4xx|retry_limit_exceeded|circuit_breaker/i;

    function isProblemMetric(key, value) {
        const num = Number(value);
        return !Number.isNaN(num) && num > 0 && ERROR_METRIC_PATTERN.test(key);
    }

    function metricValueClass(key, value) {
        return isProblemMetric(key, value) ? 'bold text-danger' : 'muted';
    }

    function renderTabB(healthDiagnostics, errorDiv, targetUrl = '') {
        const errorEntries = Object.entries(healthDiagnostics?.activeErrorMetrics ?? {});
        const errorCount = healthDiagnostics?.errorCount ?? errorEntries.length;
        const diagnoses = healthDiagnostics?.diagnoses ?? [];

        // Hostname aus Ziel-URL extrahieren für Cluster-Korrelation
        let targetHostname = '';
        try { targetHostname = new URL(targetUrl).hostname.toLowerCase(); } catch (_) {}
        const targetMetrics = targetHostname
            ? errorEntries.filter(([k]) => k.toLowerCase().includes(targetHostname))
            : [];
        const targetHtml = targetHostname && targetMetrics.length > 0 ? `
            <div class="alert alert-danger">
                <div class="alert-title small">⌖ ZIEL-KORRELATION – ${escapeHtml(targetHostname)}</div>
                <p class="x-small">Envoy-Metriken direkt für das Ziel-Cluster gefunden:</p>
                <table class="table x-small m-0">
                    <tbody>${targetMetrics.map(([k, v]) => `
                        <tr>
                            <td class="mono break">${escapeHtml(k)}</td>
                            <td class="${metricValueClass(k, v)}">${escapeHtml(v)}</td>
                        </tr>`).join('')}
                    </tbody>
                </table>
            </div>` : '';

        const severityConfig = {
            'KRITISCH': { cls: 'danger',  icon: '✖' },
            'WARNUNG':  { cls: 'warning', icon: '⚠' },
            'INFO':     { cls: 'info',    icon: 'ℹ' },
        };

        const diagnosisHtml = diagnoses.map(d => {
            const cfg = severityConfig[d.severity] ?? { cls: 'secondary', icon: '?' };
            const metricsHtml = d.affectedMetrics.map(m =>
                `<code class="block break x-small">${escapeHtml(m)}</code>`
            ).join('');
            return `
            <div class="alert alert-${cfg.cls}">
                <div class="alert-title small"><span aria-hidden="true">${cfg.icon}</span>${escapeHtml(d.severity)} – ${escapeHtml(d.title)}</div>
                <p class="x-small">${escapeHtml(d.description)}</p>
                <p class="x-small"><strong>Empfehlung:</strong> ${escapeHtml(d.recommendation)}</p>
                <details class="x-small mt-1">
                    <summary>Betroffene Metriken (${d.affectedMetrics.length})</summary>
                    <div class="mt-1">${metricsHtml}</div>
                </details>
            </div>`;
        }).join('');

        const tableHtml = errorEntries.length === 0 ? '' : `
            <div class="section-title mt-2">Alle aktiven Fehler-Metriken</div>
            <table class="table x-small">
                <thead><tr><th>Metrik</th><th>Wert</th></tr></thead>
                <tbody>${errorEntries.map(([k, v]) => `
                    <tr>
                        <td class="mono break">${escapeHtml(k)}</td>
                        <td class="${metricValueClass(k, v)}">${escapeHtml(v)}</td>
                    </tr>`).join('')}
                </tbody>
            </table>`;

        if (errorEntries.length === 0) {
            errorDiv.innerHTML = `<div class="alert alert-success small">✓ Keine aktiven Fehler-Metriken.</div>`;
        } else {
            errorDiv.innerHTML = `
                <div class="mb-2"><span class="badge badge-secondary">${escapeHtml(errorCount)} Metriken</span></div>
                ${targetHtml}
                ${diagnosisHtml}
                ${tableHtml}`;
        }
    }

    // =========================================================================
    // Tab C: Pod Kontext & Istio Sidecar Details
    // =========================================================================

    function renderTabC(context, status, resourceDiv) {
        const istioDetails = context.istioDetails;

        const contextRows = context.error
            ? `<tr><td colspan="2"><div class="alert alert-warning x-small m-0">${escapeHtml(context.error)}</div></td></tr>`
            : Object.entries(context)
                .filter(([key]) => key !== 'istioDetails')
                .map(([key, val]) => {
                    let displayVal = String(val);
                    let badgeClass = "badge-light";
                    if (typeof val === 'boolean') {
                        badgeClass = val ? "badge-success" : "badge-danger";
                        displayVal = val ? "JA" : "NEIN";
                    }
                    return `
                    <tr>
                        <td class="key small">${escapeHtml(key)}</td>
                        <td><span class="badge ${badgeClass} mono">${escapeHtml(displayVal)}</span></td>
                    </tr>`;
                }).join('');

        const istioDetailsHtml = renderIstioSidecarDetails(istioDetails);

        const statusRows = status ? Object.entries(status).map(([key, val]) => {
            const badgeClass = key === 'initialized'
                ? (val ? 'badge-success' : 'badge-danger')
                : 'badge-light';
            const cellContent = Array.isArray(val)
                ? val.map(v => `<span class="badge ${badgeClass} mono mb-1">${escapeHtml(v)}</span>`).join(' ')
                : `<span class="badge ${badgeClass} mono">${escapeHtml(String(val))}</span>`;
            return `
            <tr>
                <td class="key small">${escapeHtml(key)}</td>
                <td>${cellContent}</td>
            </tr>`;
        }).join('') : '';

        resourceDiv.innerHTML = `
        <div class="p-2">
            <div class="spread mb-2">
                <h6 class="section-title m-0">Identität & Kontext</h6>
                <button type="button" class="btn btn-sm btn-outline is-primary" onclick="this.parentElement.nextElementSibling.nextElementSibling.toggleAttribute('hidden')">Details (JSON)</button>
            </div>

            <table class="table boxed">
                <tbody>${contextRows}</tbody>
            </table>

            <div class="mb-3" hidden>
                <div class="section-title mb-1">Kompletter Kontext (raw)</div>
                <pre class="code-block tall p-3">${escapeHtml(JSON.stringify(context, null, 4))}</pre>
            </div>

            ${istioDetailsHtml}

            <div id="istioResourcesSection" class="divider">
                <div class="loading"><span class="spinner spinner-sm"></span></div>
            </div>

            ${statusRows ? `
            <div class="divider">
                <h6 class="section-title">K8s Client Status</h6>
                <table class="table boxed">
                    <tbody>${statusRows}</tbody>
                </table>
            </div>` : ''}
        </div>`;
    }

    function renderIstioSidecarDetails(istioDetails) {
        if (!istioDetails) return '';
        if (istioDetails.error) {
            return `<div class="alert alert-warning x-small mt-2">${escapeHtml(istioDetails.error)}</div>`;
        }
        return `
        <div class="divider">
            <h6 class="section-title">Istio Sidecar Details</h6>
            <table class="table boxed x-small">
                <tbody>
                    <tr>
                        <td class="key">clusterSummary</td>
                        <td><span class="badge badge-secondary mono">${escapeHtml(istioDetails.clusterSummary ?? '-')}</span></td>
                    </tr>
                </tbody>
            </table>
            ${istioDetails.networkStats && Object.keys(istioDetails.networkStats).length > 0 ? `
            <div class="section-title mb-1">Network Stats</div>
            <table class="table boxed x-small">
                <thead><tr><th>Metrik</th><th>Wert</th></tr></thead>
                <tbody>${Object.entries(istioDetails.networkStats).map(([k, v]) => `
                    <tr>
                        <td class="mono break">${escapeHtml(k)}</td>
                        <td>${escapeHtml(v)}</td>
                    </tr>`).join('')}
                </tbody>
            </table>` : ''}
            ${istioDetails.info ? `
            <button type="button" class="btn btn-sm btn-outline mb-2" onclick="this.nextElementSibling.toggleAttribute('hidden')">Server Info (JSON)</button>
            <pre class="code-block mb-2" style="max-height:200px;" hidden>${escapeHtml(JSON.stringify(istioDetails.info, null, 2))}</pre>` : ''}
        </div>`;
    }

    // =========================================================================
    // Tab C: Istio Ressourcen (nachgeladen)
    // =========================================================================

    async function loadIstioResources(sourceNamespace, targetUrl) {
        const section = document.getElementById('istioResourcesSection');
        if (!section) return;

        const TYPES = [
            { key: 'virtualservices',       label: 'VirtualServices' },
            { key: 'destinationrules',      label: 'DestinationRules' },
            { key: 'gateways',              label: 'Gateways' },
            { key: 'serviceentries',        label: 'ServiceEntries' },
            { key: 'sidecars',              label: 'SidecarConfigs' },
            { key: 'envoyfilters',          label: 'EnvoyFilters' },
            { key: 'peerauthentications',   label: 'PeerAuthentications' },
            { key: 'requestauthentications',label: 'RequestAuthentications' },
            { key: 'authorizationpolicies', label: 'AuthorizationPolicies' },
        ];

        // Ziel-Namespace aus "service.namespace.svc.cluster.local" ableiten, sonst = Source-Namespace.
        // Istio-Config (v.a. DestinationRule/AuthorizationPolicy) liegt meist im Ziel-Namespace, nicht
        // zwingend im Namespace des Aufrufers – beide müssen abgefragt werden.
        let targetNamespace = sourceNamespace;
        if (targetUrl) {
            try {
                const host = new URL(targetUrl).hostname;
                const m = host.match(/^[a-zA-Z0-9-]+\.([a-zA-Z0-9-]+)\.svc\.cluster\.local$/);
                if (m) targetNamespace = m[1];
            } catch (_) {}
        }
        const namespaces = targetNamespace === sourceNamespace ? [sourceNamespace] : [sourceNamespace, targetNamespace];

        try {
            const [nsResults, correlation] = await Promise.all([
                Promise.all(namespaces.map(ns =>
                    Promise.all(TYPES.map(t => apiFetch(`/api/k8s/istio/${t.key}?namespace=${encodeURIComponent(ns)}`).catch(() => [])))
                )),
                targetUrl
                    ? apiFetch(`/api/k8s/correlate?url=${encodeURIComponent(targetUrl)}&namespace=${encodeURIComponent(sourceNamespace)}`).catch(() => null)
                    : Promise.resolve(null)
            ]);

            // Matched VS/DR/SE Namen (namespace-qualifiziert) für Highlighting sammeln
            const matchedVsNames = new Set((correlation?.matchedVirtualServices ?? []).map(v => `${v.namespace}/${v.name}`));
            const matchedDrNames = new Set((correlation?.matchedDestinationRules ?? []).map(d => `${d.namespace}/${d.name}`));
            const matchedSeNames = new Set((correlation?.matchedServiceEntries ?? []).map(s => `${s.namespace}/${s.name}`));

            const cards = TYPES.map((t, i) => {
                const itemsByNs = namespaces.map((ns, nsIdx) => ({
                    ns,
                    items: Array.isArray(nsResults[nsIdx][i]) ? nsResults[nsIdx][i] : []
                }));
                const totalCount = itemsByNs.reduce((sum, g) => sum + g.items.length, 0);
                const countBadge = totalCount === 0
                    ? `<span class="badge badge-secondary">0</span>`
                    : `<span class="badge badge-primary">${totalCount}</span>`;

                const isVs = t.key === 'virtualservices';
                const isDr = t.key === 'destinationrules';
                const isSe = t.key === 'serviceentries';

                const groupsHtml = itemsByNs.map(({ ns, items }) => {
                    if (items.length === 0) return '';
                    const itemCards = items.map(item => {
                        const name = item.metadata?.name ?? '?';
                        const id = `res-${t.key}-${ns}-${name}`.replace(/[^a-z0-9-]/gi, '-');
                        const key = `${ns}/${name}`;
                        const isMatched = (isVs && matchedVsNames.has(key)) || (isDr && matchedDrNames.has(key)) || (isSe && matchedSeNames.has(key));
                        const matchBadge = isMatched ? `<span class="badge badge-success ms-1">URL-Match</span>` : '';
                        return `
                        <div class="item ${isMatched ? 'is-match' : ''}">
                            <button type="button" class="toggle-row" onclick="document.getElementById('${id}').toggleAttribute('hidden')">
                                <span class="mono">${escapeHtml(name)}${matchBadge}</span>
                                <span class="muted" aria-hidden="true">▾</span>
                            </button>
                            <pre id="${id}" class="code-block" ${isMatched ? '' : 'hidden'}>${escapeHtml(JSON.stringify(item, null, 2))}</pre>
                        </div>`;
                    }).join('');
                    const nsLabel = namespaces.length > 1
                        ? `<div class="x-small muted mb-1 mt-2"><code>${escapeHtml(ns)}</code></div>` : '';
                    return nsLabel + itemCards;
                }).join('');

                return `
                <div class="mb-3">
                    <div class="section-title mb-1">${t.label} ${countBadge}</div>
                    ${totalCount === 0
                        ? `<div class="muted x-small">Keine Ressourcen in Namespace${namespaces.length > 1 ? 's' : ''} ${namespaces.map(n => `<code>${escapeHtml(n)}</code>`).join(', ')}</div>`
                        : groupsHtml}
                </div>`;
            }).join('');

            section.innerHTML = `
                ${renderCorrelation(correlation, targetUrl)}
                <h6 class="section-title mb-3 ${correlation ? 'divider' : ''}">
                    Istio Ressourcen
                    ${namespaces.map(n => `<span class="badge badge-light normal">${escapeHtml(n)}</span>`).join('')}
                </h6>
                ${cards}`;

        } catch (err) {
            section.innerHTML = `<div class="alert alert-danger x-small">${escapeHtml(err.message)}</div>`;
        }
    }

    function renderCorrelation(corr, targetUrl) {
        if (!corr || !targetUrl) return '';
        if (corr.error) return `<div class="alert alert-warning x-small">Korrelation fehlgeschlagen: ${escapeHtml(corr.error)}</div>`;

        const vsMatches = corr.matchedVirtualServices ?? [];
        const drMatches = corr.matchedDestinationRules ?? [];

        const vsHtml = vsMatches.map(vs => {
            const routes = vs.httpRoutes ?? [];
            const matchedRoutes = routes.filter(r => r.pathMatch);
            const routeRows = routes.map(r => {
                const icon = r.pathMatch ? '<span class="text-success">✓</span>' : '<span class="muted">–</span>';
                const dests = (r.destinations ?? []).map(d =>
                    `<span class="badge badge-dark mono">${escapeHtml(d.host)}${d.subset ? ':' + escapeHtml(d.subset) : ''}${d.weight != null ? ' ' + escapeHtml(d.weight) + '%' : ''}</span>`
                ).join(' ');
                const extras = [
                    r.timeout ? `<span class="badge badge-secondary">timeout: ${escapeHtml(r.timeout)}</span>` : '',
                    r.retries ? `<span class="badge badge-secondary">retries: ${escapeHtml(JSON.stringify(r.retries))}</span>` : '',
                    r.faultInjection ? `<span class="badge badge-warning">fault injection</span>` : '',
                ].join(' ');
                return `
                <tr>
                    <td>${icon}</td>
                    <td class="muted">${escapeHtml(r.name ?? '—')}</td>
                    <td>${escapeHtml(r.matchReason)}</td>
                    <td>${dests} ${extras}</td>
                </tr>`;
            }).join('');

            const pathBadge = matchedRoutes.length > 0
                ? `<span class="badge badge-success">Pfad-Match (${matchedRoutes.length})</span>`
                : vs.noHttpRoutes
                    ? `<span class="badge badge-secondary">Keine HTTP-Routen</span>`
                    : `<span class="badge badge-warning">Kein Pfad-Match</span>`;

            return `
            <div class="mb-2">
                <div class="bold x-small">${escapeHtml(vs.name)} ${pathBadge}</div>
                <div class="x-small muted mb-1">Hosts: ${escapeHtml((vs.vsHosts ?? []).join(', '))}</div>
                ${routes.length > 0 ? `
                <table class="table plain compact m-0">
                    <thead><tr><th></th><th>Route</th><th>Match</th><th>Destinations</th></tr></thead>
                    <tbody>${routeRows}</tbody>
                </table>` : ''}
            </div>`;
        }).join('');

        const drHtml = drMatches.map(dr => {
            const subsets = (dr.subsets ?? []).map(s =>
                `<span class="badge badge-dark mono">${escapeHtml(s.name)}: ${escapeHtml(JSON.stringify(s.labels ?? {}))}</span>`
            ).join(' ');
            const tp = dr.trafficPolicy;
            const tpItems = tp ? [
                tp.connectionPool ? `<span class="badge badge-secondary">connectionPool</span>` : '',
                tp.outlierDetection ? `<span class="badge badge-secondary">outlierDetection</span>` : '',
                tp.loadBalancer ? `<span class="badge badge-secondary">loadBalancer: ${escapeHtml(tp.loadBalancer.simple)}</span>` : '',
            ].join(' ').trim() : '';
            return `
            <div class="mb-2">
                <div class="bold x-small">${escapeHtml(dr.name)}</div>
                ${tpItems ? `<div class="x-small mb-1">Traffic Policy: ${tpItems}</div>` : ''}
                ${subsets ? `<div class="x-small">Subsets: ${subsets}</div>` : ''}
            </div>`;
        }).join('');

        const seMatches = corr.matchedServiceEntries ?? [];
        const seHtml = seMatches.map(se => {
            const ports = (se.ports ?? []).map(p =>
                `<span class="badge badge-dark mono">${escapeHtml(p.number)}/${escapeHtml(p.protocol ?? p.name ?? '?')}</span>`
            ).join(' ');
            const portWarning = se.portMatchWarning
                ? `<div class="x-small text-warning mt-1">⚠ ${escapeHtml(se.portMatchWarning)}</div>` : '';
            const portMatchBadge = se.portMatch === false
                ? `<span class="badge badge-warning">Port nicht definiert</span>`
                : se.portMatch === true ? `<span class="badge badge-success">Port-Match</span>` : '';
            return `
            <div class="mb-2">
                <div class="bold x-small">${escapeHtml(se.name)} ${portMatchBadge}</div>
                <div class="x-small muted">Hosts: ${escapeHtml((se.seHosts ?? []).join(', '))}</div>
                <div class="x-small">
                    ${se.location ? `<span class="badge badge-secondary">${escapeHtml(se.location)}</span>` : ''}
                    ${se.resolution ? `<span class="badge badge-secondary">resolution: ${escapeHtml(se.resolution)}</span>` : ''}
                    ${ports}
                </div>
                ${portWarning}
            </div>`;
        }).join('');

        const hasAny = vsMatches.length > 0 || drMatches.length > 0 || seMatches.length > 0;
        const summaryBadge = hasAny
            ? `<span class="badge badge-success">Match gefunden</span>`
            : `<span class="badge badge-warning">Kein Match – URL nicht durch Istio-Config abgedeckt</span>`;

        const subTitle = (text) => `<div class="section-title mb-1 divider">${text}</div>`;

        const authz = corr.authorizationPolicies;
        const authzBlocked = authz && authz.verdict && authz.verdict.startsWith('BLOCKED');
        const authzHtml = authz ? `
            ${subTitle('AuthorizationPolicies')}
            <div class="mb-2">
                <span class="badge ${authzBlocked ? 'badge-danger' : 'badge-success'}">${escapeHtml(authz.verdict)}</span>
                ${(authz.matchingPolicies ?? []).map(p => `
                    <div class="x-small mt-1">${escapeHtml(p.name)}
                        <span class="badge ${p.action === 'DENY' ? 'badge-danger' : 'badge-secondary'} ms-1">${escapeHtml(p.action)}</span>
                        ${p.appliesToSource ? `<span class="badge badge-dark ms-1">gilt für Source</span>` : ''}
                    </div>`).join('')}
            </div>` : '';

        const peerAuth = corr.peerAuthentications ?? [];
        const peerAuthHtml = peerAuth.length > 0 ? `
            ${subTitle('PeerAuthentications')}
            <div class="mb-2">${peerAuth.map(p => `
                <span class="badge badge-dark mono">${escapeHtml(p.name)}: mTLS ${escapeHtml(p.mtlsMode)}</span>`).join(' ')}
            </div>` : '';

        const sidecarEgress = corr.sidecarEgress;
        const sidecarEgressHtml = (sidecarEgress && sidecarEgress.restricted) ? `
            ${subTitle('Sidecar Egress-Scope (Source-Namespace)')}
            <div class="mb-2">
                <span class="badge ${sidecarEgress.hostAllowed ? 'badge-success' : 'badge-danger'}">${sidecarEgress.hostAllowed ? 'Host im Egress-Scope' : 'Host NICHT im Egress-Scope'}</span>
                ${sidecarEgress.warning ? `<div class="x-small text-danger mt-1">⚠ ${escapeHtml(sidecarEgress.warning)}</div>` : ''}
            </div>` : '';

        return `
        <div class="panel mb-2">
            <div class="inline mb-2">
                <span class="section-title m-0">URL-Korrelation</span>
                ${summaryBadge}
                <code class="x-small truncate" style="max-width:250px;" title="${escapeHtml(targetUrl)}">${escapeHtml(targetUrl)}</code>
            </div>
            ${corr.sourceNamespace && corr.targetNamespace ? `
            <div class="x-small muted mb-2">Quelle: <code>${escapeHtml(corr.sourceNamespace)}</code> → Ziel: <code>${escapeHtml(corr.targetNamespace)}</code>
                (abgefragte Namespaces: ${(corr.queriedNamespaces ?? []).map(n => `<code>${escapeHtml(n)}</code>`).join(', ')})</div>` : ''}
            ${vsMatches.length > 0 ? `<div class="section-title mb-1">VirtualServices</div>${vsHtml}` : ''}
            ${drMatches.length > 0 ? `${subTitle('DestinationRules')}${drHtml}` : ''}
            ${seMatches.length > 0 ? `${subTitle('ServiceEntries')}${seHtml}` : ''}
            ${authzHtml}
            ${peerAuthHtml}
            ${sidecarEgressHtml}
        </div>`;
    }

    // =========================================================================
    // Orchestrierung
    // =========================================================================

    return {
        loadContext: async () => {
            try { return await apiFetch('/api/k8s/context'); }
            catch (err) { return { error: err.message }; }
        },

        loadServices: async () => {
            try { return await apiFetch('/api/k8s/services'); }
            catch (err) { return []; }
        },

        runFullDiagnostics: async () => {
            const configDiv = document.getElementById('configDisplay');
            const errorDiv  = document.getElementById('errorDisplay');
            const resourceDiv = document.getElementById('resourceDisplay');

            const spinner = '<div class="loading"><span class="spinner"></span></div>';
            [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = spinner; });

            try {
                const targetUrl = document.getElementById('url')?.value ?? '';
                const [report, context, status] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    K8sClient.loadContext(),
                    apiFetch('/api/k8s/status').catch(() => null)
                ]);

                if (report.error) {
                    const warning = `<div class="alert alert-warning small">⚠ ${escapeHtml(report.error)}</div>`;
                    configDiv.innerHTML = warning;
                    errorDiv.innerHTML  = `<div class="alert alert-warning small">Keine Daten – Sidecar nicht aktiv.</div>`;
                } else {
                    renderTabA(report.reachability, configDiv);
                    renderTabB(report.healthDiagnostics, errorDiv, targetUrl);
                }

                renderTabC(context, status, resourceDiv);
                loadIstioResources(context.namespace ?? 'default', targetUrl);

            } catch (err) {
                const msg = `<div class="alert alert-danger small">Fehler: ${escapeHtml(err.message)}</div>`;
                [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = msg; });
            }
        }
    };

})();

// =============================================================================
// Navbar & Event Listener
// =============================================================================

// Tauscht nur Schema, Host und Port aus; Pfad, Query und Fragment der bisherigen URL bleiben erhalten
function withServiceOrigin(serviceUrl, currentUrl) {
    const current = currentUrl.trim();
    if (current.startsWith('/')) return serviceUrl + current;
    try {
        const parsed = new URL(current);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return serviceUrl;
        const rest = parsed.pathname + parsed.search + parsed.hash;
        return rest === '/' ? serviceUrl : serviceUrl + rest;
    } catch {
        return serviceUrl;
    }
}

async function refreshEnvoyServiceSelect() {
    const select = document.getElementById('envoyServiceSelect');
    if (!select) return;

    select.innerHTML = `<option value="">Lade Services...</option>`;
    const services = await K8sClient.loadServices();

    if (!services.length) {
        select.innerHTML = `<option value="">-- keine Envoy-Services gefunden --</option>`;
        return;
    }

    const options = services.map(s => {
        const url = `http://${s.host}:${s.port}`;
        const label = s.subset ? `${s.shortName} (${s.subset}) — ${url}` : `${s.shortName} — ${url}`;
        return `<option value="${escapeHtml(url)}">${escapeHtml(label)}</option>`;
    }).join('');

    select.innerHTML = `<option value="">-- über Envoy erreichbaren Service wählen --</option>${options}`;
}

document.addEventListener('DOMContentLoaded', async () => {
    const contextEl  = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');
    const serviceSelect = document.getElementById('envoyServiceSelect');
    const refreshServicesBtn = document.getElementById('refreshServicesBtn');
    const diagTabs = document.querySelectorAll('#diagTabs .tab');
    const closeIstioBtn = document.getElementById('closeIstioBtn');

    // Tab-Umschaltung im Istio-Panel
    diagTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            diagTabs.forEach(t => {
                t.classList.toggle('active', t === tab);
                t.setAttribute('aria-selected', String(t === tab));
            });
            document.querySelectorAll('#istioContent .tab-pane').forEach(pane =>
                pane.classList.toggle('active', pane.id === tab.dataset.target));
        });
    });

    if (closeIstioBtn) {
        closeIstioBtn.addEventListener('click', () => {
            document.getElementById('istioPanel').style.display = 'none';
        });
    }

    const ctx = await K8sClient.loadContext();
    if (contextEl && ctx.error) {
        contextEl.innerHTML = `
            <span class="badge badge-warning" title="${escapeHtml(ctx.error)}">Kein Cluster-Kontext</span>`;
    } else if (contextEl) {
        contextEl.innerHTML = `
            <span class="badge badge-dark outlined" title="Pod">pod: ${escapeHtml(ctx.podName)}</span>
            <span class="badge badge-dark outlined" title="Namespace">ns: ${escapeHtml(ctx.namespace)}</span>
            <span class="badge ${ctx.istioSidecar ? 'badge-success' : 'badge-warning'}">Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}</span>`;
    }

    refreshEnvoyServiceSelect();

    if (serviceSelect) {
        serviceSelect.addEventListener('change', () => {
            if (serviceSelect.value) {
                const urlInput = document.getElementById('url');
                urlInput.value = withServiceOrigin(serviceSelect.value, urlInput.value);
                urlInput.dispatchEvent(new Event('input', { bubbles: true }));
            }
        });
    }

    if (refreshServicesBtn) {
        refreshServicesBtn.addEventListener('click', refreshEnvoyServiceSelect);
    }

    if (diagnoseBtn) {
        diagnoseBtn.addEventListener('click', () => {
            const currentUrl = document.getElementById('url').value;
            if (!currentUrl) return alert("Bitte URL eingeben.");

            document.getElementById('resultArea').style.display = 'block';
            document.getElementById('istioPanel').style.display = 'block';

            const firstTab = document.querySelector('#config-tab');
            if (firstTab) firstTab.click();

            K8sClient.runFullDiagnostics();
        });
    }
});
