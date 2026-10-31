document.addEventListener('DOMContentLoaded', () => {
    // UI Elemente
    const form = document.getElementById('httpForm');
    const headerContainer = document.getElementById('headerContainer');
    const addHeaderBtn = document.getElementById('addHeaderBtn');
    const submitBtn = document.getElementById('submitBtn');
    const resultArea = document.getElementById('resultArea');
    const responseOutput = document.getElementById('responseOutput');
    const errorBox = document.getElementById('errorBox');
    const statusBadge = document.getElementById('statusBadge');
    const protocolBadge = document.getElementById('protocolBadge');
    const responseTimeText = document.getElementById('responseTime');
    const redirectChainDiv = document.getElementById('redirectChain');
    const tlsPanelDiv = document.getElementById('tlsPanel');
    const stacktraceArea = document.getElementById('stacktraceArea');
    const toggleStackBtn = document.getElementById('toggleStackBtn');

    // Historie Elemente
    const historyList = document.getElementById('historyList');
    const emptyHistoryMsg = document.getElementById('emptyHistoryMsg');
    const clearHistoryBtn = document.getElementById('clearHistoryBtn');
    const exportHistoryBtn = document.getElementById('exportHistoryBtn');

    let requestHistory = [];
    let tlsRequestId = 0;
    const STORAGE_KEY = 'k8s_http_client_history';

    // --- INITIALISIERUNG ---
    loadHistoryFromStorage();
    addHeaderRow('Content-Type', 'application/json');

    // --- EVENT LISTENER ---
    addHeaderBtn.addEventListener('click', () => addHeaderRow());

    // KORREKTUR: Robuster Toggle-Mechanismus
    if (toggleStackBtn) {
        toggleStackBtn.addEventListener('click', () => {
            const isHidden = stacktraceArea.style.display === 'none' || stacktraceArea.style.display === '';
            stacktraceArea.style.display = isHidden ? 'block' : 'none';
            toggleStackBtn.textContent = isHidden ? 'Stacktrace ausblenden' : 'Stacktrace anzeigen';
        });
    }

    clearHistoryBtn.addEventListener('click', () => {
        if (confirm("Möchtest du die gesamte Historie wirklich löschen?")) {
            requestHistory = [];
            saveHistoryToStorage();
            renderHistory();
        }
    });

    exportHistoryBtn.addEventListener('click', exportHistoryToJson);

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        await performRequest();
    });

    // --- CORE FUNKTIONEN ---

    async function performRequest() {
        const startTime = Date.now();
        const payload = {
            url: document.getElementById('url').value,
            method: document.getElementById('method').value,
            body: document.getElementById('body').value,
            copyHeaders: document.getElementById('copyHeaders').checked,
            customHeaders: collectHeaders()
        };

        prepareUIForRequest();

        try {
            const response = await fetch('/client', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            const duration = Date.now() - startTime;
            const data = await response.text();
            const protocol = response.headers.get('x-protocol-version') ?? '';
            const resolvedIp = response.headers.get('x-resolved-ip') ?? '';
            const redirectChainHeader = response.headers.get('x-redirect-chain');
            const truncatedAt = response.headers.get('x-body-truncated');

            updateResponseMetadata(response.status, duration, protocol, resolvedIp);
            renderRedirectChain(redirectChainHeader);
            if (payload.url.startsWith('https://')) fetchAndRenderTls(payload.url);
            addToHistory(payload, response.status, duration, data, { protocol, resolvedIp, redirectChain: redirectChainHeader });

            if (response.status === 502 && data.includes("---STACKTRACE---")) {
                handleDetailedError(data);
            } else {
                handleSuccess(data);
                if (truncatedAt) {
                    responseOutput.innerText += `\n\n[Response nach ${truncatedAt} Bytes gekürzt]`;
                }
            }
        } catch (err) {
            const errorMsg = "Netzwerkfehler zum Proxy: " + err.message;
            handleNetworkError(errorMsg);
            addToHistory(payload, 0, 0, errorMsg);
        } finally {
            submitBtn.disabled = false;
        }
    }

    // --- HILFSFUNKTIONEN ---

    function addHeaderRow(key = '', value = '') {
        const div = document.createElement('div');
        div.className = 'header-row';
        div.innerHTML = `
            <input type="text" class="input input-sm h-key" placeholder="Key" value="${escapeHtml(key)}">
            <input type="text" class="input input-sm h-val" placeholder="Value" value="${escapeHtml(value)}">
            <button type="button" class="btn btn-sm btn-outline is-danger remove-header" title="Header entfernen">✕</button>
        `;
        div.querySelector('.remove-header').onclick = () => div.remove();
        headerContainer.appendChild(div);
    }

    function collectHeaders() {
        const headers = {};
        document.querySelectorAll('.header-row').forEach(row => {
            const k = row.querySelector('.h-key').value.trim();
            const v = row.querySelector('.h-val').value.trim();
            if (k) headers[k] = v;
        });
        return headers;
    }

    function prepareUIForRequest() {
        submitBtn.disabled = true;
        resetResultUI();
        responseOutput.innerText = "Sende Request an Cluster...";
    }

    // Entfernt alle Anzeigen des vorherigen Requests (Badges, TLS, Redirects, Stacktrace)
    function resetResultUI() {
        resultArea.style.display = 'block';
        errorBox.style.display = 'none';
        responseOutput.style.display = 'block';
        stacktraceArea.style.display = 'none';
        if (toggleStackBtn) toggleStackBtn.textContent = 'Stacktrace anzeigen';
        if (protocolBadge) protocolBadge.style.display = 'none';
        const ipBadge = document.getElementById('resolvedIpBadge');
        if (ipBadge) ipBadge.style.display = 'none';
        if (redirectChainDiv) redirectChainDiv.style.display = 'none';
        if (tlsPanelDiv) {
            tlsRequestId++;
            tlsPanelDiv.style.display = 'none';
            tlsPanelDiv.innerHTML = '';
        }
    }

    function statusBadgeClass(status) {
        if (status >= 200 && status < 300) return 'badge-success';
        if (status >= 300 && status < 400) return 'badge-info';
        return 'badge-danger';
    }

    function renderRedirectChain(chainHeader) {
        if (!redirectChainDiv) return;
        if (!chainHeader) { redirectChainDiv.style.display = 'none'; return; }

        let steps;
        try { steps = JSON.parse(chainHeader); } catch (_) { return; }
        if (!steps.length) { redirectChainDiv.style.display = 'none'; return; }

        const statusColor = (s) => s < 400 ? 'badge-warning' : 'badge-danger';

        const stepsHtml = steps.map((step, i) => `
            <div class="inline mb-1">
                ${i === 0 ? `<span class="badge badge-dark mono truncate" title="${escapeHtml(step.from)}">${escapeHtml(step.from)}</span>` : ''}
                <span class="muted">→</span>
                <span class="badge ${statusColor(step.status)}">${escapeHtml(step.status)}</span>
                <span class="badge badge-secondary">${escapeHtml(step.proto)}</span>
                <span class="muted">→</span>
                <span class="badge badge-dark mono truncate" title="${escapeHtml(step.to)}">${escapeHtml(step.to)}</span>
            </div>`).join('');

        redirectChainDiv.innerHTML = `
            <div class="panel">
                <div class="section-title">Redirect-Chain (${steps.length} Hop${steps.length > 1 ? 's' : ''})</div>
                ${stepsHtml}
            </div>`;
        redirectChainDiv.style.display = 'block';
    }

    function updateResponseMetadata(status, duration, protocol = '', resolvedIp = '') {
        statusBadge.innerText = `HTTP ${status}`;
        statusBadge.className = `badge badge-lg ${statusBadgeClass(status)}`;
        responseTimeText.innerText = `Dauer: ${duration}ms`;
        if (protocol && protocolBadge) {
            protocolBadge.innerText = protocol;
            protocolBadge.className = `badge ${protocol === 'HTTP/2' ? 'badge-primary' : 'badge-secondary'}`;
            protocolBadge.style.display = '';
        } else if (protocolBadge) {
            protocolBadge.style.display = 'none';
        }
        const ipBadge = document.getElementById('resolvedIpBadge');
        if (ipBadge) {
            if (resolvedIp) {
                ipBadge.innerText = resolvedIp;
                ipBadge.style.display = '';
            } else {
                ipBadge.style.display = 'none';
            }
        }
    }

    function handleDetailedError(data) {
        const [info, stack] = data.split("---STACKTRACE---");
        const lines = info.split("\n");
        errorBox.style.display = 'block';
        responseOutput.style.display = 'none';
        document.getElementById('errorSummary').innerText = lines[0];
        document.getElementById('errorDetail').innerText = lines[1] || "";

        stacktraceArea.innerText = stack.trim();
        // Sicherstellen, dass er beim Laden des Fehlers erstmal zu ist
        stacktraceArea.style.display = 'none';
    }

    async function fetchAndRenderTls(url) {
        if (!tlsPanelDiv) return;
        const requestId = tlsRequestId;
        tlsPanelDiv.innerHTML = `<div class="panel x-small muted"><span class="spinner spinner-sm"></span> TLS wird inspiziert...</div>`;
        tlsPanelDiv.style.display = 'block';
        try {
            const res = await fetch(`/api/k8s/tls?url=${encodeURIComponent(url)}`);
            const tls = await res.json();
            // Ergebnis verwerfen, falls inzwischen ein anderer Request/Historieneintrag angezeigt wird
            if (requestId !== tlsRequestId) return;
            tlsPanelDiv.innerHTML = renderTlsPanel(tls);
        } catch (e) {
            if (requestId !== tlsRequestId) return;
            tlsPanelDiv.innerHTML = `<div class="panel x-small text-danger">TLS-Inspektion fehlgeschlagen: ${escapeHtml(e.message)}</div>`;
        }
    }

    function renderTlsPanel(tls) {
        if (tls.error) return `
            <div class="panel inline">
                <span class="section-title m-0">TLS</span>
                <span class="x-small text-danger">${escapeHtml(tls.error)}</span>
            </div>`;

        const mtlsBadge = tls.isMtls
            ? `<span class="badge badge-success">mTLS / SPIFFE</span>`
            : `<span class="badge badge-secondary">TLS</span>`;

        const spiffeRow = tls.spiffeId ? `
            <div class="mt-1 x-small">
                <span class="muted">SPIFFE-ID:</span>
                <code class="text-success">${escapeHtml(tls.spiffeId)}</code>
            </div>` : '';

        const chainHtml = (tls.chain ?? []).map((cert, i) => {
            const expiredClass = cert.expired ? 'text-danger' : cert.daysUntilExpiry < 30 ? 'text-warning' : 'text-success';
            const expiredIcon = cert.expired ? '✗' : cert.daysUntilExpiry < 30 ? '⚠' : '✓';
            const typeBadge = { leaf: 'badge-primary', intermediate: 'badge-secondary', root: 'badge-dark' }[cert.type] ?? 'badge-secondary';
            const sans = (cert.subjectAltNames ?? []).map(s => {
                const isSpiffe = s.startsWith('URI:spiffe://');
                return `<span class="badge ${isSpiffe ? 'badge-success' : 'badge-light'} mono mb-1">${escapeHtml(s)}</span>`;
            }).join(' ');
            const id = `tls-cert-${i}`;
            return `
            <div class="item">
                <button type="button" class="toggle-row" onclick="document.getElementById('${id}').toggleAttribute('hidden')">
                    <span class="inline">
                        <span class="badge ${typeBadge}">${escapeHtml(cert.type)}</span>
                        <span class="mono">${escapeHtml(cert.subject.split(',')[0])}</span>
                    </span>
                    <span class="${expiredClass}">${expiredIcon} ${cert.expired ? 'ABGELAUFEN' : escapeHtml(cert.daysUntilExpiry) + 'd'}</span>
                </button>
                <div id="${id}" class="item-body x-small" hidden>
                    <table class="table plain compact mb-1">
                        <tbody>
                            <tr><td class="muted" style="width:25%;">Subject</td><td class="mono break">${escapeHtml(cert.subject)}</td></tr>
                            <tr><td class="muted">Issuer</td><td class="mono break">${escapeHtml(cert.issuer)}</td></tr>
                            <tr><td class="muted">Serial</td><td class="mono break">${escapeHtml(cert.serial)}</td></tr>
                            <tr><td class="muted">Gültig von</td><td>${escapeHtml(cert.validFrom)}</td></tr>
                            <tr><td class="muted">Gültig bis</td><td class="${expiredClass}">${escapeHtml(cert.validTo)}</td></tr>
                        </tbody>
                    </table>
                    ${sans ? `<div class="mb-1">${sans}</div>` : ''}
                </div>
            </div>`;
        }).join('');

        return `
        <div class="panel">
            <div class="inline mb-2">
                <span class="section-title m-0">TLS</span>
                <span class="badge badge-info">${escapeHtml(tls.tlsVersion)}</span>
                <span class="badge badge-light mono">${escapeHtml(tls.cipherSuite)}</span>
                ${mtlsBadge}
            </div>
            ${spiffeRow}
            <div class="mt-2">${chainHtml}</div>
        </div>`;
    }

    function handleSuccess(data) {
        errorBox.style.display = 'none';
        responseOutput.style.display = 'block';
        try {
            const json = JSON.parse(data);
            responseOutput.innerText = JSON.stringify(json, null, 2);
        } catch {
            responseOutput.innerText = data || "Empty Response";
        }
    }

    function handleNetworkError(msg) {
        responseOutput.innerText = msg;
        statusBadge.innerText = "Error";
        statusBadge.className = "badge badge-lg badge-warning";
    }

    // --- HISTORIE & EXPORT ---

    function addToHistory(payload, status, duration, responseData, meta = {}) {
        const entry = {
            id: Date.now(),
            time: new Date().toLocaleTimeString(),
            payload, status, duration, responseData,
            protocol: meta.protocol ?? '',
            resolvedIp: meta.resolvedIp ?? '',
            redirectChain: meta.redirectChain ?? null
        };
        requestHistory.unshift(entry);
        if (requestHistory.length > 50) requestHistory.pop();

        saveHistoryToStorage();
        renderHistory();
    }

    function renderHistory() {
        emptyHistoryMsg.style.display = requestHistory.length ? 'none' : 'block';
        historyList.innerHTML = '';

        requestHistory.forEach((entry) => {
            const isErr = entry.status >= 400 || entry.status === 0;
            const item = document.createElement('div');
            item.className = `history-item ${isErr ? 'is-error' : ''}`;

            item.innerHTML = `
            <div class="history-main">
                <div class="spread">
                    <strong class="small">${escapeHtml(entry.payload.method)}</strong>
                    <small class="muted x-small">${escapeHtml(entry.time)}</small>
                </div>
                <div class="truncate x-small muted" style="max-width:100%;" title="${escapeHtml(entry.payload.url)}">${escapeHtml(entry.payload.url)}</div>
                <div class="mt-1">
                    <span class="badge ${statusBadgeClass(entry.status)}">${escapeHtml(entry.status)}</span>
                    <small class="x-small">${escapeHtml(entry.duration)}ms</small>
                </div>
            </div>
            <button type="button" class="delete-history-item" title="Löschen">✕</button>
        `;

            item.querySelector('.history-main').onclick = () => restoreEntry(entry);

            item.querySelector('.delete-history-item').onclick = (e) => {
                e.preventDefault();
                e.stopPropagation();
                deleteSingleHistoryItem(entry.id);
            };

            historyList.appendChild(item);
        });
    }

    function deleteSingleHistoryItem(entryId) {
        requestHistory = requestHistory.filter(item => item.id !== entryId);

        saveHistoryToStorage();
        renderHistory();
    }

    function restoreEntry(entry) {
        document.getElementById('url').value = entry.payload.url;
        document.getElementById('method').value = entry.payload.method;
        document.getElementById('body').value = entry.payload.body;
        document.getElementById('copyHeaders').checked = entry.payload.copyHeaders;

        headerContainer.innerHTML = '';
        Object.entries(entry.payload.customHeaders).forEach(([k, v]) => addHeaderRow(k, v));

        resetResultUI();
        updateResponseMetadata(entry.status, entry.duration, entry.protocol, entry.resolvedIp);
        renderRedirectChain(entry.redirectChain);

        const istioPanel = document.getElementById('istioPanel');
        if (istioPanel) istioPanel.style.display = 'none';

        if (entry.status === 502 && entry.responseData.includes("---STACKTRACE---")) {
            handleDetailedError(entry.responseData);
        } else {
            handleSuccess(entry.responseData);
        }
    }

    function exportHistoryToJson() {
        if (!requestHistory.length) return alert("Historie ist leer!");
        const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(requestHistory, null, 2));
        const downloadAnchor = document.createElement('a');
        downloadAnchor.setAttribute("href", dataStr);
        downloadAnchor.setAttribute("download", `k8s_http_history_${new Date().toISOString().split('T')[0]}.json`);
        document.body.appendChild(downloadAnchor);
        downloadAnchor.click();
        downloadAnchor.remove();
    }

    function saveHistoryToStorage() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(requestHistory));
        } catch (e) {
            console.error("Fehler beim Speichern im LocalStorage", e);
        }
    }

    function loadHistoryFromStorage() {
        try {
            const saved = localStorage.getItem(STORAGE_KEY);
            if (saved) {
                requestHistory = JSON.parse(saved);
                renderHistory();
            }
        } catch (e) {
            console.error("Fehler beim Laden aus LocalStorage", e);
            requestHistory = [];
        }
    }
});