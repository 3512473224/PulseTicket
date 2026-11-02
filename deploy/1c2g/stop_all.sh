#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="/opt/pulseticket/logs"
for svc in ticket-gateway order-service show-service user-service; do
    PID_FILE="$LOG_DIR/${svc}.pid"
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            echo "Stopping $svc (PID $PID)..."
            kill "$PID" || true
        fi
        rm -f "$PID_FILE"
    fi
done
