# CapyIoT

云原生智慧园区物联设备接入与分布式告警治理平台，个人开源项目。

当前版本提供工程基础、演示账号认证、Gateway 健康转发和八页导航壳子。设备接入、告警与工单业务将逐步实现，页面会明确显示接入状态。

## 本地启动

需要 Docker Engine / Docker Desktop、Docker Compose v2+ 和 Python 3.10+。首次构建需要联网下载镜像与依赖。

```sh
python scripts/init_env.py
docker compose up -d --build --wait --wait-timeout 300
```

打开 <http://localhost:8088>。账号为 `admin`（平台管理员）或 `operator`（运维人员），随机生成的密码分别保存在本地 `.env` 的 `DEMO_ADMIN_PASSWORD` 和 `DEMO_OPERATOR_PASSWORD`。再次运行初始化脚本会保留现有配置。令牌有效期为一小时，退出后清除当前浏览器会话。

`.env` 包含本地凭据，请保存在本机。已初始化 MySQL 数据卷时，应保留原有数据库凭据。服务端只读取演示密码的 PBKDF2 哈希。

```sh
python scripts/smoke.py --check-databases
docker compose ps
docker compose down
```

停止命令保留数据卷。默认仅在本机回环地址发布端口。MQTT 和 Kafka 使用本地开发配置。

八个入口依次为 `/dashboard`、`/devices`、`/topology`、`/events`、`/alarms`、`/workorders`、`/scenarios`、`/observability`。

## 开发与验证

后端需要 JDK 21，Maven Wrapper 固定 Maven 版本。集成测试使用 Testcontainers，需要正在运行的 Docker。

```sh
cd backend
./mvnw verify
```

Windows 使用 `mvnw.cmd verify`。后端模块为 Gateway、设备接入、设备管理、告警治理、工单协同及共享契约。MySQL 中三个独立业务库由各自服务的 Flyway 管理，本地共用一个 MySQL 实例。

前端需要 Node.js 24，CI 与容器构建使用相同主版本。

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm run build
npm run dev
```

开发服务器默认将 `/api` 转发到本地 `8080` 的 Gateway，可以在 Compose 运行时使用。端到端测试默认连接完整 Compose 的 `8088` 端口。

```sh
npx playwright install chromium
npm run test:e2e
```

## 可选监控

```sh
docker compose --profile monitoring up -d
```

Prometheus 位于 <http://localhost:9095>，Grafana 位于 <http://localhost:3000>。Grafana 使用 `admin` 和 `.env` 中的 `GRAFANA_ADMIN_PASSWORD` 登录，已配置 Prometheus 数据源。当前提供 Java 服务基础指标，业务监控和 Kubernetes 指标随后接入。
