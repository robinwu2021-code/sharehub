# OpsKit 生产部署（ShareHub 同机）

| 项 | 值 |
|---|---|
| 启动 | `java -jar opskit-svc.jar --spring.profiles.active=prod`（JDK 21） |
| 数据库 | 现有 MariaDB 上新建独立库 `opskit`（utf8mb4）+ 只授权该库的独立用户；Flyway 启动时自动建表（平台 / 流程 / 工单各自的历史表），不碰 ShareHub 的库 |
| 引导配置 | `bootstrap.yml.example` → `/etc/opskit/bootstrap.yml`（或 `OPSKIT_BOOTSTRAP_FILE`）|
| 反代 | nginx `/api/opskit/` → `127.0.0.1:8090`，保留原路径（OpsKit 同时认 `/api/opskit/v1` 与 `/v1`）|

## 环境变量

| 变量 | 必需 | 说明 |
|---|:-:|---|
| `OPSKIT_CRYPTO_KEY` | ✅ | 32 字节密钥的 Base64（`openssl rand -base64 32`）。缺了拒绝启动；**生成后不可更换**（已加密的 IdP 密钥会读不出）|
| `OPSKIT_DB_URL` | ✅ | `jdbc:mariadb://127.0.0.1:3306/opskit` |
| `OPSKIT_DB_USER` / `OPSKIT_DB_PASSWORD` | ✅ | 独立用户 |
| `OPSKIT_INTROSPECT_URL` | ✅ | `http://127.0.0.1:8082/internal/opskit/introspect` |
| `OPSKIT_INTROSPECT_CLIENT_ID` / `_SECRET` | ✅ | 与 ShareHub 的 `SHAREHUB_OPSKIT_INTROSPECT_CLIENT_ID` / `_SECRET` 同值 |
| `OPSKIT_PORT` | | 默认 8090 |
| `OPSKIT_BOOTSTRAP_FILE` | | 默认 `/etc/opskit/bootstrap.yml` |
| `OPSKIT_APP_SHAREHUB_CREDENTIAL` / `_SIGNING_SECRET` | M5 | 宿主应用凭证 `okk_…` 与 webhook 签名密钥 `oks_…`（生成命令见 `bootstrap.yml.example`）；与 ShareHub 侧同值。日志只记动作（CREATED / ROTATED / UNCHANGED），不记值 |
| `OPSKIT_BOOTSTRAP_REQUIRED` | 建议 | 设 `true`：引导文件缺失、是目录、或未 `enabled: true` 时拒绝启动（默认 false 只记 WARN —— 服务会健康却无人能登录）|
| `OPSKIT_DB_POOL` | | 连接池上限，默认 20 |
| `OPSKIT_OUTBOX_RETENTION` | | 已送达 webhook 事件的保留期，默认 `30d`（每日清理；失败待人工的不清）|
| `OPSKIT_LOG_DIR` | | 设了才落盘（`{dir}/spring.log`，按天滚动、保留 20 天、单文件 200MB）；不设只进 journal |

## 预置定义（流程 · SLA · 工单类型）

`bootstrap.yml` 的 `definitions-dir` 指向一个目录，启动时按下列顺序发布其中的 `*.json`（工单类型要引用前面几种）：

```
/etc/opskit/definitions/
  option-sets/    选项集（可选）
  processes/      流程，如 repair_flow.json
  sla-policies/   SLA 策略，如 repair_sla.json
  ticket-types/   工单类型，如 repair.json
```

- **文件名须与定义里的 `code` 一致**；按代号幂等：内容不变 → 沿用原版本（日志 `unchanged=[…]`），变了 → 出新版本（`published=[…]`），
  已在途的单子继续跑旧版本。
- 任一文件形状不对或校验不过都**拒绝启动**，日志里给出文件名与逐字段错误键（文案见 `opskit/docs/error-keys.json`）。
- JSON 形状 = 定义的规范化存储形状 = `PUT /workspaces/{ws}/processes/{code}`、`PUT …/ticket-types/{code}`、`PUT …/option-sets/{code}`
  的请求体（多一个可选 `note`）。**SLA 例外**：它的 HTTP 接口 `PUT …/sla-policies/{code}` 用的是分钟制 DTO（`targetsMin`），
  定义文件里用 ISO-8601 时长（`PT4H`）。
- 起点：复制 `definitions.example/`（设备报修：新建 → 处理中 → 已解决 / 已作废，解决与作废须写说明；优先级 紧急 / 高 / 普通；
  字段 来源（SELECT）· 来源单号（TEXT）；SLA 首响 P1 1h / 其余 4h，解决 P1 4h / 其余 24h）。
- `processes/withdrawal_audit.json`：M5 提现试点。单级审批，候选人 = 令牌带宿主权限码 `finance:withdrawal:audit` 的人；禁止自审（提交人经 `onBehalfOf` 成为 REQUESTER）；驳回必填原因（OpsKit 对所有驳回都强制）。发起即进审批（无草稿态），通过 / 驳回 / 撤回分别落 `approved` / `rejected` / `withdrawn`。
- 派单：示例用 `MANUAL`（不自动派，持 `ticket:assign` 的人在工单页指派，含指派给自己）。没有组就没有「抢单池」——
  `QUEUE` / `ROUND_ROBIN` / `LEAST_LOADED` 都要一个组；有了队列组后把 `assignment` 改成 `{"strategy": "QUEUE", "group": "<组代号>"}` 重启即可。

## 核对

- 启动日志有 `启动引导完成 … ref=MAIN idps=2 roles=[…]`。
- `curl -H "Authorization: Bearer <运营台员工令牌>" http://127.0.0.1:8090/api/opskit/v1/workspaces/ext:MAIN/me` 返回角色与产品权限。
- `/dev/**` 只在 local 配置档存在，prod 不暴露。
- 健康：`/actuator/health` 含 `scheduler`（db-scheduler 在跑）与 `outbox`（投递积压，已到期未投出 > 1000 条时为 UNKNOWN）；指标：`/actuator/prometheus` 的 `opskit_outbox_pending / due / failed`。
- 日志每行带 `[traceId] [ws=…] [actor=…]`，排障按 traceId 串起一次请求。
