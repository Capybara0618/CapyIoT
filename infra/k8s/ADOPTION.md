# 当前微服务 Compose 的受控接管

默认交付独立 Kubernetes 验证环境，体验环境继续使用 Compose。本流程用于以后明确选择的运行切换，不随全新部署自动执行；历史 Core 接管脚本不适用于这里。

1. 指定当前微服务源项目、私有 bundle、目标集群/namespace、窗口和运行资源。保存容器/镜像 ID、网络/卷、五库迁移与逐表校验、Kafka topics/groups/offset、未决工单预约/操作、Agent执行/Outbox，确认完整恢复证明。
2. 停止源前端入口和模拟器，再停止七应用的消费、扫描、Relay、Dispatcher，等待已确认命令可解释。冻结期间禁止新的设备 Session 和代理业务写入。
3. 在源写入者全部停止后执行同一事务五库快照及 Kafka 位点记录；备份与原 ring/密码/指纹/边缘 CA 留本地。恢复演练使用另一个全新 MySQL 目标，不覆盖当前库。
4. `cloud_native_environment.py prepare --mode import --source-bundle <原完整bundle>` 只准备候选身份与证书，不初始化另一套权威基础设施。此模式主动拒绝 fresh 集群/基础设施初始化；不得改 manifest 为 fresh 绕过门禁。
5. 接管现有基础设施需要明确绑定其容器/卷和可达的 Kafka listener、Broker JWKS 回调与 TLS；对现有卷保留原 owner/身份，不能冒充独立验证卷。当前自动化环境入口只支持全新隔离目标，已有基础设施切换采用单独审查的运行清单；未满足该条件时保留 Compose，不宣称已自动迁移。
6. 候选被动运行核对历史 ID、账号/Session、设备 Session epoch、游标、预约、工单回执、Agent确认命令和 offset；运行同版本迁移兼容检查，不重新播种账号或替换签名 ring。
7. 明确切换后只激活一套权威写入者，再开放设备上游与平台入口，定向复验原四设备、五角色、人工与 Agent 维修闭环。源应用保持停止，源卷保留。
8. 激活前可保留原数据返回源兼容应用；激活后先冻结并对账当前 SQL/Kafka/未决操作，再决定兼容应用回切或继续修复。禁止用旧快照覆盖新写入，也不重设 Kafka offset。

连接撤销存在未知结果时保持设备重新签发阻塞。因为 Broker 的按 clientId 删除没有条件版本，HTTP超时/进程崩溃后不能只凭数据库租约过期重发。需要在冻结设备接入的窗口隔离旧 Broker 进程并记录原/新容器身份，证明旧请求不能继续执行后，由人工核对清理对应未知撤销；不能在线直接删除 PENDING 或 outcome_unknown 记录。
