/**
 * k8s-client.js
 * Fokus auf Envoy-Status und Pod-Kontext.
 */
const K8sClient = (() => {

    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.json();
    }

    return {
        loadContext: async () => {
            try { return await apiFetch('/api/k8s/context'); }
            catch (err) { return { error: err.message }; }
        },

        runFullDiagnostics: async (targetUrl) => {
            const configDiv = document.getElementById('configDisplay');
            const errorDiv = document.getElementById('errorDisplay');
            const resourceDiv = document.getElementById('resourceDisplay'); // Jetzt Context-Display

            const spinner = '<div class="text-center p-4"><div class="spinner-border text-info"></div></div>';
            [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = spinner; });

            try {
                // 1. Daten parallel abrufen
                const [report, context] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    K8sClient.loadContext()
                ]);

                if (report.error) throw new Error(report.error);

                // --- TAB A: ENVOY CONFIG ---
                configDiv.innerHTML = `
                    <div class="mb-3">
                        <label class="fw-bold small text-muted">ENVOY CLUSTERS:</label>
                        <pre class="console x-small p-2" style="max-height: 250px; overflow:auto; background: #1a1a1a; color: #00ff41;">${report.reachability.activeEndpoints}</pre>
                        <div class="badge bg-primary mt-1">${report.reachability.summary}</div>
                    </div>
                    <button class="btn btn-xs btn-outline-secondary" onclick="this.nextElementSibling.classList.toggle('d-none')">Raw JSON anzeigen</button>
                    <pre class="console x-small d-none mt-2 bg-light p-2 border">${JSON.stringify(report.reachability.envoyConfig, null, 2)}</pre>`;

                // --- TAB B: ACTIVE ERRORS ---
                const errorEntries = Object.entries(report.healthDiagnostics.activeErrorMetrics);
                errorDiv.innerHTML = errorEntries.length === 0 ?
                    `<div class="alert alert-success mt-2 small">Keine aktiven Netzwerkfehler im Proxy.</div>` :
                    `<table class="table table-sm table-hover small mt-2">
                        <thead class="table-dark"><tr><th>Metrik</th><th class="text-end">Wert</th></tr></thead>
                        <tbody>${errorEntries.map(([k, v]) => `<tr><td class="x-small font-monospace">${k}</td><td class="text-end text-danger fw-bold">${v}</td></tr>`).join('')}</tbody>
                    </table>`;

                // --- TAB C: K8S CONTEXT (ERSETZT RESSOURCEN) ---
                resourceDiv.innerHTML = `
                    <div class="p-2">
                        <h6 class="x-small fw-bold text-uppercase text-muted border-bottom pb-2 mb-3">Pod Identität & Umgebung</h6>
                        <table class="table table-sm border shadow-sm">
                            <tbody>
                                <tr><td class="bg-light fw-bold small" style="width: 30%;">Pod Name</td><td class="font-monospace small">${context.podName || 'unbekannt'}</td></tr>
                                <tr><td class="bg-light fw-bold small">Namespace</td><td><span class="badge bg-dark">${context.namespace}</span></td></tr>
                                <tr><td class="bg-light fw-bold small">Istio Sidecar</td><td>
                                    <span class="badge ${context.istioSidecar ? 'bg-success' : 'bg-danger'}">
                                        ${context.istioSidecar ? 'ACTIVE' : 'INACTIVE'}
                                    </span>
                                </td></tr>
                                <tr><td class="bg-light fw-bold small">Status Zeit</td><td class="text-muted small">${new Date().toLocaleString()}</td></tr>
                            </tbody>
                        </table>
                        <div class="alert alert-secondary x-small mt-3">
                            <i class="bi bi-info-circle me-1"></i> Dieser Kontext wird direkt aus dem Service-Account Token und Umgebungsvariablen des laufenden Containers gelesen.
                        </div>
                    </div>`;

            } catch (err) {
                const msg = `<div class="alert alert-danger m-2 small">Fehler: ${err.message}</div>`;
                [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = msg; });
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

    // Navbar initial füllen
    const ctx = await K8sClient.loadContext();
    if (contextEl && !ctx.error) {
        contextEl.innerHTML = `
            <span class="badge bg-dark border me-2"><i class="bi bi-tags me-1"></i>${ctx.namespace}</span>
            <span class="badge ${ctx.istioSidecar ? 'bg-success' : 'bg-warning text-dark'}">Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}</span>`;
    }

    if (diagnoseBtn) {
        diagnoseBtn.addEventListener('click', () => {
            const currentUrl = document.getElementById('url').value;
            if (!currentUrl) {
                alert("Bitte eine URL eingeben.");
                return;
            }

            document.getElementById('resultArea').style.display = 'block';
            document.getElementById('istioPanel').style.display = 'block';

            // Immer zum ersten Tab (Config) springen beim Klick
            const firstTab = document.querySelector('#config-tab');
            if (firstTab) firstTab.click();

            K8sClient.runFullDiagnostics(currentUrl);
        });
    }
});