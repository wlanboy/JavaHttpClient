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
    const responseTimeText = document.getElementById('responseTime');
    const stacktraceArea = document.getElementById('stacktraceArea');
    const toggleStackBtn = document.getElementById('toggleStackBtn');

    // Historie Elemente
    const historyList = document.getElementById('historyList');
    const emptyHistoryMsg = document.getElementById('emptyHistoryMsg');
    const clearHistoryBtn = document.getElementById('clearHistoryBtn');
    const exportHistoryBtn = document.getElementById('exportHistoryBtn');

    let requestHistory = [];
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
            toggleStackBtn.textContent = isHidden ? 'Stacktrace ausblenden' : 'Stacktrace Details';
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

            updateResponseMetadata(response.status, duration);
            addToHistory(payload, response.status, duration, data);

            if (response.status === 502 && data.includes("---STACKTRACE---")) {
                handleDetailedError(data);
            } else {
                handleSuccess(data);
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
        div.className = 'row g-2 mb-2 header-row';
        div.innerHTML = `
            <div class="col-5"><input type="text" class="form-control form-control-sm h-key" placeholder="Key" value="${key}"></div>
            <div class="col-6"><input type="text" class="form-control form-control-sm h-val" placeholder="Value" value="${value}"></div>
            <div class="col-1 text-end"><button type="button" class="btn btn-sm btn-outline-danger remove-header">✕</button></div>
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
        resultArea.style.display = 'block';
        errorBox.style.display = 'none';
        responseOutput.style.display = 'block';
        responseOutput.innerText = "Sende Request an Cluster...";
        stacktraceArea.style.display = 'none';
        if (toggleStackBtn) toggleStackBtn.textContent = 'Stacktrace Details';
    }

    function updateResponseMetadata(status, duration) {
        statusBadge.innerText = `HTTP ${status}`;
        statusBadge.className = `badge p-2 ${status >= 200 && status < 300 ? 'bg-success' : 'bg-danger'}`;
        responseTimeText.innerText = `Dauer: ${duration}ms`;
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
        statusBadge.className = "badge bg-warning text-dark";
    }

    // --- HISTORIE & EXPORT ---

    function addToHistory(payload, status, duration, responseData) {
        const entry = {
            id: Date.now(),
            time: new Date().toLocaleTimeString(),
            payload, status, duration, responseData
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
            item.className = `list-group-item list-group-item-action border-start border-4 ${isErr ? 'border-danger' : 'border-success'} d-flex align-items-center p-0`;

            item.innerHTML = `
            <div class="flex-grow-1 p-2 cursor-pointer">
                <div class="d-flex justify-content-between">
                    <strong class="small">${entry.payload.method}</strong>
                    <small class="text-muted" style="font-size: 0.7rem;">${entry.time}</small>
                </div>
                <div class="text-truncate x-small text-muted" style="max-width: 180px;">${entry.payload.url}</div>
                <div class="mt-1">
                    <span class="badge ${isErr ? 'bg-danger' : 'bg-success'}" style="font-size: 0.65rem;">${entry.status}</span> 
                    <small class="x-small">${entry.duration}ms</small>
                </div>
            </div>
            <button class="btn btn-sm text-danger opacity-50 px-2 delete-history-item" title="Löschen">
                <i class="bi bi-trash"></i>✕
            </button>
        `;

            item.querySelector('.flex-grow-1').onclick = () => restoreEntry(entry);

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

        resultArea.style.display = 'block';
        updateResponseMetadata(entry.status, entry.duration);

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