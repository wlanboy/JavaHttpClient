const K8sClient = (() => {

    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.json();
    }

    // Zeigt einfach alle Items an, die übergeben werden
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
            [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = spinner; });

            try {
                // Namespace-Logik: Wir nutzen primär den Namespace, in dem die App selbst läuft,
                // außer die URL ist voll qualifiziert (z.B. service.other-ns.svc).
                const urlObj = new URL(targetUrl);
                const hostParts = urlObj.hostname.split('.');

                // Wir holen uns erst den eigenen Namespace der App aus dem Context
                const context = await apiFetch('/api/k8s/context');
                const currentNs = context.namespace || 'default';

                // Wenn die URL einen Namespace enthält (part[1]), nutze diesen, sonst den aktuellen.
                const targetNamespace = (hostParts.length > 1 && hostParts[1] !== 'svc' && hostParts[1] !== 'cluster')
                    ? hostParts[1] : currentNs;

                const [report, vs, dr, gw] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    apiFetch(`/api/k8s/istio/virtualservice?namespace=${targetNamespace}`),
                    apiFetch(`/api/k8s/istio/destinationrule?namespace=${targetNamespace}`),
                    apiFetch(`/api/k8s/istio/gateway?namespace=${targetNamespace}`)
                ]);

                // Render Sektionen
                configDiv.innerHTML = `
                    <div class="mb-3">
                        <label class="fw-bold small text-muted">ENVOY CLUSTERS:</label>
                        <pre class="console x-small" style="max-height: 250px; overflow:auto; background: #1a1a1a; color: #00ff41; padding: 12px;">${report.reachability.activeEndpoints}</pre>
                    </div>
                    <button class="btn btn-xs btn-outline-secondary" onclick="this.nextElementSibling.classList.toggle('d-none')">Raw JSON</button>
                    <pre class="console x-small d-none mt-2">${JSON.stringify(report.reachability.envoyConfig, null, 2)}</pre>`;

                const errorEntries = Object.entries(report.healthDiagnostics.activeErrorMetrics);
                errorDiv.innerHTML = errorEntries.length === 0 ?
                    `<div class="alert alert-success mt-2 small">Keine Fehler-Metriken > 0.</div>` :
                    `<table class="table table-sm table-hover small mt-2">
                        <thead class="table-dark"><tr><th>Metrik</th><th class="text-end">Wert</th></tr></thead>
                        <tbody>${errorEntries.map(([k, v]) => `<tr><td class="x-small">${k}</td><td class="text-end fw-bold text-danger">${v}</td></tr>`).join('')}</tbody>
                    </table>`;

                // Ressourcen ohne Filter anzeigen
                resourceDiv.innerHTML = `
                    <div class="alert alert-info py-1 px-2 x-small mb-3">Zeige Ressourcen im Namespace: <strong>${targetNamespace}</strong></div>
                    ${renderResourceGroup("Virtual Services", vs, "bi-shuffle", "border-primary")}
                    ${renderResourceGroup("Destination Rules", dr, "bi-shield-shaded", "border-success")}
                    ${renderResourceGroup("Gateways", gw, "bi-door-open", "border-warning")}`;

            } catch (err) {
                const msg = `<div class="alert alert-danger m-2 small">Fehler: ${err.message}</div>`;
                [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = msg; });
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

            document.getElementById('resultArea').style.display = 'block';
            document.getElementById('istioPanel').style.display = 'block';

            const firstTab = document.querySelector('#config-tab');
            if (firstTab) firstTab.click();

            K8sClient.runFullDiagnostics(urlVal);
        });
    }
});