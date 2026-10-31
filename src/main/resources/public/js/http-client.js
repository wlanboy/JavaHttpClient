document.addEventListener('DOMContentLoaded', () => {
    // UI Elemente
    const form = document.getElementById('httpForm');
    const headerContainer = document.getElementById('headerContainer');
    const addHeaderBtn = document.getElementById('addHeaderBtn');
    const submitBtn = document.getElementById('submitBtn');
    const cancelBtn = document.getElementById('cancelBtn');
    const resultArea = document.getElementById('resultArea');
    const responseOutput = document.getElementById('responseOutput');
    const responseBodyArea = document.getElementById('responseBodyArea');
    const responseSizeText = document.getElementById('responseSize');
    const responseHeadersDiv = document.getElementById('responseHeaders');
    const wrapToggle = document.getElementById('wrapToggle');
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
    const urlHistoryList = document.getElementById('urlHistory');
    const methodSelect = document.getElementById('method');
    const bodyInput = document.getElementById('body');
    const bodyHint = document.getElementById('bodyHint');
    const headerNameSuggestions = document.getElementById('headerNameSuggestions');
    const headerValueSuggestions = document.getElementById('headerValueSuggestions');

    let requestHistory = [];
    let tlsRequestId = 0;
    let activeRequest = null;
    const submitBtnLabel = submitBtn.textContent;
    const STORAGE_KEY = 'k8s_http_client_history';
    const FORM_STORAGE_KEY = 'k8s_http_client_form';
    const WRAP_STORAGE_KEY = 'k8s_http_client_wrap';
    // Header, die der Proxy selbst setzt und die nicht vom Zielservice stammen
    const PROXY_META_HEADERS = new Set([
        'x-protocol-version', 'x-protocol-fallback', 'x-resolved-ip', 'x-body-truncated', 'x-redirect-chain'
    ]);
    // Darüber wird nur formatiert, nicht eingefärbt (tausende <span> machen die Seite träge)
    const MAX_HIGHLIGHT_LENGTH = 1_000_000;
    // Muss zu BAD_HEADERS in ClientService passen: diese Header verwirft der Proxy
    const BLOCKED_HEADERS = new Set(['host', 'content-length', 'connection', 'accept-encoding', 'upgrade']);
    const HEADER_SUGGESTIONS = [
        'Authorization', 'Content-Type', 'Accept', 'Accept-Language', 'Cookie', 'User-Agent', 'Cache-Control',
        'x-request-id', 'traceparent', 'b3', 'x-b3-traceid', 'x-b3-spanid', 'x-b3-sampled',
        'x-forwarded-for', 'x-forwarded-proto', 'x-api-key', 'x-envoy-upstream-rq-timeout-ms'
    ];
    // Werte-Vorschläge je Header-Name (klein geschrieben); Funktionen liefern frische Werte
    const HEADER_VALUE_SUGGESTIONS = {
        'authorization': ['Bearer ', 'Basic '],
        'content-type': ['application/json', 'application/x-www-form-urlencoded', 'text/plain', 'application/xml'],
        'accept': ['application/json', '*/*', 'text/html'],
        'cache-control': ['no-cache', 'no-store'],
        'x-request-id': () => [newRequestId()],
        'x-b3-sampled': ['1', '0'],
        'x-forwarded-proto': ['https', 'http']
    };
    const BODY_HINTS = {
        GET: { disabled: true, text: 'GET-Requests senden keinen Body. Der Inhalt bleibt hier stehen, wird aber nicht mitgeschickt.' },
        DELETE: { disabled: false, text: 'Ein Body bei DELETE ist unüblich und wird von vielen Servern ignoriert.' }
    };

    // --- INITIALISIERUNG ---
    loadHistoryFromStorage();
    headerNameSuggestions.innerHTML = HEADER_SUGGESTIONS.map(name => `<option value="${escapeHtml(name)}"></option>`).join('');
    if (!loadFormFromStorage()) addHeaderRow('Content-Type', 'application/json');
    updateBodyState();
    wrapToggle.checked = loadWrapSetting();
    applyWrapSetting();

    // --- EVENT LISTENER ---
    addHeaderBtn.addEventListener('click', () => addHeaderRow().querySelector('.h-key').focus());

    methodSelect.addEventListener('change', updateBodyState);

    wrapToggle.addEventListener('change', () => {
        applyWrapSetting();
        try {
            localStorage.setItem(WRAP_STORAGE_KEY, String(wrapToggle.checked));
        } catch (e) {
            console.error("Fehler beim Speichern der Umbruch-Einstellung", e);
        }
    });

    // Letzte Eingaben merken, damit sie einen Reload überstehen
    form.addEventListener('input', saveFormToStorage);
    form.addEventListener('change', saveFormToStorage);

    // Strg+Enter (bzw. Cmd+Enter) sendet aus jedem Feld, auch aus dem Body
    form.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            form.requestSubmit();
        }
    });

    cancelBtn.addEventListener('click', () => activeRequest?.abort());

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
        if (activeRequest) return;
        const startTime = Date.now();
        const headerRows = collectHeaderRows();
        const payload = {
            url: document.getElementById('url').value,
            method: methodSelect.value,
            body: bodyInput.value,
            copyHeaders: document.getElementById('copyHeaders').checked,
            customHeaders: toHeaderMap(headerRows)
        };

        activeRequest = new AbortController();
        prepareUIForRequest();

        try {
            const response = await fetch('/client', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
                signal: activeRequest.signal
            });

            const duration = Date.now() - startTime;
            const buffer = await response.arrayBuffer();
            const data = new TextDecoder().decode(buffer);
            const size = buffer.byteLength;
            const headers = [...response.headers].filter(([name]) => !PROXY_META_HEADERS.has(name));
            const protocol = response.headers.get('x-protocol-version') ?? '';
            const resolvedIp = response.headers.get('x-resolved-ip') ?? '';
            const redirectChainHeader = response.headers.get('x-redirect-chain');
            const truncatedAt = response.headers.get('x-body-truncated');

            updateResponseMetadata(response.status, duration, protocol, resolvedIp);
            renderRedirectChain(redirectChainHeader);
            if (payload.url.startsWith('https://')) fetchAndRenderTls(payload.url);
            addToHistory(payload, response.status, duration, data,
                { protocol, resolvedIp, redirectChain: redirectChainHeader, headers, size, truncatedAt, headerRows });

            if (response.status === 502 && data.includes("---STACKTRACE---")) {
                handleDetailedError(data);
            } else {
                renderResponseInfo(headers, size, truncatedAt);
                handleSuccess(data, truncatedAt);
            }
            scrollToResult();
        } catch (err) {
            if (err.name === 'AbortError') {
                handleAbort();
            } else {
                const errorMsg = "Netzwerkfehler zum Proxy: " + err.message;
                handleNetworkError(errorMsg);
                addToHistory(payload, 0, 0, errorMsg, { headerRows });
                scrollToResult();
            }
        } finally {
            activeRequest = null;
            setLoading(false);
        }
    }

    // --- HILFSFUNKTIONEN ---

    function addHeaderRow(key = '', value = '', enabled = true) {
        const div = document.createElement('div');
        div.className = 'header-row';
        div.innerHTML = `
            <input type="checkbox" class="h-enabled" ${enabled ? 'checked' : ''} title="Header senden (abwählen, um ihn vorübergehend abzuschalten)" aria-label="Header senden">
            <input type="text" class="input input-sm h-key" placeholder="Name" list="headerNameSuggestions" value="${escapeHtml(key)}" aria-label="Header-Name">
            <input type="text" class="input input-sm h-val" placeholder="Wert" list="headerValueSuggestions" value="${escapeHtml(value)}" aria-label="Header-Wert">
            <button type="button" class="btn btn-sm btn-outline is-danger remove-header" title="Header entfernen" aria-label="Header entfernen">✕</button>
        `;
        const enabledBox = div.querySelector('.h-enabled');
        const keyInput = div.querySelector('.h-key');
        const updateRowState = () => {
            div.classList.toggle('is-off', !enabledBox.checked);
            const blocked = BLOCKED_HEADERS.has(keyInput.value.trim().toLowerCase());
            keyInput.classList.toggle('is-blocked', blocked);
            keyInput.title = blocked ? 'Dieser Header wird vom Proxy nicht weitergeleitet' : '';
        };
        enabledBox.addEventListener('change', updateRowState);
        keyInput.addEventListener('input', updateRowState);
        // Werte-Vorschläge passend zum Header-Namen dieser Zeile
        div.querySelector('.h-val').addEventListener('focus', () => {
            const suggestions = HEADER_VALUE_SUGGESTIONS[keyInput.value.trim().toLowerCase()];
            const values = typeof suggestions === 'function' ? suggestions() : suggestions ?? [];
            headerValueSuggestions.innerHTML = values.map(v => `<option value="${escapeHtml(v)}"></option>`).join('');
        });
        div.querySelector('.remove-header').onclick = () => {
            div.remove();
            saveFormToStorage();
        };
        updateRowState();
        headerContainer.appendChild(div);
        return div;
    }

    // Alle Zeilen inkl. abgeschalteter, damit sie gespeichert und wiederhergestellt werden können
    function collectHeaderRows() {
        return [...headerContainer.querySelectorAll('.header-row')].map(row => ({
            key: row.querySelector('.h-key').value.trim(),
            value: row.querySelector('.h-val').value.trim(),
            enabled: row.querySelector('.h-enabled').checked
        })).filter(row => row.key || row.value);
    }

    // Nur aktive Header gehen an den Proxy
    function toHeaderMap(rows) {
        const headers = {};
        rows.forEach(({ key, value, enabled }) => {
            if (enabled && key) headers[key] = value;
        });
        return headers;
    }

    function newRequestId() {
        if (crypto.randomUUID) return crypto.randomUUID();
        // randomUUID gibt es nur in Secure Contexts; per http im Cluster greift dieser Fallback
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function updateBodyState() {
        const hint = BODY_HINTS[methodSelect.value];
        bodyInput.disabled = hint?.disabled ?? false;
        bodyHint.textContent = hint?.text ?? '';
        bodyHint.hidden = !hint;
    }

    function prepareUIForRequest() {
        setLoading(true);
        resetResultUI();
        statusBadge.innerText = 'Sende…';
        statusBadge.className = 'badge badge-lg badge-secondary';
        responseTimeText.innerText = '';
        responseOutput.innerText = "Sende Request an Cluster...";
    }

    function setLoading(loading) {
        submitBtn.disabled = loading;
        cancelBtn.hidden = !loading;
        if (loading) {
            submitBtn.innerHTML = `<span class="spinner spinner-sm" aria-hidden="true"></span> Sende…`;
        } else {
            submitBtn.textContent = submitBtnLabel;
        }
    }

    // Scrollt nur, wenn der Anfang des Ergebnisbereichs nicht im sichtbaren Bereich liegt
    function scrollToResult() {
        const top = resultArea.getBoundingClientRect().top;
        if (top >= 0 && top < window.innerHeight * 0.75) return;
        const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        resultArea.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    }

    // Entfernt alle Anzeigen des vorherigen Requests (Badges, TLS, Redirects, Stacktrace)
    function resetResultUI() {
        resultArea.style.display = 'block';
        errorBox.style.display = 'none';
        responseBodyArea.style.display = 'block';
        renderResponseInfo(null, null, null);
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
        responseBodyArea.style.display = 'none';
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

    // Zeigt Größe und Header des Upstream-Responses; ohne Werte wird beides ausgeblendet
    function renderResponseInfo(headers, size, truncatedAt) {
        responseSizeText.innerText = size == null ? '' : `Größe: ${formatBytes(size)}${truncatedAt ? ' (gekürzt)' : ''}`;
        if (!headers?.length) {
            responseHeadersDiv.style.display = 'none';
            return;
        }
        document.getElementById('responseHeaderCount').innerText = headers.length;
        document.getElementById('responseHeaderRows').innerHTML = headers.map(([name, value]) => `
            <tr><td class="mono muted" style="width:30%;">${escapeHtml(name)}</td><td class="mono break">${escapeHtml(value)}</td></tr>`).join('');
        responseHeadersDiv.style.display = 'block';
    }

    function formatBytes(bytes) {
        const format = (n) => n.toLocaleString('de-DE', { maximumFractionDigits: 1 });
        if (bytes < 1024) return `${bytes} B`;
        if (bytes < 1024 * 1024) return `${format(bytes / 1024)} KB`;
        return `${format(bytes / (1024 * 1024))} MB`;
    }

    function handleSuccess(data, truncatedAt) {
        errorBox.style.display = 'none';
        responseBodyArea.style.display = 'block';
        try {
            const pretty = JSON.stringify(JSON.parse(data), null, 2);
            if (pretty.length <= MAX_HIGHLIGHT_LENGTH) {
                responseOutput.innerHTML = highlightJson(pretty);
            } else {
                responseOutput.innerText = pretty;
            }
        } catch {
            responseOutput.innerText = data || "Leere Antwort";
        }
        if (truncatedAt) {
            responseOutput.append(`\n\n[Response nach ${truncatedAt} Bytes gekürzt]`);
        }
    }

    // Färbt formatiertes JSON ein. Strings werden als Ganzes erkannt, daher
    // werden Zahlen oder true/false innerhalb von Strings nicht hervorgehoben.
    const JSON_TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

    function highlightJson(pretty) {
        let html = '';
        let last = 0;
        for (const match of pretty.matchAll(JSON_TOKEN)) {
            const [token, str, colon, literal] = match;
            html += escapeHtml(pretty.slice(last, match.index));
            if (str) {
                html += `<span class="${colon ? 'json-key' : 'json-string'}">${escapeHtml(str)}</span>${colon ? escapeHtml(colon) : ''}`;
            } else if (literal) {
                html += `<span class="${literal === 'null' ? 'json-null' : 'json-boolean'}">${literal}</span>`;
            } else {
                html += `<span class="json-number">${escapeHtml(token)}</span>`;
            }
            last = match.index + token.length;
        }
        return html + escapeHtml(pretty.slice(last));
    }

    function applyWrapSetting() {
        responseOutput.classList.toggle('nowrap', !wrapToggle.checked);
    }

    function loadWrapSetting() {
        try {
            return localStorage.getItem(WRAP_STORAGE_KEY) !== 'false';
        } catch {
            return true;
        }
    }

    function handleNetworkError(msg) {
        responseOutput.innerText = msg;
        statusBadge.innerText = "Fehler";
        statusBadge.className = "badge badge-lg badge-warning";
    }

    function handleAbort() {
        responseOutput.innerText = "Request im Browser abgebrochen. Der Proxy wartet serverseitig ggf. noch bis zu seinem Timeout auf die Antwort.";
        statusBadge.innerText = "Abgebrochen";
        statusBadge.className = "badge badge-lg badge-secondary";
    }

    // --- HISTORIE & EXPORT ---

    function addToHistory(payload, status, duration, responseData, meta = {}) {
        const entry = {
            id: Date.now(),
            time: new Date().toLocaleTimeString(),
            payload, status, duration, responseData,
            protocol: meta.protocol ?? '',
            resolvedIp: meta.resolvedIp ?? '',
            redirectChain: meta.redirectChain ?? null,
            headers: meta.headers ?? null,
            size: meta.size ?? null,
            truncatedAt: meta.truncatedAt ?? null,
            headerRows: meta.headerRows ?? null
        };
        requestHistory.unshift(entry);
        if (requestHistory.length > 50) requestHistory.pop();

        saveHistoryToStorage();
        renderHistory();
    }

    function renderHistory() {
        emptyHistoryMsg.style.display = requestHistory.length ? 'none' : 'block';
        historyList.innerHTML = '';

        const urls = [...new Set(requestHistory.map(entry => entry.payload.url))];
        urlHistoryList.innerHTML = urls.map(url => `<option value="${escapeHtml(url)}"></option>`).join('');

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

    function fillForm(values) {
        document.getElementById('url').value = values.url ?? '';
        methodSelect.value = values.method ?? 'GET';
        bodyInput.value = values.body ?? '';
        document.getElementById('copyHeaders').checked = values.copyHeaders ?? true;
        updateBodyState();

        headerContainer.innerHTML = '';
        // Ältere Einträge kennen nur customHeaders (ohne abgeschaltete Zeilen)
        const rows = values.headerRows
            ?? Object.entries(values.customHeaders ?? {}).map(([key, value]) => ({ key, value, enabled: true }));
        rows.forEach(({ key, value, enabled }) => addHeaderRow(key, value, enabled));
    }

    function restoreEntry(entry) {
        fillForm({ ...entry.payload, headerRows: entry.headerRows });
        saveFormToStorage();

        resetResultUI();
        updateResponseMetadata(entry.status, entry.duration, entry.protocol, entry.resolvedIp);
        renderRedirectChain(entry.redirectChain);

        const istioPanel = document.getElementById('istioPanel');
        if (istioPanel) istioPanel.style.display = 'none';

        if (entry.status === 502 && entry.responseData.includes("---STACKTRACE---")) {
            handleDetailedError(entry.responseData);
        } else {
            // Ältere Einträge haben noch keine Header/Größe gespeichert → bleibt ausgeblendet
            renderResponseInfo(entry.headers, entry.size, entry.truncatedAt);
            handleSuccess(entry.responseData, entry.truncatedAt);
        }
        scrollToResult();
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

    function saveFormToStorage() {
        try {
            localStorage.setItem(FORM_STORAGE_KEY, JSON.stringify({
                url: document.getElementById('url').value,
                method: methodSelect.value,
                body: bodyInput.value,
                copyHeaders: document.getElementById('copyHeaders').checked,
                headerRows: collectHeaderRows()
            }));
        } catch (e) {
            console.error("Fehler beim Speichern der Eingaben im LocalStorage", e);
        }
    }

    // Liefert true, wenn gespeicherte Eingaben wiederhergestellt wurden
    function loadFormFromStorage() {
        try {
            const saved = localStorage.getItem(FORM_STORAGE_KEY);
            if (!saved) return false;
            fillForm(JSON.parse(saved));
            return true;
        } catch (e) {
            console.error("Fehler beim Laden der Eingaben aus LocalStorage", e);
            return false;
        }
    }
});