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
            <div class="d-flex align-items-center justify-content-between mb-2 px-1">
                <span class="badge bg-secondary"><i class="bi bi-diagram-3 me-1"></i>${r.summary ?? ''}</span>
                <button class="btn btn-sm btn-outline-secondary x-small" onclick="this.closest('.d-flex').nextElementSibling.classList.toggle('d-none')">
                    <i class="bi bi-code-slash me-1"></i>Envoy Config (JSON)
                </button>
            </div>
            <div class="d-none mb-2">
                <pre class="x-small p-2" style="background:#1e1e1e;color:#4af626;border-radius:4px;max-height:300px;overflow:auto;">${JSON.stringify(r.envoyConfig, null, 2)}</pre>
            </div>
            <div class="input-group input-group-sm mb-2">
                <span class="input-group-text bg-dark border-secondary text-secondary"><i class="bi bi-search"></i></span>
                <input type="text" id="clusterSearch" class="form-control form-control-sm x-small bg-dark text-success border-secondary" placeholder="Cluster filtern...">
                <span id="clusterCount" class="input-group-text bg-dark border-secondary text-secondary x-small">${clusterLines.length}</span>
            </div>
            <pre id="clusterOutput" class="console x-small p-3 bg-dark text-success" style="border-radius:4px;max-height:400px;overflow:auto;">${clusterLines.join('\n')}</pre>`;

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

    function renderTabB(healthDiagnostics, errorDiv) {
        const errorEntries = Object.entries(healthDiagnostics?.activeErrorMetrics ?? {});
        const errorCount = healthDiagnostics?.errorCount ?? errorEntries.length;

        errorDiv.innerHTML = errorEntries.length === 0
            ? `<div class="alert alert-success small"><i class="bi bi-check-circle me-2"></i>Keine aktiven Fehler-Metriken.</div>`
            : `<div class="mb-2"><span class="badge bg-danger">${errorCount} aktive Fehler</span></div>
               <table class="table table-sm x-small">
                   <thead><tr><th>Metrik</th><th>Wert</th></tr></thead>
                   <tbody>${errorEntries.map(([k, v]) => `
                       <tr>
                           <td class="font-monospace text-truncate" style="max-width:300px;" title="${k}">${k}</td>
                           <td class="fw-bold text-danger">${v}</td>
                       </tr>`).join('')}
                   </tbody>
               </table>`;
    }

    // =========================================================================
    // Tab C: Pod Kontext & Istio Sidecar Details
    // =========================================================================

    function renderTabC(context, status, resourceDiv) {
        const istioDetails = context.istioDetails;

        const contextRows = context.error
            ? `<tr><td colspan="2"><div class="alert alert-warning x-small m-0">${context.error}</div></td></tr>`
            : Object.entries(context)
                .filter(([key]) => key !== 'istioDetails')
                .map(([key, val]) => {
                    let displayVal = String(val);
                    let badgeClass = "bg-light text-dark border";
                    if (typeof val === 'boolean') {
                        badgeClass = val ? "bg-success text-white" : "bg-danger text-white";
                        displayVal = val ? "JA" : "NEIN";
                    }
                    return `
                    <tr>
                        <td class="bg-light fw-bold small text-muted w-25">${key}</td>
                        <td><span class="badge ${badgeClass} font-monospace">${displayVal}</span></td>
                    </tr>`;
                }).join('');

        const istioDetailsHtml = renderIstioSidecarDetails(istioDetails);

        const statusRows = status ? Object.entries(status).map(([key, val]) => {
            const badgeClass = key === 'initialized'
                ? (val ? 'bg-success text-white' : 'bg-danger text-white')
                : 'bg-light text-dark border';
            const cellContent = Array.isArray(val)
                ? val.map(v => `<span class="badge ${badgeClass} font-monospace me-1 mb-1">${v}</span>`).join('')
                : `<span class="badge ${badgeClass} font-monospace">${String(val)}</span>`;
            return `
            <tr>
                <td class="bg-light fw-bold small text-muted w-25">${key}</td>
                <td>${cellContent}</td>
            </tr>`;
        }).join('') : '';

        resourceDiv.innerHTML = `
        <div class="p-2">
            <div class="d-flex justify-content-between align-items-center border-bottom pb-2 mb-3">
                <h6 class="x-small fw-bold text-uppercase text-muted mb-0">Identität & Kontext</h6>
                <button class="btn btn-sm btn-outline-primary x-small" onclick="this.closest('.d-flex').nextElementSibling.nextElementSibling.classList.toggle('d-none')">
                    <i class="bi bi-eye me-1"></i> Details (JSON)
                </button>
            </div>

            <table class="table table-sm border shadow-sm">
                <tbody>${contextRows}</tbody>
            </table>

            <div class="d-none mt-3">
                <label class="x-small fw-bold text-muted mb-1">KOMPLETTER KONTEXT (RAW):</label>
                <pre class="x-small p-3" style="background-color:#1e1e1e;color:#4af626;border-radius:6px;border:1px solid #333;overflow:auto;max-height:500px;font-family:'Fira Code',monospace;">${JSON.stringify(context, null, 4)}</pre>
            </div>

            ${istioDetailsHtml}

            <div id="istioResourcesSection" class="border-top pt-3 mt-3">
                <div class="text-center p-3"><div class="spinner-border spinner-border-sm text-info"></div></div>
            </div>

            ${statusRows ? `
            <div class="border-top pt-3 mt-3">
                <h6 class="x-small fw-bold text-uppercase text-muted mb-2">K8s Client Status</h6>
                <table class="table table-sm border shadow-sm">
                    <tbody>${statusRows}</tbody>
                </table>
            </div>` : ''}
        </div>`;
    }

    function renderIstioSidecarDetails(istioDetails) {
        if (!istioDetails) return '';
        if (istioDetails.error) {
            return `<div class="alert alert-warning x-small mt-3">${istioDetails.error}</div>`;
        }
        return `
        <div class="border-top pt-3 mt-3">
            <h6 class="x-small fw-bold text-uppercase text-muted mb-2">Istio Sidecar Details</h6>
            <table class="table table-sm x-small border shadow-sm">
                <tbody>
                    <tr>
                        <td class="bg-light fw-bold text-muted w-25">clusterSummary</td>
                        <td><span class="badge bg-secondary font-monospace">${istioDetails.clusterSummary ?? '-'}</span></td>
                    </tr>
                </tbody>
            </table>
            ${istioDetails.networkStats && Object.keys(istioDetails.networkStats).length > 0 ? `
            <label class="x-small fw-bold text-muted mb-1">Network Stats</label>
            <table class="table table-sm x-small border">
                <thead><tr><th>Metrik</th><th>Wert</th></tr></thead>
                <tbody>${Object.entries(istioDetails.networkStats).map(([k, v]) => `
                    <tr>
                        <td class="font-monospace" title="${k}">${k}</td>
                        <td>${v}</td>
                    </tr>`).join('')}
                </tbody>
            </table>` : ''}
            ${istioDetails.info ? `
            <button class="btn btn-sm btn-outline-secondary x-small mb-2" onclick="this.nextElementSibling.classList.toggle('d-none')">
                <i class="bi bi-info-circle me-1"></i>Server Info (JSON)
            </button>
            <div class="d-none mb-2">
                <pre class="x-small p-2" style="background:#1e1e1e;color:#4af626;border-radius:4px;max-height:200px;overflow:auto;">${JSON.stringify(istioDetails.info, null, 2)}</pre>
            </div>` : ''}
        </div>`;
    }

    // =========================================================================
    // Tab C: Istio Ressourcen (nachgeladen)
    // =========================================================================

    async function loadIstioResources(namespace) {
        const section = document.getElementById('istioResourcesSection');
        if (!section) return;

        const TYPES = [
            { key: 'virtualservices',       label: 'VirtualServices',       icon: 'bi-diagram-3' },
            { key: 'destinationrules',      label: 'DestinationRules',      icon: 'bi-signpost-split' },
            { key: 'gateways',              label: 'Gateways',              icon: 'bi-door-open' },
            { key: 'serviceentries',        label: 'ServiceEntries',        icon: 'bi-plug' },
            { key: 'sidecars',              label: 'SidecarConfigs',        icon: 'bi-box' },
            { key: 'envoyfilters',          label: 'EnvoyFilters',          icon: 'bi-funnel' },
            { key: 'peerauthentications',   label: 'PeerAuthentications',   icon: 'bi-key' },
            { key: 'requestauthentications',label: 'RequestAuthentications',icon: 'bi-person-check' },
            { key: 'authorizationpolicies', label: 'AuthorizationPolicies', icon: 'bi-shield-lock' },
        ];

        try {
            const results = await Promise.all(
                TYPES.map(t => apiFetch(`/api/k8s/istio/${t.key}?namespace=${encodeURIComponent(namespace)}`).catch(() => []))
            );

            const cards = TYPES.map((t, i) => {
                const items = Array.isArray(results[i]) ? results[i] : [];
                const countBadge = items.length === 0
                    ? `<span class="badge bg-secondary">0</span>`
                    : `<span class="badge bg-primary">${items.length}</span>`;

                const itemCards = items.map(item => {
                    const name = item.metadata?.name ?? '?';
                    const id = `res-${t.key}-${name}`.replace(/[^a-z0-9-]/gi, '-');
                    return `
                    <div class="border rounded mb-1">
                        <button class="btn btn-sm w-100 text-start x-small d-flex justify-content-between align-items-center px-2 py-1"
                                onclick="document.getElementById('${id}').classList.toggle('d-none')">
                            <span class="font-monospace">${name}</span>
                            <i class="bi bi-chevron-down text-muted"></i>
                        </button>
                        <pre id="${id}" class="d-none x-small m-0 p-2" style="background:#1e1e1e;color:#4af626;border-radius:0 0 4px 4px;max-height:300px;overflow:auto;">${JSON.stringify(item, null, 2)}</pre>
                    </div>`;
                }).join('');

                return `
                <div class="mb-3">
                    <div class="d-flex align-items-center mb-1">
                        <i class="bi ${t.icon} me-2 text-muted"></i>
                        <span class="x-small fw-bold text-uppercase text-muted me-2">${t.label}</span>
                        ${countBadge}
                    </div>
                    ${items.length === 0
                        ? `<div class="text-muted x-small ps-1">Keine Ressourcen in Namespace <code>${namespace}</code></div>`
                        : itemCards}
                </div>`;
            }).join('');

            section.innerHTML = `
                <h6 class="x-small fw-bold text-uppercase text-muted mb-3">
                    <i class="bi bi-list-ul me-1"></i>Istio Ressourcen
                    <span class="badge bg-light text-dark border ms-1 fw-normal">${namespace}</span>
                </h6>
                ${cards}`;

        } catch (err) {
            section.innerHTML = `<div class="alert alert-danger x-small">${err.message}</div>`;
        }
    }

    // =========================================================================
    // Orchestrierung
    // =========================================================================

    return {
        loadContext: async () => {
            try { return await apiFetch('/api/k8s/context'); }
            catch (err) { return { error: err.message }; }
        },

        runFullDiagnostics: async () => {
            const configDiv = document.getElementById('configDisplay');
            const errorDiv  = document.getElementById('errorDisplay');
            const resourceDiv = document.getElementById('resourceDisplay');

            const spinner = '<div class="text-center p-4"><div class="spinner-border text-info"></div></div>';
            [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = spinner; });

            try {
                const [report, context, status] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    K8sClient.loadContext(),
                    apiFetch('/api/k8s/status').catch(() => null)
                ]);

                if (report.error) {
                    const warning = `<div class="alert alert-warning m-3 small"><i class="bi bi-exclamation-triangle me-2"></i>${report.error}</div>`;
                    configDiv.innerHTML = warning;
                    errorDiv.innerHTML  = `<div class="alert alert-warning m-3 small">Keine Daten – Sidecar nicht aktiv.</div>`;
                } else {
                    renderTabA(report.reachability, configDiv);
                    renderTabB(report.healthDiagnostics, errorDiv);
                }

                renderTabC(context, status, resourceDiv);
                loadIstioResources(context.namespace ?? 'default');

            } catch (err) {
                const msg = `<div class="alert alert-danger m-2 small">Fehler: ${err.message}</div>`;
                [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = msg; });
            }
        }
    };

})();

// =============================================================================
// Navbar & Event Listener
// =============================================================================

document.addEventListener('DOMContentLoaded', async () => {
    const contextEl  = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');

    const ctx = await K8sClient.loadContext();
    if (contextEl && !ctx.error) {
        contextEl.innerHTML = `
            <span class="badge bg-dark border me-2"><i class="bi bi-cpu me-1"></i>${ctx.podName}</span>
            <span class="badge bg-dark border me-2"><i class="bi bi-tags me-1"></i>${ctx.namespace}</span>
            <span class="badge ${ctx.istioSidecar ? 'bg-success' : 'bg-warning text-dark'}">Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}</span>`;
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
