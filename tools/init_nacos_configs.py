import os
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT_ENV = Path(__file__).resolve().parents[1] / ".env"
env_map = {}
if ROOT_ENV.exists():
    for line in ROOT_ENV.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            env_map[k.strip()] = v.strip()

NACOS_ADDR = env_map.get("NACOS_SERVER_ADDR", "127.0.0.1:8848")
MYSQL_HOST = env_map.get("MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = env_map.get("MYSQL_PORT", "3306")
MYSQL_USER = env_map.get("MYSQL_USER", "root")
MYSQL_PASSWORD = env_map.get("MYSQL_PASSWORD", "123456")
REDIS_HOST = env_map.get("REDIS_HOST", "127.0.0.1")
REDIS_PORT = env_map.get("REDIS_PORT", "6379")
RABBITMQ_HOST = env_map.get("RABBITMQ_HOST", "127.0.0.1")
RABBITMQ_PORT = env_map.get("RABBITMQ_PORT", "5672")
RABBITMQ_USER = env_map.get("RABBITMQ_USER", "guest")
RABBITMQ_PASS = env_map.get("RABBITMQ_PASS", "guest")
JWT_SECRET = env_map.get("JWT_SECRET", "PulseTicketMicroserviceGatewayJwtSecretKey2026EnterpriseEdition8899")

SHARED_CONFIGS = {
    "shared-jdbc.yaml": f"""# Nacos 共享数据库与连接池配置 (被 user-service / show-service / order-service 共同引用)
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: {MYSQL_USER}
    password: {MYSQL_PASSWORD}
    hikari:
      minimum-idle: 2
      maximum-pool-size: 15
      connection-timeout: 10000
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
""",
    "shared-redis.yaml": f"""# Nacos 共享 Redis 与 Redisson 分布式锁配置
spring:
  data:
    redis:
      host: {REDIS_HOST}
      port: {REDIS_PORT}
      timeout: 5000ms
""",
    "shared-mq.yaml": f"""# Nacos 共享 RabbitMQ 可靠消息配置 (开启 Publisher Confirm + Returns + 消费者 Manual ACK)
spring:
  rabbitmq:
    host: {RABBITMQ_HOST}
    port: {RABBITMQ_PORT}
    username: {RABBITMQ_USER}
    password: {RABBITMQ_PASS}
    virtual-host: /
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 10
""",
    "shared-jwt.yaml": f"""# Nacos 共享 JWT 密钥与白名单配置
pulseticket:
  jwt:
    secret: {JWT_SECRET}
""",
    "shared-oss.yaml": f"""# Nacos 共享阿里云 OSS (ticket-ppday 华北2·北京) 配置
pulseticket:
  oss:
    endpoint: {env_map.get("OSS_ENDPOINT", "oss-cn-beijing.aliyuncs.com")}
    bucket: {env_map.get("OSS_BUCKET", "ticket-ppday")}
    access-key-id: {env_map.get("OSS_ACCESS_KEY_ID", "")}
    access-key-secret: {env_map.get("OSS_ACCESS_KEY_SECRET", "")}
""",
}


def wait_nacos_ready():
    url = f"http://{NACOS_ADDR}/nacos/v1/console/health/readiness"
    for _ in range(30):
        try:
            with urllib.request.urlopen(url, timeout=2) as resp:
                if resp.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def publish_all():
    wait_nacos_ready()
    api_url = f"http://{NACOS_ADDR}/nacos/v1/cs/configs"
    for data_id, content in SHARED_CONFIGS.items():
        payload = urllib.parse.urlencode(
            {
                "dataId": data_id,
                "group": "DEFAULT_GROUP",
                "type": "yaml",
                "content": content,
            }
        ).encode("utf-8")
        req = urllib.request.Request(api_url, data=payload, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read().decode("utf-8")
            print(f"[Nacos Config Published] {data_id} -> {body}")


if __name__ == "__main__":
    publish_all()
