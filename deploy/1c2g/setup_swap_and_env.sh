#!/usr/bin/env bash
set -euo pipefail

echo "===================================================="
echo " [PulseTicket 1C2G] Step 1: 4GB Swap & Kernel Tuning"
echo "===================================================="

CURRENT_SWAP_MB=$(free -m | awk '/^Swap:/ {print $2}')
if [ "${CURRENT_SWAP_MB:-0}" -lt 3500 ]; then
    SWAP_FILE="/swapfile_pulseticket"
    if [ ! -f "$SWAP_FILE" ]; then
        echo "-> Creating 4GB Swap file at $SWAP_FILE ..."
        fallocate -l 4G "$SWAP_FILE" || dd if=/dev/zero of="$SWAP_FILE" bs=1M count=4096 status=progress
        chmod 600 "$SWAP_FILE"
        mkswap "$SWAP_FILE"
    fi
    swapon "$SWAP_FILE" || true
    if ! grep -q "$SWAP_FILE" /etc/fstab; then
        echo "$SWAP_FILE none swap sw 0 0" >> /etc/fstab
    fi
    echo "-> 4GB Swap activated."
else
    echo "-> Swap already sufficient (${CURRENT_SWAP_MB} MB)."
fi

sysctl -w vm.swappiness=60 >/dev/null
sysctl -w vm.overcommit_memory=1 >/dev/null
if ! grep -q "vm.swappiness" /etc/sysctl.conf; then
    echo "vm.swappiness=60" >> /etc/sysctl.conf
    echo "vm.overcommit_memory=1" >> /etc/sysctl.conf
fi

echo "===================================================="
echo " [PulseTicket 1C2G] Step 2: Runtime Check (Java 17 / Docker / Nginx)"
echo "===================================================="

if ! command -v java >/dev/null 2>&1; then
    echo "-> Installing OpenJDK 17 Headless JRE..."
    apt-get update -y
    apt-get install -y openjdk-17-jre-headless
fi

if ! command -v nginx >/dev/null 2>&1; then
    echo "-> Installing Nginx..."
    apt-get update -y
    apt-get install -y nginx
fi

java -version
free -h
