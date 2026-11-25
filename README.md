# CapyIoT

面向园区视频监控设备的运维平台。当前设备范围为 IPC（网络摄像机）、NVR（录像存储主机）和交换机，不处理视频画面，不远程控制设备。

阶段一业务重构已于 2026-11-25 完成统一验收：后端 79 项测试、前端静态检查与构建、4 项浏览器用例、Helm 静态验证，以及独立 Compose 环境中的安全接入和维修闭环均通过。现阶段无实体硬件，模拟器只能证明协议与软件链路，不能代替厂商兼容或现场验收；性能容量指标尚未实测。

## 业务流程

登记区域、资产和监控点位 → 建立网络、PoE 和录像通道关系 → 设备安全上报 → 产生异常及待研判故障 → 人工确认并创建工单 → 维修人员逐台记录处置结果 → 指定验收人独立验收 → 主管核查并关闭故障。

连接状态、运行模式、数据新鲜度和健康状态分别展示。在线不代表录像正常。关联线索不会自动确认为根因，也不会自动创建工单。工单验收通过不自动关闭故障。

页面统一使用蓝白灰浅色主题。本地托管 Poppins、思源黑体和品牌字体 Lora，字体许可随前端静态资源提供。

## 本地启动

需要 Docker/Compose、Python 3.10+ 和 OpenSSL。开发编译使用 JDK 21、Maven Wrapper、Node.js 24。

```sh
python scripts/init_env.py
python scripts/init_tls.py
docker compose up -d --build --wait --wait-timeout 300
```

普通页面入口为 `http://localhost:8088`，HTTPS 入口为 `https://localhost:8443`。浏览器可导入本地 `var/tls/ca.pem` 作为受信开发 CA。设备会话只接受经受信 HTTPS 入口转发的请求，设备消息只通过 MQTTS 8883；不开放设备明文 1883。

`.env`、`var/` 中的私钥和设备凭据仅存本地。初始化脚本保留已有配置与数据库密码，只补齐新增项。新环境会生成随机初始密码；本机已有密码不会被重置。CA 私钥不挂载到应用容器或上传集群。

演示账号：

- `admin`：平台账号与授权管理，不自动拥有园区业务权限。
- `supervisor`：设备、关系维护及故障、工单管理。
- `operator`：业务查询和创建工单。
- `repairer`：处理被分派的维修任务。
- `acceptor`：独立验收被分派的任务。

前两个账号的初始密码来自 `.env` 的 `DEMO_ADMIN_PASSWORD`，其余来自 `DEMO_OPERATOR_PASSWORD`。服务器仅使用对应 PBKDF2 哈希。

用户令牌使用持久化登录会话及独立 RS256 签名。每次请求重新加载用户状态和最新授权；退出、禁用、撤权使旧会话失效，授权服务不可用时拒绝继续访问。`/.well-known/jwks.json` 只提供用户公钥。签名密钥保存在独立数据卷；轮换时原子替换 JWK Set，新私钥放首项，保留旧公钥至少一个令牌有效期。

## 虚拟设备接入

```sh
python scripts/video_devices.py
docker compose --profile simulation build simulator
docker compose --profile simulation run --rm simulator --rounds 3
docker compose --profile simulation run --rm simulator --scenario switch-outage --rounds 10 --fault-seconds 60
```

注册脚本通过正式 REST API 创建一台交换机、两台 IPC、一台 NVR、监控点位及三类关系，再投入使用。一次性设备密钥立即保存到 `var/video-devices.json`。脚本不自动重置已有凭据、不重新启用停用设备、不替换他人的点位绑定。凭据丢失时须显式轮换。

每个虚拟设备通过 HTTPS Device Session API 获取 15 分钟 RS256 JWT，以自己的 deviceId 作为 MQTT username/clientId，JWT 仅放 CONNECT password。Broker 校验签名、身份绑定、到期时间及本会话专属 Topic ACL。客户端校验证书和主机名。JWT 不写入业务消息、配置、数据库或日志。

Topic 为 `capyiot/v1/devices/{deviceId}/sessions/{sessionId}/events`，QoS 1、非 retained。设备只能发布本设备当前会话 Topic，不能订阅其他设备。每条消息经过接入服务校验，并重新查询活动会话；Kafka 确认后才完成接入订阅端 ACK。

HEARTBEAT、状态观察、资源故障、恢复分别使用规范 streamKey 和独立递增序号。同一会话续签保留序号，新会话递增服务端 epoch。重试保留原事件 ID 和内容。模拟器暂存在内存中的未发消息随进程退出丢失，不承诺离线持久化。

`switch-outage` 使交换机报告设备异常，暂停两台下游 IPC 的心跳，并报告录像通道异常，之后恢复同一资源。平台依据接收时间判断失联，以历史关系形成待研判候选。`--duplicate-first true` 可重复发送首条消息。

凭据轮换、停用或退役撤销活动会话，并通过持久化断连任务踢除 Broker 连接。旧 JWT 的离线签名验证在到期前可能仍通过，但旧会话不能更新平台状态；不宣称 stateless JWT 可即时全局撤销。旧协议模拟器只可显式 `--protocol legacy` 用于隔离历史环境，当前安全 Broker 不支持旧协议。

## 服务与接口

Java Core 是业务权威，内部资产、故障、工单、平台模块分别使用 device_db、alarm_db、workflow_db、platform_db 的独立账号与本地事务，通过受控查询接口协作。旧服务过渡期保留只读历史与运维补偿入口，关闭旧消息消费、自动建单和场景写入。Redis 只承载派生缓存。

业务事务写入本领域 Outbox；Kafka 至少一次投递，Inbox 和业务序号处理重复与乱序。不可恢复的消费失败进入对应 `.dlq` Topic，保留源消息和定位信息，不承诺端到端 Exactly Once。修复来源后可在受控运行环境保留原事件 ID 重放。未知建单结果保留待对账许可，不按超时自动释放。

公开 OpenAPI 位于 `GET /api/v1/openapi`，需要有效用户令牌；内部登录、会话查询等接口不列入公开文档。写命令携带 `Idempotency-Key`、`X-Request-Id`、`X-Correlation-Id`，并提交相关 expectedVersion。首次创建返回 201，同键同请求重放返回 200；设备密钥不会因重放再次显示。

主要接口：`/devices`、`/areas`、`/monitoring-points`、`/topology/context`、`/events`、`/anomalies`、`/incidents`、`/workorders`、`/audit-records`。列表统计先按当前用户范围过滤；多设备工单、故障需要全部实际关联设备可见，不能用部分设备权限推断完整对象。

历史数据保留原 ID 和映射，不把旧 Alarm 一对一伪装成新 Incident。历史查询保留 `/api/v1/alarms`、`/api/v1/legacy/events`、`/api/v1/legacy/workorders`；这些历史业务入口只读。旧五状态工单不冒充新版八状态维修验收记录。

## 统一验证

全部编码完成后再统一执行。本节命令不会因每次 commit/push 自动运行。2026-11-25 已执行并通过统一验收；每次运行的实际结果和复用来源保存在本地报告中。

```sh
python scripts/verify_stage1.py --install-browser
```

脚本使用独立 Compose 项目、数据卷及设备凭据文件，串行执行后端测试、模拟器构建、前端检查、Helm 静态验证、真实 TLS/MQTT 权限与旧会话拒绝、页面验收，以及故障研判—维修—独立验收—关闭流程。已有 CapyIoT 环境运行时明确退出，不自动停止用户环境；结束后保留验证卷，原始报告只写本地 `var/verification/`。

本地浏览器已安装时可省略 `--install-browser`。Windows 可设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 使用已有 Chromium。验收前需停止原 Compose 环境以让出端口，保留数据卷。GitHub CI 只手动触发，新版统一验证与固定提交的历史协议回归分开；历史通过结果不能作为新版验收证据。

修复验收失败后，可通过 `--resume-report var/verification/<上次运行目录>/report.json` 继续。脚本仅复用输入文件哈希完全相同的成功构建检查，并记录来源；部署、协议、页面和业务闭环始终在新的隔离环境中重新执行。

量化指标在主要代码完成后统一实测，无数据时展示缺失，不填估计值。

## 部署与观测

### Agent 服务基础

2026-11-25 已完成阶段二基础代码与离线统一验证：34 项 Python 测试、静态检查、容器构建迁移及 Gateway/Core/Agent 联调通过。真实 DeepSeek 联调按用户选择留待模型配置后执行，当前不宣称真实模型兼容性已验收。

同日已完成阶段三代码与离线整链路验收：77 项 Python 测试、45 项 Core/共享契约测试通过；独立环境中九个工具实际调用 Java API，查询回答、证据重验、幂等重放、主动/断连取消和重启恢复均通过。真实模型的工具选择与推理效果仍待配置后实测。

`agent-service` 使用 Python 3.12、FastAPI、SQLAlchemy/Alembic、HTTPX 和 PydanticAI。`uv.lock` 固定依赖；官方 DeepSeek Provider 的模型客户端与调用 Java Core 的客户端独立。Agent 账号只拥有 `agent_db` 权限，每次用户请求都校验 Gateway RS256/JWKS 并向 Core 核对最新会话和授权。

先执行 `python scripts/init_env.py`，再在本地 `.env` 填写 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`；可通过 `DEEPSEEK_BASE_URL` 配置模型 API 地址。现有 MySQL 数据卷需要先执行：

```sh
docker compose up -d mysql
python scripts/ensure_agent_db.py
docker compose --profile agent up -d --build
```

Agent 使用独立的一次性迁移容器执行 Alembic，再启动服务。`/health/live` 检查进程，`/health/ready` 分别检查数据库迁移、Core 和模型元数据，失败返回 503；探针不生成模型回答。缺少必需配置时启动失败。通过 Gateway 可访问 `/api/v1/agent/conversations` 的创建/列表及指定会话历史；对话严格按当前用户隔离。

阶段二提供服务基础，阶段三接入了 9 个只读业务工具及消息查询链路。用户通过 `POST /api/v1/agent/conversations/{id}/messages` 发起查询，必填 `Idempotency-Key`，并声明 `Accept: text/event-stream`。响应提供运行/工具进度及校验后的结构化回答；`GET /api/v1/agent/runs/{id}` 查询持久化结果，`POST /api/v1/agent/runs/{id}/cancel` 主动取消。前端对话界面仍留后续阶段，当前可通过 API 使用。

工具覆盖设备、状态历史、异常、故障详情、拓扑、工单和维修历史，参数不接受模型自报身份或园区权限。异常严重程度沿用新领域的 `WARNING/FAULT`，不混用历史告警枚举。列表默认 20 条、最大 50 条；拓扑深度最大 3、节点上限 100。裁剪、权限受限和数据新鲜度不足会明确标记。ToolCall、来源对象 ID/版本/时间和证据引用保存至 Agent 自有数据库；原始设备载荷和模型思维链不落库。

时间筛选须使用 RFC3339 UTC，单次最大跨度默认 31 天；只提供一侧边界时由服务端补齐有界范围。工单及维修历史列表的时间筛选按工单创建时间执行。历史拓扑关系与当前节点状态分开展示，不将当前在线状态冒充历史状态。

历史答案与有界对话上下文在读取时重新查询 Core 校验：授权版本、可见来源对象或结果范围变化时保守隐藏旧答案；没有可重验来源的旧答案也保持隐藏。历史对话不能充当权限凭证。消息响应只发送经过 Schema 与引用校验的回答，`answer.delta` 当前为一次完整结构化回答片段，不承诺模型 Token 级实时输出或重放。客户端断开即取消运行；已完成工具记录和证据保留。中断的只读 Run 达到期限后可查询到超时终态。

阶段三不注册 `prepare_work_order`，不创建草稿、正式工单或修改根因。草稿确认、服务委托签名与正式命令派发留阶段四实现。

全部阶段二编码完成后，通过 `python scripts/verify_stage2.py` 显式执行离线统一验证；它使用独立 MySQL/Compose 数据卷和本地模型元数据替身，结束后关闭测试环境、保留原始报告在 `var/verification/`。测试覆盖真实 Provider 的离线 HTTP 序列化，但不能证明真实 DeepSeek 的工具调用、流式输出及模型兼容性；这些须在配置模型后另行联调。CI 仍只手动触发。

阶段三统一验证入口为 `python scripts/verify_stage3.py`：串行执行 Python 测试、Core/共享契约回归，以及虚拟交换机故障→真实 Java API→九个 Agent 工具→可追溯回答的整链路验收。只有模型使用确定性的离线替身，其余接入、数据与用户权限来自独立部署的真实平台。支持 `--resume-report <本地报告>`，仅按输入哈希复用成功构建检查，部署与查询场景始终重新执行。这个离线场景验证系统链路，不代表真实模型的推理质量或 DeepSeek 兼容性已经验证。

### 平台监控

```sh
docker compose --profile monitoring up -d
```

平台管理员可查看系统可观测页面。Prometheus 为 `http://localhost:9095`，Grafana 为 `http://localhost:3000`，Jaeger 为 `http://localhost:16686`。监控包含 Core、接入及过渡服务；旧查询缓存与新业务指标不能混作同一容量结果。无采样时不宣称性能已达标。

Helm 部署文件位于 `infra/helm/capyiot`。先生成本地配置和 TLS，再通过 `scripts/k8s_secrets.py --context <context> --namespace capyiot` 创建运行凭据与服务端证书 Secret。CA 私钥不上传。设备公钥/用户公钥分别发布，私钥分别使用 PVC。Kubernetes 及旧 KEDA 演示保留为可选部署，当前重构尚待统一验证；旧 device-state-group 的伸缩配置不代表新版 Core 容量结论。

Agent 只响应用户主动请求；不新增 SuperAgent、多 Agent、RAG、视频流模块或任意 SQL/HTTP/shell 工具。业务查询和命令始终通过经授权的 Java API 完成。
