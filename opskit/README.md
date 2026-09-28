# OpsKit（运营套件）

通用运营基础能力 SaaS：把「事情找人、按流程办完、超时有人管、全程可追溯」做成可嵌入、可转售的产品。
首批能力：**平台**（账户 · 空间 · 身份联邦 · 成员目录 · 事件投递）· **流程**（状态 · 迁移 · 审批 / 外部确认 / 定时门 · SLA）· **工单**（租户自定义类型 · 字段 · 派单策略）。

- 设计准绳：[`docs/technical/v4/16-工作流与工单/21-设计方案-重梳/`](../docs/technical/v4/16-工作流与工单/21-设计方案-重梳/README.md)
- 执行入口：[`21/07-任务清单.md`](../docs/technical/v4/16-工作流与工单/21-设计方案-重梳/07-任务清单.md)
- 孵化期住在 powerbank 仓库根目录，**不进 `sharehub-parent`、不依赖任何业务构件**（父 POM 直接是 `spring-boot-starter-parent`，干净机器只靠 Maven Central 就能构建）；成熟后按 `git subtree split` 拆为独立仓库，坐标与包名不变。

## 模块

| 目录 | 构件 | 内容 |
|---|---|---|
| `commons/` | `opskit-commons` | 共享库（只依赖 JDK）：租户键 · 主体引用 · 条件模型 · 编号 · 方言 · 定时 ID · 错误；test-jar 带四库测试基座 |
| `platform/` | `platform-{api,client,core,store}` | 平台层；表前缀 `plat_` |
| `process/` | `process-{api,client,core,store}` | 流程能力；表前缀 `proc_` |
| `ticket/` | `ticket-{api,client,core,store}` | 工单能力；表前缀 `tkt_` |
| `connector/` | `opskit-connector` | 宿主接入骨架（纯 JDK HttpClient + Jackson 3）：发起 / 代成员 / 幂等 · 外部确认回复 · 对账 · webhook 接收（验签 · 去重）· 接收端 TCK；指引 `docs/integration/` |
| `opskit-svc/` | `opskit-svc` | 启动模块 |
| `opskit-arch/` | —— | 架构测试（ArchUnit A1–A12 + 源码扫描 A7 / A9），每条规则带负对照；不发布 |

依赖方向：`ticket → process → platform → commons`，上层只经下层的 `*-api` / `*-client`。

## 本地验证

```bash
scripts/verify.sh                           # 全量：构建 + 四库测试 + 架构测试
scripts/verify.sh -pl commons               # 透传 mvn 参数
OPSKIT_TEST_DATABASES=h2 scripts/verify.sh  # 只跑 H2 快速档（不作为通过依据）
```

脚本自动处理两件本机上容易卡住的事：选 JDK 21（根 POM 的 enforcer 锁 [21,22)）；
从当前 docker context 推出 `DOCKER_HOST`（Testcontainers 不读 docker context，colima / OrbStack 下否则找不到 Docker）。
四库 = MariaDB 11.4（生产同系）· MySQL 8.4 · PostgreSQL 16 · H2，需要本机有容器运行时（如 `colima start`）。

## CI

`.github/workflows/opskit.yml`：改动 `opskit/**` 或 `deploy/opskit/**` 时跑 `scripts/verify.sh`（构建 + 四库测试 + 架构测试 + OpenAPI / 错误键比对）并构建运行镜像。

## 已知的坑（都已由用例钉住）

| 坑 | 表现 | 处理 |
|---|---|---|
| db-scheduler 16.12.0 的 starter 按 Boot 3 的类名声明「排在数据源之后」 | Boot 4 下调度器 Bean **静默不创建** | `PlatformCoreAutoConfiguration` 夹在数据源与它之间，靠拓扑排序传递；`OpsKitApplicationTest` 守 |
| db-scheduler 默认按 JVM 时区写 MySQL 的 `DATETIME` | 换时区部署，执行时刻错位 | `db-scheduler.always-persist-timestamp-in-utc=true`；`DbSchedulerTimerSchedulerTest` 正反两条守 |
| 调度器启动即轮询执行表 | 表未建好时刷错误日志 | 平台迁移做成 Bean 先执行 + `delay-startup-until-context-ready=true` |
| H2 的 `MERGE … USING (SELECT ? AS c)` 推断不出参数类型 | `Unknown data type` | H2 固定 `MODE=MySQL`，upsert 复用 MySQL 写法 |
| Testcontainers 2.x 的类挪了包 | 旧写法编译通过但已弃用 | 用 `org.testcontainers.{mysql,mariadb,postgresql}.*Container` |
