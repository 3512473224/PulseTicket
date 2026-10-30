@echo off
chcp 65001 >nul
echo ============================================================================
echo   PulseTicket · 企业级演出与电影在线选座微服务系统 (一键启动脚本)
echo   Spring Cloud Alibaba 2023 + Nacos + Seata AT + RabbitMQ DLX + Redisson
echo ============================================================================

cd /d "%~dp0"

echo [1/4] 正在检查并启动 Docker 中间件容器 (Redis / RabbitMQ / Nacos / Seata)...
docker compose -f deploy/docker-compose.yml up -d

echo [2/4] 正在向 Nacos 推送 4 份共享配置 (shared-jdbc / shared-redis / shared-mq / shared-jwt)...
python tools/init_nacos_configs.py

echo [3/4] 正在启动 4 个 Spring Cloud 微服务 (Gateway:8200 / User:8201 / Show:8202 / Order:8203)...
for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" if not "%%A:~0,1%"=="#" set "%%A=%%B"
)

start "PulseTicket-UserService:8201" cmd /k "java -Xms128m -Xmx256m -jar user-service/target/user-service-1.0.0.jar"
start "PulseTicket-ShowService:8202" cmd /k "java -Xms128m -Xmx256m -jar show-service/target/show-service-1.0.0.jar"
start "PulseTicket-OrderService:8203" cmd /k "java -Xms128m -Xmx256m -jar order-service/target/order-service-1.0.0.jar"
start "PulseTicket-Gateway:8200" cmd /k "java -Xms128m -Xmx256m -jar ticket-gateway/target/ticket-gateway-1.0.0.jar"

echo [4/4] 正在启动 Vue 3 + Tailwind CSS 选座与观测前端 (:5190)...
start "PulseTicket-Web:5190" cmd /k "cd web && npm run dev"

echo ============================================================================
echo   启动指令已下发！请访问前端地址: http://localhost:5190
echo   网关入口地址: http://localhost:8200
echo ============================================================================
pause
