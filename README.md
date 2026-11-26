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

2026-11-25 已完成阶段二基础代码与离线统一验证：34 项 Python 测试、静态检查、容器构建迁移及 Gateway/Core/Agent 联调通过。

同日已完成阶段三代码与离线整链路验收：77 项 Python 测试、45 项 Core/共享契约测试通过；独立环境中九个工具实际调用 Java API，查询回答、证据重验、幂等重放、主动/断连取消和重启恢复均通过。

用户配置模型后，已使用真实 `deepseek-flash` 验证设备查询和模拟交换机故障分析：模型实际选择并调用受控 Java 查询工具，返回带有效证据引用的结构化回答，经 Agent SSE 和 Run 查询返回；没有创建工单或确认根因。实测修复了模型超时与 Core 超时混用、累计输出预算误用，以及接近预算时无法收束查询的问题，并补充拓扑关系方向说明。复杂场景曾出现超时、预算耗尽和无效输出，修复后的场景复验通过；单次场景通过不等于全面的模型准确性或稳定性评估，正式业务结论仍须人工核查。修复后统一复验通过：87 项 Python 测试、静态检查及九工具离线整链路检查；45 项 Core/共享契约测试因对应源码未变化复用此前结果。不宣称已验证原生 Token 级流式输出。

`agent-service` 使用 Python 3.12、FastAPI、SQLAlchemy/Alembic、HTTPX 和 PydanticAI。`uv.lock` 固定依赖；官方 DeepSeek Provider 的模型客户端与调用 Java Core 的客户端独立。Agent 账号只拥有 `agent_db` 权限，每次用户请求都校验 Gateway RS256/JWKS 并向 Core 核对最新会话和授权。

先执行 `python scripts/init_env.py`，再在本地 `.env` 填写 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`；可通过 `DEEPSEEK_BASE_URL` 配置模型 API 地址。现有 MySQL 数据卷需要先执行：

```sh
docker compose up -d mysql
python scripts/ensure_agent_db.py
docker compose --profile agent up -d --build
```

模型请求默认超时 30 秒，可用 `AGENT_MODEL_TIMEOUT_SECONDS` 调整；这与 Core API 的短超时独立。单次模型响应最多 4096 个输出 Token，整轮多步调用的累计输出预算默认 8192，可用 `AGENT_RUN_OUTPUT_TOKEN_BUDGET` 调整。接近请求数、工具数或输出预算时，会收起查询工具并提醒模型完成最终回答；硬上限依然生效，不自动无限追加预算。运行总时长、模型请求次数和工具次数仍有独立上限，模型服务异常不会无限重试。截断的结构化输出不会作为成功答案返回。

Agent 使用独立的一次性迁移容器执行 Alembic，再启动服务。`/health/live` 检查进程，`/health/ready` 分别检查数据库迁移、Core 和模型元数据，失败返回 503；探针不生成模型回答。缺少必需配置时启动失败。通过 Gateway 可访问 `/api/v1/agent/conversations` 的创建/列表及指定会话历史；对话严格按当前用户隔离。

阶段二提供服务基础，阶段三接入了 9 个只读业务工具及消息查询链路。用户通过 `POST /api/v1/agent/conversations/{id}/messages` 发起查询，必填 `Idempotency-Key`，并声明 `Accept: text/event-stream`。响应提供运行/工具进度及校验后的结构化回答；`GET /api/v1/agent/runs/{id}` 查询持久化结果，`POST /api/v1/agent/runs/{id}/cancel` 主动取消。阶段五已提供前端对话入口，也可直接通过 API 使用。

工具覆盖设备、状态历史、异常、故障详情、拓扑、工单和维修历史，参数不接受模型自报身份或园区权限。异常严重程度沿用新领域的 `WARNING/FAULT`，不混用历史告警枚举。列表默认 20 条、最大 50 条；拓扑深度最大 3、节点上限 100。裁剪、权限受限和数据新鲜度不足会明确标记。ToolCall、来源对象 ID/版本/时间和证据引用保存至 Agent 自有数据库；原始设备载荷和模型思维链不落库。

时间筛选须使用 RFC3339 UTC，单次最大跨度默认 31 天；只提供一侧边界时由服务端补齐有界范围。工单及维修历史列表的时间筛选按工单创建时间执行。历史拓扑关系与当前节点状态分开展示，不将当前在线状态冒充历史状态。

历史答案与有界对话上下文在读取时重新查询 Core 校验：授权版本、可见来源对象或结果范围变化时保守隐藏旧答案；没有可重验来源的旧答案也保持隐藏。历史对话不能充当权限凭证。消息响应只发送经过 Schema 与引用校验的回答，`answer.delta` 当前为一次完整结构化回答片段，不承诺模型 Token 级实时输出或重放。客户端断开即取消运行；已完成工具记录和证据保留。中断的只读 Run 达到期限后可查询到超时终态。

阶段三建立只读查询链路；阶段四新增的 `prepare_work_order` 只保存 Agent 草稿，模型没有创建正式工单或修改根因的工具。

全部阶段二编码完成后，通过 `python scripts/verify_stage2.py` 显式执行离线统一验证；它使用独立 MySQL/Compose 数据卷和本地模型元数据替身，结束后关闭测试环境、保留原始报告在 `var/verification/`。测试覆盖真实 Provider 的离线 HTTP 序列化，但不能证明真实 DeepSeek 的工具调用、流式输出及模型兼容性；这些须在配置模型后另行联调。CI 仍只手动触发。

阶段三统一验证入口为 `python scripts/verify_stage3.py`：串行执行 Python 测试、Core/共享契约回归，以及虚拟交换机故障→真实 Java API→九个 Agent 工具→可追溯回答的整链路验收。只有模型使用确定性的离线替身，其余接入、数据与用户权限来自独立部署的真实平台。支持 `--resume-report <本地报告>`，仅按输入哈希复用成功构建检查，部署与查询场景始终重新执行。这个离线场景验证系统链路，不代表真实模型的推理质量或 DeepSeek 兼容性已经验证。

### 工单草稿与人工确认

2026-11-25 已完成阶段四编码与验收：107 项 Python 测试、41 项 Core + 6 项共享契约测试、7 项浏览器检查通过。独立平台验证九工具只读回归和模拟故障→草稿→拒绝/版本冲突/并发确认→Java 多设备正式工单闭环；成功检查仅在源码哈希相同时复用。真实 `deepseek-flash` 另完成一次设备证据查询与草稿生成，确认前无新增工单，确认后正式创建成功且重复确认不重复创建。真实模型结果限于已测样本，不代表广泛准确性或性能容量验收。

当前支持 9 个只读工具和 1 个草稿工具。用户主动要求创建维修工单后，Agent 核查设备与来源故障并保存草稿，SSE 发出 `draft.ready`；Run 进入 `WAITING_CONFIRMATION`。通过工单中心的“审阅 Agent 草稿”入口，或 `/agent-drafts/<draftId>` 链接查看设备范围、Incident 来源、优先级、描述、预期结果和证据。完整对话入口已在阶段五接入。

`GET /api/v1/agent/drafts/{draftId}` 只允许 owner 读取，并重验当前业务数据权限。确认或拒绝分别使用 `POST /api/v1/agent/drafts/{draftId}/confirm`、`/reject`，请求均须携带 `Idempotency-Key` 及用户实际审阅的 `{draftRevision,contentHash}`；拒绝可附 `reason`。确认需要 `agent.draft.confirm` 和 `workorder.create`，版本冲突返回 409，过期返回 410，业务状态变化使草稿失效。确认接口返回 202；用户不确认或拒绝时不创建正式工单。

确认事务仅在 agent_db 原子写入确认、执行记录和命令 Outbox。进程内 Dispatcher 领取命令后，使用独立 RS256 服务密钥签发 60 秒委托 JWT，绑定确认人、草稿版本、请求 Hash 和原幂等键。Java 校验签名/请求绑定并重新加载当前用户授权，最终检查同园区、设备状态、Incident Gate 和优先级规则，再创建正式工单。多设备明细和幂等回执在 Java 自有事务中保存。用户 Token 与委托 JWT 都不持久化。

Compose 的 `agent` profile 启用 Dispatcher，并在独立 `agent-keys` 数据卷中首次生成服务密钥。直接运行服务时，需配置 `AGENT_DISPATCH_ENABLED=true`、`AGENT_DELEGATION_KEY_FILE`；仅本地首次初始化可设置 `AGENT_DELEGATION_KEYS_GENERATE=true`。未启用派发时确认接口返回 503，不会静默积压确认。公钥地址为 Agent 内部 `/.well-known/agent-jwks.json`，Core 必须配置可信的 Agent JWKS 地址。密钥轮换命令如下；轮换保留最近公钥供短时旧凭证验证，不打印私钥：

```sh
docker compose --profile agent exec agent-service python -m app.delegation --file /keys/agent-keys.json --rotate
```

派发默认每秒领取一条命令、60 秒租约、最多 3 次尝试。Java 成功但响应丢失、租约中断等情况进入 `RECONCILIATION_REQUIRED`，沿用原幂等键回查回执；禁止换键重建。超过自动尝试上限后保留结果未知状态，需由运维核对，不声称创建失败或自动解除。确认后撤权会被 Java 拒绝；若此前结果已未知，撤权不能被当作“没有创建”的证明。草稿页可查询执行状态并跳转正式工单。

阶段四统一验证入口为 `python scripts/verify_stage4.py`：Python、Java、前端检查后，在独立 Compose 中运行九工具只读回归及草稿/拒绝/并发确认/真实委托 Java 创建场景，再运行浏览器用例。使用确定性的本地模型替身，不消耗真实模型额度。支持 `--resume-report <本地报告>`，仅源码哈希相同的成功检查可复用；部署及业务场景重新执行。Windows 可复用已安装的 Chrome；其他环境可加 `--install-browser` 安装 Chromium，或设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`。模型替身覆盖链路与确定性行为；上述真实模型样本另行实测。

### AI 运维助手

2026-11-26 阶段五已完成编码与验收：108 项 Python、8 项消息流/证据边界、14 项浏览器检查通过（含修复后的定向复验）；Java 源码未改，沿用相同源码哈希的阶段四 47 项结果。真实部署的平台已通过页面查询、历史恢复、同会话后续草稿、明确确认、正式工单展示、离开页面取消与重新进入恢复。此阶段模型使用离线替身，设备使用模拟器；不冒充本阶段真实模型质量或实体设备验收。

启用 `docker compose --profile agent up -d --build` 并登录具备 `agent.use` 的业务账号后，可从侧栏“AI 运维助手”进入 `/assistant`。输入问题创建对话，支持 Ctrl + Enter 发送、工具进度、事实/推断/建议/未知分区、证据引用和设备/Incident/拓扑/工单详情跳转。页面沿用现有浅色蓝色主题；不会把来源文本当 HTML 渲染。

会话列表与消息历史分页加载。历史接口兼容 `after`，新增 `latest=true` 读取最近消息和 `before=<messageSeq>` 读取更早消息，响应始终保持时间正序，并返回 `hasMoreBefore/hasMoreAfter/firstSequence/lastSequence`。消息附当前授权的 Run 摘要；同一页同一 Run 只重验一次，隐藏回答时同时隐藏引用。Run 的 owner 范围 `requestKey` 用于核对浏览器提交是否已受理，不包含用户令牌。

页面使用 fetch 读取 POST SSE，支持 UTF-8 分块、CRLF、多行 data、重复事件去重和有界缓冲，拒绝混入其他 Run 的事件。只展示工具的安全进度，不显示隐藏推理或原始工具参数。收到 `answer.delta` 后读取并展示经当前权限校验的持久化回答；当前后端输出为完整结构化片段，不宣称原生逐 Token 生成。

对话内的工单草稿复用独立审阅页的组件。确认前须核对设备范围、描述、优先级和证据，并明确确认当前 revision/contentHash；不会把自然语言“同意”自动变成创建命令。创建结果可直接跳转正式工单。

停止查询、离开页面或断线会中断当前订阅，服务端取消尚未完成的 Run，保留已保存结果与草稿。刷新或恢复连接只查询历史/Run，不自动重发消息；首次回执丢失时通过 owner、会话、原请求键和 Run 标记恢复。仅在用户明确点击重试时复用原幂等键。本地不保存回答和证据缓存；窗口重新聚焦先隐藏旧内容再核查当前权限。

阶段五统一验证入口：`python scripts/verify_stage5.py`。包括 Python、Java（相同源码哈希可复用）、8 项消息流/证据边界检查、前端 lint/typecheck/build，以及独立平台中的真实 API 与浏览器“查询→历史恢复→后续草稿→明确确认→正式工单”验收。模型使用本地确定性替身，其余服务真实运行；设备使用模拟器。量化容量与广泛模型质量评估仍待后续统一实测。

### 平台监控

```sh
docker compose --profile monitoring up -d
```

平台管理员可查看系统可观测页面。Prometheus 为 `http://localhost:9095`，Grafana 为 `http://localhost:3000`，Jaeger 为 `http://localhost:16686`。监控包含 Core、接入及过渡服务；旧查询缓存与新业务指标不能混作同一容量结果。无采样时不宣称性能已达标。

Helm 部署文件位于 `infra/helm/capyiot`。先生成本地配置和 TLS，再通过 `scripts/k8s_secrets.py --context <context> --namespace capyiot` 创建运行凭据与服务端证书 Secret。CA 私钥不上传。设备公钥/用户公钥分别发布，私钥分别使用 PVC。Kubernetes 及旧 KEDA 演示保留为可选部署，当前重构尚待统一验证；旧 device-state-group 的伸缩配置不代表新版 Core 容量结论。

Agent 只响应用户主动请求；不新增 SuperAgent、多 Agent、RAG、视频流模块或任意 SQL/HTTP/shell 工具。业务查询和命令始终通过经授权的 Java API 完成。
