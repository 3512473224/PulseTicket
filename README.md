# PulseTicket · 企业级演出与电影在线选座微服务系统

> **个人简历项目三（Spring Cloud Alibaba 微服务全栈代表作）**
> 基于 **Spring Cloud Alibaba 2023.0.1.0 + Spring Boot 3.2.5 + JDK 17 + Nacos + OpenFeign + Sentinel + Redisson + Seata AT + RabbitMQ (TTL + DLX 死信队列) + MySQL 8.0 + Vue 3 + TypeScript + Tailwind CSS** 构建的高并发演出票务选座与分布式事务微服务系统。

---

## 一、 为什么这个项目是中小厂/大厂实习面试的“满分收割机”？

在 Java 后端校招与日常实习面试中，面试官考察微服务项目最核心的 **6 大必考连环问**，在本项目中全部具备 **100% 真实可运行、可现场演示、可压测验证** 的工程落地：

| 核心考点 | 本项目工程落地实现 | 对应源码位置 |
| :--- | :--- | :--- |
| **1. 标准工程分层与解耦** | 抽离 `ticket-common`（统一 `Result<T>`、全局异常、分页、`ThreadLocal`）与独立 `ticket-feign-api`（存放 `@FeignClient` 契约、DTO 与 Sentinel `FallbackFactory`，彻底解决服务间循环依赖） | `ticket-common/`、`ticket-feign-api/` |
| **2. Gateway + ThreadLocal + OpenFeign 三棒登录态透传** | ① `AuthGlobalFilter` 统一验签 JWT，**先 `remove` 客户端伪造同名请求头**，再注入 `X-User-Id` / `X-Username` / `X-Trace-Id`；<br>② 下游 `UserInfoInterceptor` 写入 `ThreadLocal`，并在 `afterCompletion` 中强制 `clear()` 防内存泄漏与线程串号；<br>③ `FeignRelayAutoConfig` 实现 `RequestInterceptor` 继续向下游透传身份头与 Seata `TX_XID`。 | `AuthGlobalFilter.java`、`UserContext.java`、`UserInfoInterceptor.java`、`FeignRelayAutoConfig.java` |
| **3. Nacos 注册发现与共享配置中心** | 4 个微服务全部注册至 Nacos (`:8848`)，并将 MySQL、Redis、RabbitMQ、JWT 公共配置抽离为 `shared-jdbc.yaml`、`shared-redis.yaml`、`shared-mq.yaml`、`shared-jwt.yaml` 统一管理。 | `tools/init_nacos_configs.py` |
| **4. Redis 缓存三座大山防护** | 演出详情查询完整落地：① **防穿透**：不存在 ID 写入 `__NULL__` 空值哨兵（60s 短 TTL）；② **防击穿**：热点失效使用 Redisson `RLock` 互斥锁 + 双重检查锁重建；③ **防雪崩**：基础 TTL 1800s + 随机抖动 60~360s。 | `ShowCacheAndSeatService.java` |
| **5. Redisson 三层防重锁 + 锁包在事务外** | 外层非事务 `OrderFacade` 先加 **用户防连点锁 (`lock:order:submit:{userId}`，不设 leaseTime 启用 30s WatchDog 自动续期)** + **按 `seatId` 升序排列的 `RedissonMultiLock` 联锁（消除交叉死锁）**，再进入内层 `@GlobalTransactional OrderTxService`，并在 `finally` 中（事务提交后）释放锁。 | `OrderFacade.java`、`OrderTxService.java` |
| **6. Seata AT 跨库事务 + RabbitMQ 死信延迟关单与消费幂等** | ① **同步跨库锁座**：`order-service` (TM) 同步 Feign 调 `show-service` (RM) 锁定座位，基于 `undo_log` 前后镜像实现异常自动回滚（支持前端一键注入故障回滚演练）；<br>② **异步延迟关单**：拆分为 `15m` 标准队列与 `15s` 极速演示队列（彻底规避单队列不同 TTL 的**队头阻塞问题**），到期路由至死信交换机触发 `WHERE status='UNPAID'` 的 CAS 自动关单并归还座位；<br>③ **支付成功 Fanout 广播与幂等**：`user-service` 消费加积分与发电子票通知，依赖 `UNIQUE KEY uk_order_biz (order_no, biz_type)` 保证 100% 消费幂等。 | `OrderTxService.java`、`OrderMqConfigAndListener.java`、`UserOrderPaidConsumer.java` |
| **7. 主 Graph + 3 子图 Multi-Agent 编排与资金确定性兜底** | ① **意图路由 + 子图隔离**：主图通过 `input_guard` 防注入护栏与 `intent_router` 将复合提问拆解为有序意图队列，按序调度 **知识问答子图（Agentic RAG：查询改写 → BM25+向量+RRF → 证据充分性判定 → 盲区诚实拒答）**、**选座购票子图** 与 **订单售后子图（确定性阶梯退票算费）**；<br>② **零幻觉核价与幂等确认**：写操作强制向 Java 服务端 `/quote` 重新核验价格与余座，通过 LangGraph `interrupt()` 推送 SSE 确认卡片，用户点击后携带 `thread_id:tool_call_id` 幂等键执行真实创单。 | `ticket-agent/app/graph/`、`AgentChatWidget.vue` |

---

## 二、 系统模块与端口拓扑

```text
pulseticket/
├── pom.xml                       # Maven 父工程 (Spring Boot 3.2.5 + Spring Cloud Alibaba 2023.0.1.0)
├── ticket-common/                # 公共基础模块 (Result / BizException / UserContext ThreadLocal / JwtUtils)
├── ticket-feign-api/             # 独立远程调用契约模块 (ShowSeatClient / DTO / Sentinel FallbackFactory / Feign 透传)
├── ticket-gateway/               # API 网关服务 (:8200) -> 路由鉴权、防伪造头清洗、转发 /api/agent/**
├── user-service/                 # 用户与积分服务 (:8201, DB: ticket_user) -> JWT 登录、ThreadLocal 档案、MQ 幂等积分与站内信
├── show-service/                 # 演出与座位库存服务 (:8202, DB: ticket_show) -> Redis 三防缓存、服务端权威核价 /quote、Seata AT 锁座 RM
├── order-service/                # 订单与事务协调服务 (:8203, DB: ticket_order) -> Redisson 三层锁、Idempotency-Key 幂等、Seata AT TM、RabbitMQ DLX
├── ticket-agent/                 # Python Multi-Agent 智能体服务 (:8204) -> 主 Graph + 3 子图、Hybrid RAG、SSE 流式、Trace 审计与 40 条离线评测集
├── web/                          # Vue 3 + TypeScript + Tailwind CSS 选座大厅、AI 悬浮对话窗与实时观测前端 (:5190)
├── deploy/                       # Docker Compose (Redis / RabbitMQ / Nacos / Seata) 与三库 SQL 初始化脚本
├── tools/                        # Nacos 配置初始化脚本与 6 大微服务场景全自动端到端验收脚本 (verify_microservices.py)
└── docs/                         # 配套渐进式源码拆解讲义与思考题
```

---

## 三、 快速启动与全链路自动化验收

### 1. 一键启动全栈环境
双击运行根目录下的 [`start_dev.bat`](file:///c:/Users/lan/Desktop/项目/pulseticket/start_dev.bat)，或按顺序执行：
```powershell
# 1. 启动中间件容器
docker compose -f deploy/docker-compose.yml up -d

# 2. 初始化 Nacos 共享配置
python tools/init_nacos_configs.py

# 3. 编译打包全部微服务模块
mvn clean package -DskipTests
```

### 2. 运行端到端全自动验证套件 (`verify_microservices.py`)
```powershell
python -X utf8 tools/verify_microservices.py
```
该脚本将自动通过网关 (`:8200`) 完整验证：
1. **Gateway JWT 鉴权 + `ThreadLocal` 透传 + `/internal/**` 外部隔离**
2. **Redis 缓存三座大山防护**（互斥锁重建 `MYSQL_MUTEX_REBUILT`、缓存命中 `REDIS_HIT`、空值哨兵 `PENETRATION_PROTECTED` -> `REDIS_NULL_GUARD`）
3. **30 线程并发争抢同一个座位压测**（验证严格 **1 单成功、29 单被拦截、0 超卖**）
4. **Seata AT 二阶段故障注入与跨库自动回滚**（验证异常后座位自动由 `LOCKED` 恢复为 `AVAILABLE`，且记录 `ROLLED_BACK` 审计日志）
5. **订单支付 + RabbitMQ Fanout 广播**（验证座位变更为 `SOLD`、用户积分幂等增加、签发电子票核验码）
6. **RabbitMQ 15 秒极速死信延迟关单**（等待 16.5 秒验证订单自动变更为 `CLOSED (TIMEOUT_UNPAID)` 且座位自动恢复为 `AVAILABLE`）

### 3. 运行 AI Multi-Agent 单元测试与 40 条基准评测集
```powershell
cd ticket-agent
python -X utf8 -m unittest discover -s tests -v
python -X utf8 eval/run_eval.py
```

---

## 四、 配套源码精读讲义（含课后思考题与参考答案）
- [01_核心链路拆解_Redisson三层锁与Seata分布式事务.md](file:///c:/Users/lan/Desktop/项目/pulseticket/docs/01_核心链路拆解_Redisson三层锁与Seata分布式事务.md)
- [02_核心链路拆解_RabbitMQ死信关单与缓存幂等.md](file:///c:/Users/lan/Desktop/项目/pulseticket/docs/02_核心链路拆解_RabbitMQ死信关单与缓存幂等.md)
