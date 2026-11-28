# Kubernetes 验证环境工具

七应用与前端清单、隔离基础设施、迁移 Job、发布/回退和场景实测工具已完成编码，统一验证状态须以实际记录为准。现有开发部署继续使用根目录 `compose.microservices.yml`；历史 Helm 路径保持退役。

使用 Agent 锁定的 Python 3.12 环境运行脚本。所有目标参数必须显式提供，`--execute` 缺省时仅输出计划，不读取源凭据或调用 Docker/Kubernetes。例如：

```powershell
agent-service/.venv/Scripts/python.exe scripts/cloud_native_environment.py plan --bundle var/cloud-native/verify --cluster capyiot-cn-verify --context kind-capyiot-cn-verify --namespace capyiot-cn-verify --infra-project capyiot-cn-infra-verify
```

`prepare --execute` 只创建新私有 bundle，不启动环境。默认 fresh 模式生成独立账号、签名身份和 CA；真实模型模式从本地环境读取模型配置，不在命令行传递 Key。`--mode import --source-bundle <本地完整微服务bundle>` 保留源凭据、签名 ring 和 CA，准备候选内部证书；导入模式不能调用 fresh 集群/基础设施初始化，必须等待受控接管流程。源 bundle 和目标 bundle 不得相同，不会覆盖已有目标。

后续统一准备阶段需先获取并校验 Kind 0.33.0、专用 kubectl 1.37.0 和 `kind/verify.yaml` 固定的节点镜像。工具路径默认位于忽略的 `var/cloud-native/tools/`，可通过 `--kind-binary`、`--kubectl-binary` 指定；不覆盖系统工具。当前集群客户端版本、镜像及资源条件不满足时拒绝创建。

只有对应阶段明确安排运行后，才依次执行 `preflight --execute`、`create-cluster --execute`、`start-infra --execute`，带齐同一组目标参数。每次操作使用 bundle 内私有 kubeconfig，核对 Docker daemon、Kind 容器、集群 UID、bridge ID、基础容器 ID 与数据卷。默认预算要求 Linux Docker 总内存至少 12 GiB，创建前至少 10 GiB 余量；Windows 宿主还须有至少 2 GiB 空闲。不会自动停止其他环境来腾资源。

本机受限资源验证可在 prepare 时显式选择 `--resource-profile compact`：七应用上限各384MiB、前端96MiB，MySQL512MiB/Kafka640MiB/EMQX384MiB；Docker至少7.3GiB，创建前至少6.2GiB余量。此配置降低组件配额和吞吐空间，不代表生产容量。两种配置均禁止在资源不足时自动抢占其他环境。

集群创建后为发布工具签发独立短时客户端证书，绑定目标namespace内的发布权限及必要的指定集群身份只读权限。日常工具使用受限kubeconfig，Kind初始管理凭据仅保存在私有bootstrap-kubeconfig，不挂载应用，不自动回退使用管理员权限。应用不挂载ServiceAccount API Token。

MySQL、Kafka、EMQX 保持集群外独立运行，使用新项目、新卷和受控 bridge。Kafka 专用 listener 返回集群可解析地址；EMQX 经专用 NodePort 读取设备 JWKS，仍验证 TLS。外部端点对象只在容器身份与健康核对后生成，后续发布层必须通过 `verified_endpoints()` 再次核对，不能直接应用过期地址文件。NodePort/Service 与前端应用资源由后续应用清单提供。

`infrastructure-state.json` 区分 CREATING、CLUSTER_BOUND、INFRA_STARTING、INFRA_READY；失败保留状态、卷和容器，先核对再恢复，不自动删除或重建，不激活任何应用消费、扫描或 Dispatcher。这里的 INFRA_READY 只表示基础设施启动，不表示业务或云原生验收完成。

私有 bundle、SQL、证书、真实 Secret、kubeconfig、机器地址及原始报告均保存在忽略的 `var/`，不得加入 Git。所有变更测试留到本轮编码完成后的统一验证阶段。

基础设施就绪后用 `verify_cloud_native.py --groups ...` 统一执行所选检查，显式提供同一目标、私有报告目录和已构建的 `cn-<commit>` 镜像标签。支持 unit/build/manifests/deploy/protocol/workflow/agent/browser/resilience/metrics/recovery，真实模型单列 real-model，另给本地 Key 文件、模型ID；不默认调用真实模型。metrics 必须提供首次运行前冻结的本地 policy 文件；resilience 必须提供另一个实际构建的镜像标签作为滚动升级版本。

`cloud_native_release.py` 的动作依次为 prepare、migrate、passive、activate；迁移独立运行且失败不自动重试。中断后 reconcile 只依据匹配的 Job/Deployment 证明推进状态；不能将未知操作强行改为成功。rollback 只恢复已经记录且 Schema 兼容的应用版本，不倒退数据库内容或 Kafka 位点。[已有环境接管边界](ADOPTION.md) 与全新隔离部署分别处理。
