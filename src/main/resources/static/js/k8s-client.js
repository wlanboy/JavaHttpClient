/**
 * k8s-client.js
 * Umfassende Diagnose für Envoy-Sidecars und Istio-Netzwerkressourcen.
 */
const K8sClient = (() => {

    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.json();
    }

    /**
     * Hilfsfunktion zum Rendern einer Ressourcen-Gruppe (VS, DR, oder GW)
     */
    function renderResourceGroup(title, items, icon, colorClass) {
        return `
            <div class="mb-3">
                <h6 class="x-small fw-bold text-uppercase text-muted border-bottom pb-1">${title}</h6>
                <div class="list-group list-group-flush shadow-sm">
                    ${items.map(item => `
                        <div class="list-group-item p-2 small border-start border-4 ${colorClass} mb-1 bg-light d-flex justify-content-between">
                            <span><i class="bi ${icon} me-2"></i>${item.metadata.name}</span>
                            <span class="badge bg-white text-dark border x-small">${item.metadata.namespace}</span>
                        </div>
                    `).join('') || `<div class="text-muted x-small p-2 italic">Keine ${title} gefunden.</div>`}
                </div>
            </div>`;
    }

    return {
        loadContext: async () => {
            try {
                return await apiFetch('/api/k8s/context');
            } catch (err) {
                return { error: err.message };
            }
        },

        runFullDiagnostics: async (targetUrl) => {
            const configDiv = document.getElementById('configDisplay');
            const errorDiv = document.getElementById('errorDisplay');
            const resourceDiv = document.getElementById('resourceDisplay');

            const spinner = '<div class="text-center p-4"><div class="spinner-border text-info"></div></div>';
            [configDiv, errorDiv, resourceDiv].forEach(el => { if(el) el.innerHTML = spinner; });

            try {
                const urlObj = new URL(targetUrl);
                const hostParts = urlObj.hostname.split('.');
                const targetNamespace = (hostParts.length > 1 && hostParts[1] !== 'svc') ? hostParts[1] : 'default';

                // 1. Alle Daten parallel abrufen
                const [report, vs, dr, gw] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    apiFetch(`/api/k8s/istio/virtualservice?namespace=${targetNamespace}`),
                    apiFetch(`/api/k8s/istio/destinationrule?namespace=${targetNamespace}`),
                    apiFetch(`/api/k8s/istio/gateway?namespace=${targetNamespace}`)
                ]);

                if (report.error) throw new Error(report.error);

                // --- TAB A: ENVOY CONFIG ---
                configDiv.innerHTML = `
                    <div class="mb-3">
                        <label class="fw-bold small text-muted">AKTIVE CLUSTER (UPSTREAM):</label>
                        <pre class="console x-small" style="max-height: 250px; overflow:auto; background: #1a1a1a; color: #00ff41; padding: 12px; border: 1px solid #333;">${report.reachability.activeEndpoints}</pre>
                        <div class="badge bg-primary mt-1">${report.reachability.summary}</div>
                    </div>
                    <button class="btn btn-xs btn-outline-secondary" onclick="this.nextElementSibling.classList.toggle('d-none')">Raw JSON Config</button>
                    <pre class="console x-small d-none mt-2" style="max-height:300px; overflow:auto;">${JSON.stringify(report.reachability.envoyConfig, null, 2)}</pre>`;

                // --- TAB B: FEHLER ---
                const errorEntries = Object.entries(report.healthDiagnostics.activeErrorMetrics);
                if (errorEntries.length === 0) {
                    errorDiv.innerHTML = `<div class="alert alert-success mt-2 small"><i class="bi bi-check-circle me-2"></i>Keine aktiven Fehlermetriken im Sidecar.</div>`;
                } else {
                    errorDiv.innerHTML = `
                        <table class="table table-sm table-hover border small mt-2">
                            <thead class="table-dark"><tr><th>Envoy Metrik</th><th class="text-end">Wert</th></tr></thead>
                            <tbody>
                                ${errorEntries.map(([k, v]) => `<tr><td class="x-small font-monospace">${k}</td><td class="text-end text-danger fw-bold">${v}</td></tr>`).join('')}
                            </tbody>
                        </table>`;
                }

                // --- TAB C: ALLE K8S/ISTIO RESSOURCEN ---
                // Filtern auf Relevanz zum Hostname
                const filterHost = (list) => list.filter(item => JSON.stringify(item).toLowerCase().includes(hostParts[0].toLowerCase()));
                
                resourceDiv.innerHTML = `
                    <div class="p-1">
                        ${renderResourceGroup("Virtual Services", filterHost(vs), "bi-shuffle", "border-primary")}
                        ${renderResourceGroup("Destination Rules", filterHost(dr), "bi-shield-shaded", "border-success")}
                        ${renderResourceGroup("Gateways", gw, "bi-door-open", "border-warning")}
                    </div>`;

            } catch (err) {
                const msg = `<div class="alert alert-danger m-2 small">Diagnose fehlgeschlagen: ${err.message}</div>`;
                [configDiv, errorDiv, resourceDiv].forEach(el => { if(el) el.innerHTML = msg; });
            }
        }
    };
})();

/**
 * Event Listener
 */
document.addEventListener('DOMContentLoaded', async () => {
    const contextEl = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');

    const ctx = await K8sClient.loadContext();
    if (contextEl && !ctx.error) {
        contextEl.innerHTML = `
            <span class="badge bg-dark border me-2"><i class="bi bi-tags me-1"></i>${ctx.namespace}</span>
            <span class="badge ${ctx.istioSidecar ? 'bg-success' : 'bg-warning text-dark'}">Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}</span>`;
    }

    if (diagnoseBtn) {
        diagnoseBtn.addEventListener('click', () => {
            const urlVal = document.getElementById('url').value;
            if (!urlVal) return alert("Bitte URL eingeben");

            document.getElementById('resultArea').style.display = 'block';
            document.getElementById('istioPanel').style.display = 'block';

            // Sicherer Tab-Wechsel ohne 'bootstrap is not defined' Risiko
            const firstTab = document.querySelector('#config-tab');
            if (firstTab) firstTab.click(); 

            K8sClient.runFullDiagnostics(urlVal);
        });
    }
});