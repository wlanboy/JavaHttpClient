/**
 * k8s-client.js
 * Verwaltet Kubernetes Kontext und tiefe Istio/Envoy Diagnose-Daten.
 */
const K8sClient = (() => {

    /**
     * Private Hilfsfunktion für API-Calls
     */
    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) {
            throw new Error(`Server-Fehler: ${response.status}`);
        }
        return await response.json();
    }

    return {
        /**
         * Lädt initialen Kontext für die Navbar (Namespace & Istio Status)
         */
        loadContext: async () => {
            try {
                return await apiFetch('/api/k8s/context');
            } catch (err) {
                console.error("K8s Context Error:", err);
                return { error: err.message };
            }
        },

        /**
         * Hauptfunktion für die Deep-Dive Diagnose
         */
        runFullDiagnostics: async (targetUrl) => {
            const configDiv = document.getElementById('configDisplay');
            const errorDiv = document.getElementById('errorDisplay');
            const resourceDiv = document.getElementById('resourceDisplay');

            // 1. Loading State für alle Tabs setzen
            const spinner = '<div class="text-center p-3"><div class="spinner-border spinner-border-sm text-info"></div><div class="mt-2 x-small text-muted">Abfrage läuft...</div></div>';
            if (configDiv) configDiv.innerHTML = spinner;
            if (errorDiv) errorDiv.innerHTML = spinner;
            if (resourceDiv) resourceDiv.innerHTML = spinner;

            try {
                // 2. Daten parallel abfragen
                // A) Full Report vom Envoy Proxy (Backend getFullReport)
                // B) K8s Ressourcen (Backend getIstioResources)
                
                const urlObj = new URL(targetUrl);
                const hostParts = urlObj.hostname.split('.');
                // Extrahiere Namespace aus URL (z.B. service.namespace.svc.cluster.local)
                const targetNamespace = (hostParts.length > 1 && hostParts[1] !== 'svc') ? hostParts[1] : 'default';

                const [report, vsData] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    apiFetch(`/api/k8s/istio/virtualservice?namespace=${targetNamespace}`)
                ]);

                if (report.error) throw new Error(report.error);

                // --- RENDERING TAB A: CONFIG & REACHABILITY ---
                configDiv.innerHTML = `
                    <div class="mb-3">
                        <label class="form-label fw-bold small text-uppercase text-muted">Aktive Upstream Endpunkte (Envoy Clusters):</label>
                        <pre class="console x-small" style="max-height: 250px; overflow:auto; background: #1e1e1e; color: #4af626; padding: 12px; border-radius: 4px; border: 1px solid #333;">${report.reachability.activeEndpoints || 'Keine Daten verfügbar'}</pre>
                        <div class="badge bg-primary mt-1">${report.reachability.summary}</div>
                    </div>
                    <div class="mt-3 border-top pt-2">
                        <button class="btn btn-sm btn-outline-secondary" onclick="this.nextElementSibling.classList.toggle('d-none')">
                            <i class="bi bi-code-slash me-1"></i>Raw Envoy Config Dump (JSON)
                        </button>
                        <pre class="console x-small d-none mt-2" style="max-height: 400px; overflow:auto; background: #f8f9fa; color: #333; border: 1px solid #ddd; padding: 10px;">${JSON.stringify(report.reachability.envoyConfig, null, 2)}</pre>
                    </div>`;

                // --- RENDERING TAB B: ACTIVE ERRORS ---
                const errorEntries = Object.entries(report.healthDiagnostics.activeErrorMetrics);
                if (errorEntries.length === 0) {
                    errorDiv.innerHTML = `
                        <div class="alert alert-success border-0 shadow-sm d-flex align-items-center mt-2">
                            <i class="bi bi-check-circle-fill fs-4 me-3 text-success"></i>
                            <div><strong>Alles okay!</strong> Keine aktiven Fehlermetriken im Sidecar Proxy für diesen Pod gefunden.</div>
                        </div>`;
                } else {
                    errorDiv.innerHTML = `
                        <div class="table-responsive mt-2">
                            <table class="table table-sm table-hover border small shadow-sm">
                                <thead class="table-dark">
                                    <tr><th>Envoy Metrik Pfad</th><th class="text-end">Zählerstand</th></tr>
                                </thead>
                                <tbody>
                                    ${errorEntries.map(([k, v]) => `
                                        <tr>
                                            <td class="font-monospace x-small text-muted">${k}</td>
                                            <td class="text-end fw-bold text-danger">${v}</td>
                                        </tr>
                                    `).join('')}
                                </tbody>
                            </table>
                            <div class="p-2 x-small bg-light border rounded"><i class="bi bi-info-circle me-1"></i> Es werden nur Counter mit Werten > 0 angezeigt.</div>
                        </div>`;
                }

                // --- RENDERING TAB C: K8S RESOURCES ---
                const vsFiltered = vsData.filter(item => JSON.stringify(item).includes(hostParts[0]));
                resourceDiv.innerHTML = `
                    <div class="p-2">
                        <h6 class="small fw-bold text-uppercase text-muted mb-3 border-bottom pb-2">Gefundene VirtualServices in '${targetNamespace}'</h6>
                        <div class="list-group list-group-flush shadow-sm">
                            ${vsFiltered.map(v => `
                                <div class="list-group-item p-2 small border-start border-4 border-info mb-2 bg-light d-flex justify-content-between">
                                    <span><i class="bi bi-shuffle me-2 text-primary"></i>${v.metadata.name}</span>
                                    <span class="badge bg-white text-dark border">v1alpha3</span>
                                </div>
                            `).join('') || '<div class="alert alert-light small text-center">Keine spezifischen Istio-Regeln für diesen Host gefunden.</div>'}
                        </div>
                    </div>`;

            } catch (err) {
                console.error("Diagnostic Error:", err);
                const errorMsg = `<div class="alert alert-danger m-3 small"><i class="bi bi-exclamation-triangle-fill me-2"></i>${err.message}</div>`;
                configDiv.innerHTML = errorMsg;
                errorDiv.innerHTML = errorMsg;
                resourceDiv.innerHTML = errorMsg;
            }
        }
    };
})();

/**
 * Event Listener Initialisierung
 */
document.addEventListener('DOMContentLoaded', async () => {
    const contextEl = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');

    // 1. Initialer Context-Check für die UI
    const ctx = await K8sClient.loadContext();
    if (contextEl && !ctx.error) {
        contextEl.innerHTML = `
            <span class="badge bg-dark border me-2" title="Namespace"><i class="bi bi-tags me-1"></i>${ctx.namespace}</span>
            <span class="badge ${ctx.istioSidecar ? 'bg-success' : 'bg-warning text-dark'}">
                <i class="bi bi-shield-check me-1"></i>Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}
            </span>`;
    }

    // 2. Diagnose Button Logik
    if (diagnoseBtn) {
        diagnoseBtn.addEventListener('click', () => {
            const urlValue = document.getElementById('url').value;
            if (!urlValue) {
                alert("Bitte eine Ziel-URL eingeben!");
                return;
            }

            // UI-Bereiche einblenden
            document.getElementById('resultArea').style.display = 'block';
            document.getElementById('istioPanel').style.display = 'block';

            // Automatisch zum ersten Tab wechseln (falls man im Fehler-Tab war)
            const firstTabEl = document.querySelector('#config-tab');
            if (firstTabEl) {
                const tabTrigger = new bootstrap.Tab(firstTabEl);
                tabTrigger.show();
            }

            // Diagnose starten
            K8sClient.runFullDiagnostics(urlValue);
        });
    }
});