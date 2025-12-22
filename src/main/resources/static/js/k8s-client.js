/**
 * k8s-client.js
 * Fokus auf Tab C: Dynamische Anzeige des Pod-Kontexts
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
            const resourceDiv = document.getElementById('resourceDisplay');

            const spinner = '<div class="text-center p-4"><div class="spinner-border text-info"></div></div>';
            [configDiv, errorDiv, resourceDiv].forEach(el => { if (el) el.innerHTML = spinner; });

            try {
                // 1. Daten laden
                const [report, context] = await Promise.all([
                    apiFetch('/api/k8s/istio/full-report'),
                    K8sClient.loadContext()
                ]);

                // --- TAB A & B (Envoy Config & Errors) ---
                // Hier bleibt deine bestehende Rendering-Logik für report.reachability und report.healthDiagnostics
                configDiv.innerHTML = `<pre class="console x-small p-2 bg-dark text-success">${report.reachability.activeEndpoints}</pre>`;

                const errorEntries = Object.entries(report.healthDiagnostics.activeErrorMetrics);
                errorDiv.innerHTML = errorEntries.length === 0 ?
                    `<div class="alert alert-success small">Keine Fehler.</div>` :
                    `<table class="table table-sm small">${errorEntries.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('')}</table>`;


                // --- TAB C: POD KONTEXT (Dynamisch) ---
                // Wir iterieren über alle Keys im Context-Response
                const contextRows = Object.entries(context).map(([key, val]) => {
                    let badgeClass = "bg-light text-dark border";
                    if (val === true) badgeClass = "bg-success text-white";
                    if (val === false) badgeClass = "bg-danger text-white";

                    return `
                        <tr>
                            <td class="bg-light fw-bold small text-muted w-25">${key}</td>
                            <td><span class="badge ${badgeClass} font-monospace">${val}</span></td>
                        </tr>`;
                }).join('');

                resourceDiv.innerHTML = `
                    <div class="p-2">
                        <div class="d-flex justify-content-between align-items-center border-bottom pb-2 mb-3">
                            <h6 class="x-small fw-bold text-uppercase text-muted mb-0">Identität & Kontext</h6>
                            <button class="btn btn-xs btn-outline-primary" onclick="this.parentElement.nextElementSibling.nextElementSibling.classList.toggle('d-none')">
                                <i class="bi bi-eye me-1"></i> Details (JSON)
                            </button>
                        </div>

                        <table class="table table-sm border shadow-sm">
                            <tbody>${contextRows}</tbody>
                        </table>

                        <div class="d-none mt-3">
                            <label class="x-small fw-bold text-muted">RAW CONTEXT RESPONSE:</label>
                            <pre class="console x-small bg-light p-3 border">${JSON.stringify(context, null, 4)}</pre>
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
 * Event Listener
 */
document.addEventListener('DOMContentLoaded', async () => {
    const contextEl = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');

    // Navbar füllen
    const ctx = await K8sClient.loadContext();
    if (contextEl && !ctx.error) {
        contextEl.innerHTML = `
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

            K8sClient.runFullDiagnostics(currentUrl);
        });
    }
});