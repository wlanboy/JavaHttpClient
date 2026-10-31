/**
 * html-utils.js
 * Escaping für Werte, die per innerHTML / Template-String in die Seite geschrieben werden.
 * Deckt Text- und (in Anführungszeichen stehende) Attribut-Kontexte ab.
 */

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
}
