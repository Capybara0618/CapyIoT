# Kubernetes 验证环境工具

七应用与前端清单、隔离基础设施、迁移 Job、发布/回退和场景实测工具已完成编码，统一验证状态须以实际记录为准。现有开发部署继续使用根目录 `compose.microservices.yml`；历史 Helm 路径保持退役。

使用 Agent 锁定的 Python 3.12 环境运行脚本。所有目标参数必须显式提供，`--execute` 缺省时仅输出计划，不读取源凭据或调用 Docker/Kubernetes。例如：

```powershell
agent-service/.venv/Scripts/python.exe scripts/cloud_native_environment.py plan --bundle var/cloud-native/verify --cluster capyiot-cn-verify --context kind-capyiot-cn-verify --namespace capyiot-cn-verify --infra-project capyiot-cn-infra-verify
```

`prepare --execute` 只创建新私有 bundle，不启动环境。默认 fresh 模式生成独立账号、签名身份和 CA；真实模型模式从本地环境读取模型配置，不在命令行传递 Key。`--mode import --source-bundle <本地完整微服务bundle>` 保留源凭据、签名 ring 和 CA，准备候选内部证书；导入模式不能调用 fresh 集群/基础设施初始化，必须等待受控接管流程。源 bundle 和目标 bundle 不得相同，不会覆盖已有目标。

后续统一准备阶段需先获取并校验 Kind 0.33.0、专用 kubectl 1.37.0 和 `kind/verify.yaml` 固定的节点镜像。工具路径默认位于忽略的 `var/cloud-native/tools/`，可通过 `--kind-binary`、`--kubectl-binary` 指定；不覆盖系统工具。当前集群客户端版本、镜像及资源条件不满足时拒绝创建。

Windows客户端下载不可用时，prepare 可显式选择 `--kubectl-mode node-image`，使用同一固定digest节点镜像内的kubectl1.37.0；版本检查无需挂载文件，实际控制只读挂载单个kubeconfig，绝不挂载整个bundle或CA私钥。临时客户端通过Docker Linux主机网络访问本机Kind API，限制128MiB，退出即删除；不创建第二业务服务或更改客户端版本要求。

只有对应阶段明确安排运行后，才依次执行 `preflight --execute`、`create-cluster --execute`、`start-infra --execute`，带齐同一组目标参数。每次操作使用 bundle 内私有 kubeconfig，核对 Docker daemon、Kind 容器、集群 UID、bridge ID、基础容器 ID 与数据卷。默认预算要求 Linux Docker 总内存至少 12 GiB，创建前至少 10 GiB 余量；Windows 宿主还须有至少 2 GiB 空闲。不会自动停止其他环境来腾资源。

本机受限资源验证可在 prepare 时显式选择 `--resource-profile compact`：七应用上限各384MiB、前端96MiB，MySQL512MiB/Kafka640MiB/EMQX384MiB；Docker至少7.3GiB，创建前至少6.2GiB余量。此配置降低组件配额和吞吐空间，不代表生产容量。两种配置均禁止在资源不足时自动抢占其他环境。

集群创建后为发布工具签发独立短时客户端证书，绑定目标namespace内的发布权限及必要的指定集群身份只读权限。日常工具使用受限kubeconfig，Kind初始管理凭据仅保存在私有bootstrap-kubeconfig，不挂载应用，不自动回退使用管理员权限。应用不挂载ServiceAccount API Token。

MySQL、Kafka、EMQX 保持集群外独立运行，使用新项目、新卷和受控 bridge。Kafka 专用 listener 返回集群可解析地址；EMQX 经专用 NodePort 读取设备 JWKS，仍验证 TLS。外部端点对象只在容器身份与健康核对后生成，后续发布层必须通过 `verified_endpoints()` 再次核对，不能直接应用过期地址文件。NodePort/Service 与前端应用资源由后续应用清单提供。

`infrastructure-state.json` 区分 CREATING、CLUSTER_BOUND、INFRA_STARTING、INFRA_READY；失败保留状态、卷和容器，先核对再恢复，不自动删除或重建，不激活任何应用消费、扫描或 Dispatcher。这里的 INFRA_READY 只表示基础设施启动，不表示业务或云原生验收完成。

Docker 重启会重建 Kind 节点的网络命名空间。再次运行前执行 `reconcile-network --execute`（带齐相同目标参数），核对 daemon、节点、bridge 和集群 UID 后恢复唯一的 Pod 网段到外部 bridge 的节点内 SNAT 规则；已有规则不重复添加，不改宿主路由。`start-infra` 也执行这项检查。不得以重建 bundle、轮换身份或删除数据卷修复网络。

私有 bundle、SQL、证书、真实 Secret、kubeconfig、机器地址及原始报告均保存在忽略的 `var/`，不得加入 Git。所有变更测试留到本轮编码完成后的统一验证阶段。

基础设施就绪后用 `verify_cloud_native.py --groups ...` 统一执行所选检查，显式提供同一目标、私有报告目录和已构建的 `cn-<commit>` 镜像标签。支持 unit/build/manifests/deploy/protocol/workflow/agent/browser/resilience/metrics/recovery/upgrade/release-rehearsal，真实模型单列 real-model，另给本地 Key 文件、模型ID；不默认调用真实模型。metrics 必须提供首次运行前冻结的本地 policy 文件；resilience 必须提供另一个实际构建的镜像标签作为滚动升级版本。

`resilience` 分别验证本库/平台/模型故障、失败迁移阻断、消费者与接入暂停的唯一事件对账、七应用替换、双副本、进程强制退出与真实镜像滚动。镜像发布窗口保留每次请求结果，扩缩容保存观察到的实例名；第一次失败与定向复验用不同目录，不能把首次失败报告改绿。资源通过已核验的目标 Kind 节点读取 CRI，筛选目标 namespace；发布身份没有 nodes/proxy 权限。

`workflow`、`agent`、`real-model` 使用同一组四台显式模拟资产。每个场景开始前要求该 bundle 的发布者已停止，再通过公开 API 清理这些模拟设备的遗留活动异常，保留审计和 fixture-cleanup 记录。这仅是隔离测试准备，不是设备恢复或维修证据。关联快照保留历史事实；失败场景不可直接当作新的干净故障复用。真实模型最多三次 Run，每个 Run 的模型请求/工具调用预算分别为3/5；离线九工具回归另用10/10预算。

`release-rehearsal` 要求新的 release-id 和完整镜像标签，实际执行迁移、被动启动、激活、兼容验证和应用回退，核对工单 ID 保留。它会短暂关闭单实例应用，不应在用户体验环境执行。失败保留原相位，不盲目回退。

`recovery` 冻结本 bundle 的模拟器及八个应用，保存五库快照/逐表校验/未决操作和 Kafka 位点，在另一个全新 MySQL 项目恢复并核对；结束后应用副本仍为0。`upgrade --upgrade-backup <原微服务schema15备份目录>` 须在冻结后执行，在另一份恢复副本迁移到16，核对历史行、旧 migration checksum 和断连表旧列；不启动任何克隆应用。备份和旧副本均保留，不能对当前体验库执行恢复。

`cloud_native_release.py` 的动作依次为 prepare、migrate、passive、activate；迁移独立运行且失败不自动重试。中断后 reconcile 只依据匹配的 Job/Deployment 证明推进状态；不能将未知操作强行改为成功。rollback 只恢复已经记录且 Schema 兼容的应用版本，不倒退数据库内容或 Kafka 位点。[已有环境接管边界](ADOPTION.md) 与全新隔离部署分别处理。

前端默认使用完整 Dockerfile 构建。若统一检查已经完成本机 `npm run build`，可用 `cloud_native_frontend_artifact.attest()` 将源码、dist 和构建日志摘要记录到本地证明，再给构建入口传 `--frontend-artifact <证明文件>`。它仅复制已核验的跨平台静态输出及 Nginx 配置，以 `Dockerfile.runtime` 打包，不复制宿主 node_modules。产物或记录变化会拒绝复用；原始构建失败与后续替代构建分别保留。
