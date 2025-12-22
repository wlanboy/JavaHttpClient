/**
 * k8s-client.js
 * Zuständig für Kubernetes Kontext-Informationen und Istio-Netzwerkdiagnose.
 */
const K8sClient = (() => {
    
    // Private Hilfsfunktion für API-Anfragen an den DiagnosticController
    async function apiFetch(endpoint) {
        const response = await fetch(endpoint);
        if (!response.ok) {
            throw new Error(`Fehler beim Abrufen der K8s-Daten (Status: ${response.status})`);
        }
        return await response.json();
    }

    /**
     * Erstellt das HTML für die Anzeige der Istio-Ressourcen
     */
    function renderDiagnostics(container, data) {
        let html = `
            <div class="row g-3">
                <div class="col-md-6">
                    <div class="p-2 border rounded bg-light h-100">
                        <h6 class="fw-bold border-bottom pb-2">
                            <i class="bi bi-shuffle me-2"></i>Virtual Services 
                            <span class="badge bg-secondary float-end">${data.virtualServices.length}</span>
                        </h6>
                        <div class="list-group list-group-flush shadow-sm mt-2">
                            ${data.virtualServices.map(v => `
                                <div class="list-group-item p-2 small">
                                    <span class="text-primary fw-bold">${v.metadata.name}</span>
                                </div>
                            `).join('') || '<div class="text-muted small p-2 italic">Keine VirtualServices gefunden</div>'}
                        </div>
                    </div>
                </div>
                <div class="col-md-6">
                    <div class="p-2 border rounded bg-light h-100">
                        <h6 class="fw-bold border-bottom pb-2">
                            <i class="bi bi-shield-check me-2"></i>Destination Rules 
                            <span class="badge bg-secondary float-end">${data.destinationRules.length}</span>
                        </h6>
                        <div class="list-group list-group-flush shadow-sm mt-2">
                            ${data.destinationRules.map(d => `
                                <div class="list-group-item p-2 small">
                                    <span class="text-success fw-bold">${d.metadata.name}</span>
                                </div>
                            `).join('') || '<div class="text-muted small p-2 italic">Keine DestinationRules gefunden</div>'}
                        </div>
                    </div>
                </div>
            </div>
            <div class="mt-3 p-2 bg-dark text-white rounded x-small">
                <i class="bi bi-info-circle me-2"></i>Gefiltert nach Host: <strong>${data.host}</strong> im Namespace: <strong>${data.namespace}</strong>
            </div>
        `;
        container.innerHTML = html;
    }

    return {
        // Lädt die Pod- und Namespace-Informationen für die Navbar
        loadContext: async () => {
            try {
                return await apiFetch('/api/k8s/context');
            } catch (err) {
                console.error("K8s Context Error:", err);
                return { error: err.message };
            }
        },

        // Führt eine Tiefen-Diagnose für eine Ziel-URL durch
        runFullDiagnostics: async (targetUrl) => {
            const contentDiv = document.getElementById('istioContent');
            if (!contentDiv) return;

            contentDiv.innerHTML = `
                <div class="text-center p-4">
                    <div class="spinner-border text-info" role="status"></div>
                    <div class="mt-2">Analysiere Routing-Regeln im Cluster...</div>
                </div>`;
            
            try {
                // Namespace aus URL extrahieren (Erwartet Format: http://service.namespace.svc...)
                const urlObj = new URL(targetUrl);
                const host = urlObj.hostname;
                const parts = host.split('.');
                
                // Logik: service.namespace.svc -> namespace ist an Index 1
                // Bei "localhost" oder einfachen Namen nehmen wir "default"
                const namespace = (parts.length > 1 && parts[1] !== 'svc') ? parts[1] : 'default';

                const [vs, dr] = await Promise.all([
                    apiFetch(`/api/k8s/istio/virtualservice?namespace=${namespace}`),
                    apiFetch(`/api/k8s/istio/destinationrule?namespace=${namespace}`)
                ]);

                // Filtern der Ressourcen, die den Host-Namen im Spec oder Metadaten enthalten
                const virtualServices = vs.filter(item => JSON.stringify(item).includes(parts[0]));
                const destinationRules = dr.filter(item => JSON.stringify(item).includes(parts[0]));

                renderDiagnostics(contentDiv, { host, namespace, virtualServices, destinationRules });
            } catch (err) {
                contentDiv.innerHTML = `
                    <div class="alert alert-warning">
                        <i class="bi bi-exclamation-triangle me-2"></i>
                        <strong>Diagnose eingeschränkt:</strong><br>
                        ${err.message}. Stellen Sie sicher, dass es sich um eine interne Cluster-URL handelt.
                    </div>`;
            }
        }
    };
})();

// Initialisierung bei DOM-Ready
document.addEventListener('DOMContentLoaded', async () => {
    const contextEl = document.getElementById('k8sContext');
    const diagnoseBtn = document.getElementById('k8sDiagnoseBtn');
    const istioPanel = document.getElementById('istioPanel');

    // 1. Kontext laden (Navbar)
    const ctx = await K8sClient.loadContext();
    if (contextEl) {
        if (ctx.error) {
            contextEl.innerHTML = `<span class="badge bg-danger">K8s Offline</span>`;
        } else {
            contextEl.innerHTML = `
                <span class="badge bg-opacity-25 bg-light border me-2" title="Aktueller Namespace">
                    <i class="bi bi-tags me-1"></i>${ctx.namespace}
                </span>
                <span class="badge ${ctx.istioSidecar ? 'bg-success' : 'bg-warning text-dark'}" title="Istio Sidecar Status">
                    <i class="bi bi-shield-check me-1"></i>Istio: ${ctx.istioSidecar ? 'ON' : 'OFF'}
                </span>
            `;
        }
    }

    // 2. Event Listener für den Diagnose-Button
    if (diagnoseBtn) {
        diagnoseBtn.addEventListener('click', () => {
            const urlInput = document.getElementById('url').value;
            if (!urlInput) {
                alert("Bitte geben Sie erst eine Ziel-URL ein.");
                return;
            }
            if (istioPanel) istioPanel.style.display = 'block';
            K8sClient.runFullDiagnostics(urlInput);
        });
    }
});