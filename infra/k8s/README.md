# Kubernetes 验证环境工具

当前仅完成隔离基础设施和环境工具编码，尚未统一测试。七应用清单、迁移与发布控制仍需后续交付，不能用本目录声明 Kubernetes 部署已完成。现有开发与体验部署继续使用根目录 `compose.microservices.yml`；历史 Helm 路径保持退役。

使用 Agent 锁定的 Python 3.12 环境运行脚本。所有目标参数必须显式提供，`--execute` 缺省时仅输出计划，不读取源凭据或调用 Docker/Kubernetes。例如：

```powershell
agent-service/.venv/Scripts/python.exe scripts/cloud_native_environment.py plan --bundle var/cloud-native/verify --cluster capyiot-cn-verify --context kind-capyiot-cn-verify --namespace capyiot-cn-verify --infra-project capyiot-cn-infra-verify
```

`prepare --execute` 只创建新私有 bundle，不启动环境。默认 fresh 模式生成独立账号、签名身份和 CA；真实模型模式从本地环境读取模型配置，不在命令行传递 Key。`--mode import --source-bundle <本地完整微服务bundle>` 保留源凭据、签名 ring 和 CA，准备候选内部证书；导入模式不能调用 fresh 集群/基础设施初始化，必须等待受控接管流程。源 bundle 和目标 bundle 不得相同，不会覆盖已有目标。

后续统一准备阶段需先获取并校验 Kind 0.33.0、专用 kubectl 1.37.0 和 `kind/verify.yaml` 固定的节点镜像。工具路径默认位于忽略的 `var/cloud-native/tools/`，可通过 `--kind-binary`、`--kubectl-binary` 指定；不覆盖系统工具。当前集群客户端版本、镜像及资源条件不满足时拒绝创建。

只有对应阶段明确安排运行后，才依次执行 `preflight --execute`、`create-cluster --execute`、`start-infra --execute`，带齐同一组目标参数。每次操作使用 bundle 内私有 kubeconfig，核对 Docker daemon、Kind 容器、集群 UID、bridge ID、基础容器 ID 与数据卷。默认预算要求 Linux Docker 总内存至少 12 GiB，创建前至少 10 GiB 余量；Windows 宿主还须有至少 2 GiB 空闲。不会自动停止其他环境来腾资源。

MySQL、Kafka、EMQX 保持集群外独立运行，使用新项目、新卷和受控 bridge。Kafka 专用 listener 返回集群可解析地址；EMQX 经专用 NodePort 读取设备 JWKS，仍验证 TLS。外部端点对象只在容器身份与健康核对后生成，后续发布层必须通过 `verified_endpoints()` 再次核对，不能直接应用过期地址文件。NodePort/Service 与前端应用资源由后续应用清单提供。

`infrastructure-state.json` 区分 CREATING、CLUSTER_BOUND、INFRA_STARTING、INFRA_READY；失败保留状态、卷和容器，先核对再恢复，不自动删除或重建，不激活任何应用消费、扫描或 Dispatcher。这里的 INFRA_READY 只表示基础设施启动，不表示业务或云原生验收完成。

私有 bundle、SQL、证书、真实 Secret、kubeconfig、机器地址及原始报告均保存在忽略的 `var/`，不得加入 Git。所有变更测试留到本轮编码完成后的统一验证阶段。
