# CapyIoT

面向园区视频监控设备的运维平台，覆盖 IPC、NVR 和交换机的安全接入、状态与故障监控、人工派工、维修和独立验收。模拟器实现协议与故障场景，不处理视频画面，不远程控制设备，也不能替代实体硬件的兼容与现场验收。

当前部署包含设备、故障、工单、平台和 AI 运维五个业务服务，以及 Gateway、device-ingestion，共七个后端应用。2026-11-27 已完成统一构建与功能验收、已有库升级和恢复演练，并切换本地体验环境。真实 DeepSeek 的查询、人工确认建单和维修验收闭环已联调；小规模延迟与消息恢复基线已实测，未约定生产容量 SLA，不将本机基线作为生产容量结论。

设备连接状态、运行模式、数据新鲜度和健康状态分别展示。在线不代表录像正常；关联线索不自动确认根因或创建工单，工单验收通过也不自动关闭故障。AI 仅使用受控查询工具及待人工确认的草稿，确认后由持久化 Dispatcher 调用工单服务，同一命令重试保留原幂等键。

## 部署文件

使用独立的 `compose.microservices.yml`，必须显式指定目标文件与项目。旧默认 Compose、Core Maven/运行模块和临时桥接接口已删除，旧阶段启动脚本明确退役。默认状态关闭消费、扫描、领域 Outbox 和 Agent 派发；前端入口和设备接入需要单独启用的 profile。所有新服务调用使用固定 HTTPS 地址、内部 CA 和独立 RSA 服务身份，人类、设备、工单确认委托的密钥分别管理。工单确认委托仅接受 `capyiot-workflow`，没有可重新启用的旧 audience 窗口。

数据库账号分别限定到 `device_db`、`alarm_db`、`workflow_db`、`platform_db`、`agent_db`。Gateway 和 ingestion 不持有业务库凭据，Agent 只有自身库权限。Java 服务由各自 Flyway 管理本库，Agent 使用 Alembic；应用不生成替代签名密钥，不执行 Flyway clean。

准备 Docker/Compose、Python 3.12、uv；统一源码验证还需要 JDK 21、Maven Wrapper、Node.js 24。以下命令是部署和验收的操作示例，项目名、端口与本地路径需按目标环境显式选择。

## 全新隔离环境

先安装锁定的 Python 依赖并创建本地 bundle：

```sh
uv sync --project agent-service --locked
uv run --project agent-service --no-sync python scripts/init_microservices.py --project capyiot-ms-verify-local --bundle var/bundles/capyiot-ms-verify-local --mode fresh --model-mode offline
```

初始化只写忽略的 `var/`，不启动容器，不访问数据库。它创建独立服务 JWK、TLS 证书、账号凭据和非敏感 `deployment.env`；重复运行保留原文件，已有文件缺失或变化时拒绝静默替换。bundle 目录限制宿主机访问；供非 root 容器读取的单个 Secret 文件只读挂载，各容器只挂载自己需要的文件。CA 私钥从不挂载进容器。

非敏感配置格式见 `.env.microservices.example`，不能直接将示例当作已经初始化的配置。默认网页端口为 28088/28443，MQTTS 为 28883，与原体验环境分开；可在本地 `deployment.env` 调整端口。项目、物理数据卷前缀、模型模式与 bundle 绑定，不能临时换成其他环境。

离线模式必须同时使用 `infra/compose.microservices-test.yml`。它只替代模型提供方，应用之间仍校验真实 TLS 和服务身份。构建镜像不会启动业务：

```sh
docker compose --project-directory . -p capyiot-ms-verify-local --env-file var/bundles/capyiot-ms-verify-local/deployment.env -f compose.microservices.yml -f infra/compose.microservices-test.yml --profile public --profile ingress --profile simulation build
python scripts/microservices_control.py preflight --project capyiot-ms-verify-local --compose-file compose.microservices.yml --env-file var/bundles/capyiot-ms-verify-local/deployment.env --report-dir var/rollouts/native-local
python scripts/microservices_control.py start-passive --project capyiot-ms-verify-local --compose-file compose.microservices.yml --env-file var/bundles/capyiot-ms-verify-local/deployment.env --report-dir var/rollouts/native-local --execute
python scripts/microservices_control.py activate --project capyiot-ms-verify-local --compose-file compose.microservices.yml --env-file var/bundles/capyiot-ms-verify-local/deployment.env --report-dir var/rollouts/native-local --execute
```

`start-passive` 要求全新的项目和数据卷，启动迁移和后台任务关闭的应用。`activate` 检查候选镜像 ID、代码提交与单写者状态，再通过单独的 `active.env` 开启原逻辑消费者、扫描、Outbox、派发和入口。shell 中残留的 Compose 项目、文件和 writer 环境变量不能覆盖工具选择的目标。中途失败不自动重试激活或启动旧写入者，应先查看本地状态文件。

入口为 `http://localhost:28088` 和 `https://localhost:28443`；HTTPS/MQTTS 客户端信任 bundle 中的 `pki/edge/ca.pem`，不要关闭证书验证。内部服务端口不直接发布到宿主机，Gateway 的独立 HTTP 管理端口只在容器网络使用。所有数据卷保留，工具不执行 `down -v`。

初始账号：`admin` 管理平台账号与授权；`supervisor` 管理园区设备、故障与工单；`operator` 查询并建单；`repairer` 处理分派任务；`acceptor` 独立验收。前两个账号使用本地 `secrets/demo-admin-password`，其余使用 `secrets/demo-operator-password`；服务端只读取哈希。全新环境生成随机密码，升级不会重置原密码或 Session。

真实模型模式使用 `--model-mode real`，从本地显式提供的环境读取 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL` 和可选 `DEEPSEEK_BASE_URL`；升级时也可从指定的原配置文件导入。密钥写入本地 Secret 文件，不能发到聊天或提交 Git。真实模式不添加离线覆盖文件，也不会自动调用模型生成验收答案。

仅升级导入与受控回退工具仍识别原 Core 容器名和旧稳定指纹配置；这些代码不构建或发布 Core，也不向它发业务请求。历史 Kafka group 名、信封 producer、原预约回执恢复和只读历史查询保留，用于接续原位点和数据，不代表保留旧写入者。

## 已有数据接管

本流程适用于保留的旧 Core Compose 环境向候选服务接管。原应用、镜像、数据卷和 key 卷不得删除。先创建独立候选 bundle，并显式选择原项目、原物理卷前缀、配置与 TLS 目录，例如：

```sh
uv run --project agent-service --no-sync python scripts/init_microservices.py --project capyiot-native --bundle var/bundles/capyiot-native --mode upgrade --source-project capyiot --volume-prefix capyiot --legacy-env .env --legacy-tls var/tls --model-mode real
```

初始化只读导出原 Gateway、设备、Agent 三个签名 ring，保留原 kid、公钥重叠、密码、命令指纹和边缘 CA。不能因为文件缺失就给已有业务签发新身份；自动导出无法定位原容器时，用 `--import-keys` 指定安全导出的三个原文件。已有账号或原指纹缺失时停止，不 ALTER USER、不重新播种已有平台账号。升级模式关闭平台 bootstrap。

在候选 `deployment.env` 中选择正式入口端口；需要继续使用原 URL 时，填入原 8088/8443/8883，只有旧入口冻结后才开放。构建使用与全新环境相同的显式 Compose 参数，真实模型模式去掉离线覆盖文件。然后用相同的候选目标参数，按顺序调用控制工具：

1. `preflight`：检查四个物理数据卷、原签名 ring、命令指纹、五库账号权限与配置；不修改数据库。
2. `freeze --execute`：先停原前端、网关、模拟器、Agent、接入和 Broker，再停业务写入者，保留 Kafka 积压、消费组及 Inbox 身份。
3. `snapshot --execute`：在冻结状态生成同一次五库事务快照、逐表计数与完整校验、迁移版本、Outbox/Inbox/未决操作摘要、Kafka 消费位点/日志边界/Topic 配置、原容器与镜像基线，以及受保护的 key/证书副本。报告目录权限受限，SQL 和原始记录不能上传 Git。
4. `start-passive --execute`：停止原基础容器，保留物理卷；冷拷贝停止后的 Kafka 数据，再启动候选迁移与被动应用。检查没有其他容器同时挂载权威数据卷，并核对 Broker 重启后的原位点。原消费者组名字保持不变，不从 earliest 另建组补救。
5. 在隔离项目恢复 `baseline`，取得匹配 SQL 摘要的 `restore-proof.json` 后，审查被动状态与候选运行基线。
6. `activate --restore-proof <匹配的恢复证明> --execute`：显式开启新单写者与路由。旧写入者保持停止；只有这一目标可接收新业务。完成统一验收后再决定保留与退出旧交付物。

所有动作必须显式提供 `--project`、`--compose-file`、`--env-file`、`--report-dir`；同一次接管共用报告目录。`--execute` 仅授权该次所选动作。工具记录每步状态和镜像 ID，发生未知结果时保留冻结状态，不进行自动回滚。

## 恢复与回退

隔离恢复使用另一个未使用的 `capyiot-ms-verify-*` 项目、全新 bundle 和卷。只启动该项目的 MySQL，将五库整体恢复并核对表校验、计数和迁移；不启动恢复库的业务写入者：

```sh
python scripts/microservices_control.py restore-rehearsal --project capyiot-ms-verify-restore --compose-file compose.microservices.yml --env-file var/bundles/capyiot-ms-verify-restore/deployment.env --report-dir var/verification/restore-01 --backup-dir var/rollouts/native/baseline --execute
```

此命令会重建所选隔离目标的五个 Schema，必须使用独立、未使用的恢复目标。备份摘要不匹配、目标已有容器或卷、缺少 Schema 校验时拒绝继续。

尚未激活新写入者时，可对原接管记录调用 `rollback --restore-proof <匹配的证明文件> --execute`。工具停止候选，核对 Kafka 原位点与日志边界、原镜像与容器，完整恢复原五库后停在 `SOURCE_RESTORED_STOPPED`。检查恢复结果，再用同目标的 `resume-source --execute` 恢复原来运行的服务；模拟器另行显式重连。迁移产生的表也需整体恢复，不能只改 Flyway 版本号或重新启动旧镜像。

只要激活可能发生过，即使激活命令失败，也禁止自动执行上述旧代码回退。应调用 `freeze-active`、`snapshot-active` 保存新数据和未决状态，在独立项目恢复并核对新旧业务记录、确认、Outbox 与预约，再决定向前修复或经过审查的数据回迁。不能用旧 SQL 覆盖已接受的新写入，不能用 TTL、404 或缺少工单推断预约已释放。Kafka 冷副本和位点用于完整恢复、比对与定位，工具没有 offset-reset 操作；超过保留窗口或出现额外生产者时停止接管，重新确认数据恢复方案。

## 统一验证

全部编码完成后才运行。必须选择隔离项目、候选 Compose、配置、全新报告目录和测试组，不连接默认体验环境：

```sh
python scripts/verify_microservices.py --project capyiot-ms-verify-local --compose-file compose.microservices.yml --env-file var/bundles/capyiot-ms-verify-local/deployment.env --report-dir var/verification/native-01 --groups unit build runtime protocol workflow agent browser recovery
```

测试组覆盖 Java/Python/前端、镜像、七应用与授权路由、MQTTS、维修验收、Agent 查询/确认/取消/恢复、浏览器和五库恢复。需要时加 `--install-browser`。只运行所选组，报告标记为 `PASSED_SELECTED_GROUPS`，不能将部分通过写成全部验收。恢复组另建隔离项目，完成后停止它；失败保留日志、容器和卷，不停止其他项目。

失败后的定向复验可使用新的报告目录，并显式传入 `--resume-rollout <原 ACTIVE rollout.json>`，只允许恢复该隔离项目的已激活目标，保留原失败证据；这不代表重新验证了空库启动。服务账号隔离、停机与独立重启由 `scripts/microservices_failure_smoke.py` 验证；`scripts/measure_microservices.py` 提供有界 API、设备上报、Kafka 暂停恢复及确认回执采样。两者要求显式的 `CAPYIOT_MS_TARGET`、`CAPYIOT_CREDENTIAL_FILE`、`CAPYIOT_CA_FILE` 和 `CAPYIOT_API_BASE`，目标必须是隔离验证项目，原始结果只保存在忽略目录。

旧 `verify_stage1–5` 只保留历史记录，新的入口不调用其默认启动流程。适用的协议、维修和 Agent 场景由新入口注入 HTTPS、CA、账号文件和精确 Compose 目标。离线模型成功不代表真实模型、硬件兼容或性能验收通过。

入口的所选测试组不是完整 MS-09 的自动替代：服务故障注入、已有库升级、原历史数据连续性、真实模型、体验环境接管和量化指标必须分别保存证据，完成全部必需项后才能声明验收通过。源码边界检查可单独运行 `python scripts/check_microservices_boundaries.py --report var/verification/boundaries.json`，它只核对源码与配置，不启动或测试应用。

## 设备接入、接口与观测

登记资产与点位、网络/PoE/录像关系后，设备通过 HTTPS Session API 获取短期 RS256 JWT，以 deviceId 作为 MQTT username/clientId，JWT 只放 CONNECT password。Topic 为 `capyiot/v1/devices/{deviceId}/sessions/{sessionId}/events`，QoS 1、非 retained、专属发布 ACL；消息通过校验且 Kafka 确认后才完成接入端 ACK。重试保留事件 ID 与原内容，平台按独立流序号和服务端 epoch 去重、处理乱序。

停用、退役或轮换凭据撤销活动 Session，并通过持久化断连任务踢除旧连接。旧 JWT 在到期前仍可能通过离线验签，但旧会话不能更新平台状态。无实体设备时使用模拟器的 steady、switch-outage 场景；模拟器内存中的待发消息随进程退出丢失，不承诺离线持久化。

`GET /api/v1/openapi` 返回独立服务文档目录，需当前用户登录。主要业务路径为 `/devices`、`areas`、`monitoring-points`、`topology/context`、`events`、`anomalies`、`incidents`、`workorders`、`audit-records`。内部接口即使具有契约描述，也不能从网关直接调用。历史告警、事件、工单保持原 ID 的受范围过滤只读查询，旧 SSE 明确退役。

已确认草稿的历史引用如因查询范围或业务结果变化而无法复核，页面隐藏引用并提示先核对原工单，保留同一执行回执；确认前仍严格校验证据、revision 和 contentHash。读取仍检查草稿所有者及当前设备/故障权限，重复确认不生成第二笔执行。

页面保持蓝白灰浅色主题，业务数据在进入页面或手动刷新时获取。依赖不可用显示未知、缺失或待核对，不伪装为正常或零。工单未知结果保留当前登录内的原请求和幂等键，提供操作状态回查；浏览器整页重载后的手工未决请求需依据操作/工单记录核对，不从空列表推断没有执行。

监控按需启用 `monitoring` profile；基础配置默认关闭追踪导出。候选 Prometheus 使用内部 CA 采集六个 Java 应用，Gateway 通过专用容器内管理端口采集；Agent 不伪装 JVM 指标。可在本地配置 `MS_PROMETHEUS_URL=http://prometheus:9090`、`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://jaeger:4318/v1/traces` 后显式启动对应 profile 并重建需更新配置的容器。监控使用候选项目自己的卷，保留原监控数据；应用与可选组件均有资源上限，这些是配置预算，不是实测容量。

保留的 Helm Chart 和旧 Kubernetes 辅助发布脚本属于历史非支持路径。当前七应用及前端的 Kubernetes 部署使用独立的 [Kustomize 清单与发布工具](infra/k8s/README.md)，基础设施保持集群外运行；现有微服务 Compose 部署继续保留。代码交付、隔离运行验收与已有体验环境接管分别记录，所选测试组通过不代表完整云原生验收通过。
