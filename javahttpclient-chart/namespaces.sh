#!/bin/bash

# Legt die Namespaces an, die der JavaHttpClient ueberwacht, und aktiviert dort
# die Istio-Sidecar-Injection. Ohne das Label filtert der Istio-Webhook den
# Namespace per namespaceSelector aus und es wird kein Sidecar injiziert.
#
# Aufruf:
#   bash namespaces.sh                  # Standard-Namespaces
#   bash namespaces.sh clients demo     # eigene Liste

# --- Konfiguration ---
DEFAULT_NAMESPACES=("clients")

# Fehlerbehandlung
set -euo pipefail

if [ "$#" -gt 0 ]; then
    NAMESPACES=("$@")
else
    NAMESPACES=("${DEFAULT_NAMESPACES[@]}")
fi

# --- 1. System-Check ---
echo "--- System-Check ---"
if ! command -v kubectl &>/dev/null; then
    echo "❌ kubectl nicht gefunden. Abbruch."
    exit 1
fi

if ! kubectl cluster-info &>/dev/null; then
    echo "❌ Kein Zugriff auf den Cluster (Kontext: $(kubectl config current-context 2>/dev/null || echo 'unbekannt')). Abbruch."
    exit 1
fi
echo "✅ Kontext: $(kubectl config current-context)"

# --- 2. Namespaces anlegen & labeln ---
echo -e "\n--- Namespaces ---"
for NS in "${NAMESPACES[@]}"; do
    if kubectl get namespace "$NS" &>/dev/null; then
        echo "-> Namespace $NS existiert bereits."
    else
        echo "-> Lege Namespace $NS an..."
        kubectl create namespace "$NS"
    fi

    # Revision-basierte Injection (istio.io/rev) und istio-injection schliessen sich aus;
    # istio-injection hat Vorrang, daher ein gesetztes Revision-Label nicht ueberschreiben.
    REV=$(kubectl get namespace "$NS" -o jsonpath='{.metadata.labels.istio\.io/rev}')
    if [ -n "$REV" ]; then
        echo "⚠️  $NS nutzt Revision-Label istio.io/rev=$REV, istio-injection wird nicht gesetzt."
        continue
    fi

    kubectl label namespace "$NS" istio-injection=enabled --overwrite
done

# --- 3. Abschluss ---
echo -e "\n--- Ergebnis ---"
kubectl get namespace "${NAMESPACES[@]}" -L istio-injection -L istio.io/rev

echo -e "\nHinweis: Bereits laufende Pods erhalten den Sidecar erst nach einem Neustart:"
echo "  kubectl rollout restart deployment -n <namespace>"
