# CapyIoT

云原生智慧园区物联设备接入与分布式告警治理平台，个人开源项目。

当前版本支持从场景控制台发起 MQTT 故障演示，经离线巡检、拓扑关联告警到工单恢复验证。驾驶舱图表使用设备状态历史和当前告警数据，可观测中心展示服务就绪探测。

## 本地启动

需要 Docker Engine / Docker Desktop、Docker Compose v2+ 和 Python 3.10+。首次构建需要联网下载镜像与依赖。

```sh
python scripts/init_env.py
docker compose up -d --build --wait --wait-timeout 300
```

打开 <http://localhost:8088>。账号为 `admin`（平台管理员）或 `operator`（运维人员），随机生成的密码分别保存在本地 `.env` 的 `DEMO_ADMIN_PASSWORD` 和 `DEMO_OPERATOR_PASSWORD`。再次运行初始化脚本会保留现有配置。令牌有效期为一小时，退出后清除当前浏览器会话。

`.env` 包含本地凭据，请保存在本机。初始化脚本保留现有值并补齐新增配置。已初始化 MySQL 数据卷时，应保留原有数据库凭据。服务端只读取演示密码的 PBKDF2 哈希。

```sh
python scripts/smoke.py --check-databases
docker compose ps
docker compose down
```

停止命令保留数据卷。默认仅在本机回环地址发布端口。MQTT 和 Kafka 使用本地开发配置。

八个入口依次为 `/dashboard`、`/devices`、`/topology`、`/events`、`/alarms`、`/workorders`、`/scenarios`、`/observability`。

## 设备接入演示

先完成本地启动，再注册包含交换机、摄像头、门禁和环境传感器的 12 台演示设备。

```sh
python scripts/demo_devices.py
docker compose --profile simulation up -d --build simulator
```

模拟器默认每 10 秒发送一轮心跳。打开设备资产中心查看在线状态与最后心跳，打开实时事件中心查看实际接收的事件、处理状态和 JSON。暂停追加只影响当前页面，恢复或断线重连后会从持久化游标补齐事件。

```sh
docker compose --profile simulation stop simulator
docker compose --profile simulation run --rm --no-deps simulator --rounds 2 --interval-ms 1000 --start-seq 0 --duplicate-first true
```

有限轮次示例会额外重复发送首轮的相同 eventId，可在事件中心验证去重。执行前停止已有模拟器，避免两次运行竞争设备会话。上报心跳的设备连续 30 秒未收到新心跳时，5 秒巡检会将其切换为离线；新心跳恢复连接状态，健康异常需要单独恢复。

注册和编辑仅平台管理员可操作，运维账号可查询。设备注册返回一次性设备凭据，数据库保存 PBKDF2 哈希。演示脚本将模拟器凭据写入本地 `var/demo-devices.json`，重复运行复用已有设备。保留此文件与数据库数据卷的对应关系，凭据遗失时可用 `--prefix NEWDEMO` 注册另一组设备。`.env`、`var/` 均被 Git 和 Docker 构建上下文排除。

设备通过 `POST /api/v1/device-sessions` 以设备 ID、凭据和 sessionId 获取带服务端会话代次的令牌，然后向 `capyiot/{campusId}/devices/{deviceId}/events` 发布 QoS 1 消息。消息包含 eventId、eventType、schemaVersion、campusId、deviceId、sessionId、seq、occurredAt、payload 和 sessionToken。模拟器提供完整调用示例。

接入服务校验身份与消息结构，Kafka 确认后手动确认 MQTT 消息。Device Service 按设备处理事件，拒绝状态回退并幂等落库，再更新 Redis；Redis 故障时保留消费重试。消息令牌在标准化时移除，非法消息以摘要和原因进入隔离主题。OpenAPI JSON 位于 `/api/v1/openapi`，使用登录接口返回的 Bearer 令牌访问，覆盖设备、空间、拓扑和事件接口。

## 浏览器场景演示

运行 `python scripts/demo_devices.py` 后，以管理员登录场景模拟中心。使用场景中心之前，请停止独立模拟器，避免两个发布端同时操作同一批演示设备。

```sh
docker compose --profile simulation stop simulator
```

1. 选择 **交换机故障**，选择演示交换机并开始模拟。预热确认心跳后，交换机发出故障事件，直属演示设备暂停上报。约 30–35 秒后，实际离线巡检会触发主子告警。
2. 从场景的事件证据进入告警，打开自动创建的工单。领取或指派工单，开始处理并提交恢复验证。
3. 点击 **立即恢复**，或等到设定的持续时间。观察恢复事件和心跳，再验证、关闭工单。可通过运行记录深链检查参数、状态与操作日志。

**设备状态抖动** 会观察两次真实离线和中间一次上线，默认持续 90 秒。**温度越限** 保持传感器心跳，默认上报 41℃，恢复阶段上报 25℃。**停止** 结束故障注入并恢复上报，记录停止结果。每个园区同一时间可运行一个场景，运维账号可查看记录。Device Service 从只读的本地 `var/demo-devices.json` 读取演示设备凭据，以正常设备会话发送 MQTT；运行结束后由当前进程继续为本次设备发送心跳。

## 命令行拓扑告警演示

完成演示设备注册和模拟器镜像构建后，运行以下有限场景。它先发送心跳，再触发故障，最后恢复。

```sh
docker compose --profile simulation stop simulator
docker compose --profile simulation run --rm --no-deps simulator --scenario alarm-demo
```

- 1 号楼交换机主动报告故障，同时两台下游设备停止心跳。超时后告警中心显示已确认的上游故障及子告警。
- 2 号楼仅有两台直属下游停止心跳，形成疑似上游故障。拓扑以橙色虚线标识疑似链路，以红色实线标识已确认链路。
- 3 号楼传感器保持在线，温度越限产生独立告警。约一分钟后，模拟器恢复心跳、上报交换机恢复与正常温度。持续观察数据时可启动常规模拟器。

在告警详情可查看子告警、受影响设备和原始证据，并跳转到拓扑、MQTT 事件或已关联工单。操作员和管理员均可确认活跃主告警。默认拓扑窗口 60 秒，疑似门槛为 2 台直属下游。温度告警在 35℃ 触发、降至 33℃ 恢复。告警 OpenAPI 在 `/api/v1/alarms/openapi`，使用相同的 Bearer 令牌访问。

## 告警工单闭环

从场景中心或 `alarm-demo` 触发 1 号楼故障后，打开已确认的关联主告警。系统通过 Kafka 自动生成一张工单，告警详情提供 **查看工单**。疑似与独立告警可从主告警详情或工单中心手动创建，同一来源复用同一张工单。

以运维人员登录后，可领取、开始处理、添加处置记录并提交验证。以管理员登录可指派给演示账号。故障恢复后来源告警同步更新，记录验证依据，再关闭工单。每一步都保留操作人、时间、版本和说明。工单详情可回到来源告警，刷新后仍能查看历史。

工单 REST 契约在 `/api/v1/workorders/openapi`。告警和工单的 Outbox 在各自业务事务中写入，Kafka 确认后标记为已发。Workflow Inbox 将消息去重与建单放在同一事务内。进程重启后会继续处理到期的未发消息，超过重试上限的记录可由管理员补偿。

- 生产失败可分别通过 `GET /api/v1/alarms/outbox/dead` 或 `GET /api/v1/workorders/outbox/dead` 查询。用对应的 `POST .../outbox/{id}/retry` 重新排队，保留原事件 ID。
- 工单消费失败可在 `GET /api/v1/workorders/dead-letters` 查询，在恢复故障依赖后通过 `POST /api/v1/workorders/dead-letters/{id}/retry` 重新处理。仍不符合契约的事件返回 409，供管理员核查来源。上述接口均需管理员 Bearer 令牌。

## 开发与验证

后端需要 JDK 21，Maven Wrapper 固定 Maven 版本。集成测试使用 Testcontainers，需要正在运行的 Docker。

```sh
cd backend
./mvnw verify
```

Windows 使用 `mvnw.cmd verify`。后端模块为 Gateway、设备接入、设备管理、告警治理、工单协同及共享契约。MySQL 中三个独立业务库由各自服务的 Flyway 管理，本地共用一个 MySQL 实例。

可单独构建 Java 模拟器，在项目根目录运行。

```sh
./backend/mvnw -f simulator/pom.xml verify
java -jar simulator/target/device-simulator-0.1.0-SNAPSHOT.jar --rounds 2
```

前端需要 Node.js 24，CI 与容器构建使用相同主版本。

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm run build
npm run dev
```

开发服务器默认将 `/api` 转发到本地 `8080` 的 Gateway，可以在 Compose 运行时使用。端到端测试默认连接完整 Compose 的 `8088` 端口，需要先运行演示设备注册脚本并构建模拟器镜像，验证注册编辑、持久化、重复消息、实时暂停和断线补齐。

```sh
npx playwright install chromium
npm run test:e2e
```

## 可选监控

```sh
docker compose --profile monitoring up -d
```

Prometheus 位于 <http://localhost:9095>，Grafana 位于 <http://localhost:3000>。Grafana 使用 `admin` 和 `.env` 中的 `GRAFANA_ADMIN_PASSWORD` 登录，已配置 Prometheus 数据源。当前提供 Java 服务基础指标，业务监控和 Kubernetes 指标随后接入。
