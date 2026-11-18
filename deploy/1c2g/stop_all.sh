#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="/opt/pulseticket/logs"

for svc in ticket-gateway order-service show-service user-service; do
    PID_FILE="$LOG_DIR/${svc}.pid"
    if [ -f "$PID_FILE" ]; then
        PID=$(tr -d '[:space:]' < "$PID_FILE")
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null && ps -p "$PID" -o args= 2>/dev/null | grep -q "${svc}-1.0.0.jar"; then
            echo "Stopping $svc (PID $PID)..."
            kill "$PID" || true
        fi
        rm -f "$PID_FILE"
    fi
done

AGENT_PID_FILE="$LOG_DIR/ticket-agent.pid"
if [ -f "$AGENT_PID_FILE" ]; then
    AGENT_PID=$(tr -d '[:space:]' < "$AGENT_PID_FILE")
    if [ -n "$AGENT_PID" ] && kill -0 "$AGENT_PID" 2>/dev/null && ps -p "$AGENT_PID" -o args= 2>/dev/null | grep -q "8204"; then
        echo "Stopping ticket-agent (PID $AGENT_PID)..."
        kill "$AGENT_PID" || true
    fi
    rm -f "$AGENT_PID_FILE"
fi
