#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/opt/pulseticket"
JAR_DIR="$BASE_DIR/jars"
LOG_DIR="$BASE_DIR/logs"
mkdir -p "$LOG_DIR"

# 1C2G 专属极限 JVM 参数：
# - UseSerialGC: 单核 CPU 零 GC 线程争抢，省去 G1 RememberedSet 内存开销
# - TieredStopAtLevel=1: 仅使用 C1 即时编译器，省去 40MB+ CodeCache 且防止 6 个 JVM 抢占单核 CPU 导致 100% 假死
# - Xshare:auto: 开启 CDS 类数据共享，多个 JVM 共享底层只读物理内存页
JVM_OPTS="-Xms48m -Xmx112m -Xss256k -XX:ReservedCodeCacheSize=24m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false"
SPRING_SLIM_ARGS="--server.tomcat.threads.max=20 --server.tomcat.threads.min-spare=2 --spring.datasource.hikari.maximum-pool-size=5 --spring.datasource.hikari.minimum-idle=1"

echo "===================================================="
echo " [PulseTicket 1C2G] 1. Starting Slim Middleware Containers"
echo "===================================================="
cd "$BASE_DIR/deploy/1c2g"
docker compose -f docker-compose-1c2g.yml up -d

echo "-> Waiting for Nacos (8848) & MySQL (3306) readiness..."
for i in {1..45}; do
    if curl -s http://127.0.0.1:8848/nacos/v1/console/health/readiness | grep -qi "ok"; then
        echo "   [OK] Nacos is ready!"
        break
    fi
    sleep 2
done

python3 "$BASE_DIR/tools/init_nacos_configs.py" || true

start_service() {
    local name=$1
    local port=$2
    local jar_path="$JAR_DIR/${name}-1.0.0.jar"
    local pid_file="$LOG_DIR/${name}.pid"

    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "-> [$name] already running (PID $(cat "$pid_file")), skipping."
        return
    fi

    echo "-> Starting [$name] on port $port (nice -n 10 + 1C2G SerialGC + C1 Mode)..."
    if [ "$name" = "ticket-gateway" ]; then
        nohup nice -n 10 java $JVM_OPTS -jar "$jar_path" > "$LOG_DIR/${name}.log" 2>&1 &
    else
        nohup nice -n 10 java $JVM_OPTS -jar "$jar_path" $SPRING_SLIM_ARGS > "$LOG_DIR/${name}.log" 2>&1 &
    fi
    echo $! > "$pid_file"

    # 错峰串行等待：确保单核 CPU 峰值平滑过渡后再启动下一个微服务
    for i in {1..35}; do
        if ss -tln | grep -q ":${port} "; then
            echo "   [OK] $name listening on port $port"
            break
        fi
        sleep 1
    done
}

echo "===================================================="
echo " [PulseTicket 1C2G] 2. Staggered Serial Microservice Boot"
echo "===================================================="
start_service "user-service" 8201
start_service "show-service" 8202
start_service "order-service" 8203
start_service "ticket-gateway" 8200

echo "===================================================="
echo " [PulseTicket 1C2G] All Services Started! Memory Status:"
echo "===================================================="
free -h
