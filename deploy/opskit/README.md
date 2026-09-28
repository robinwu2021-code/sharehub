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
| `OPSKIT_DB_POOL` | | 连接池上限，默认 20 |

## 核对

- 启动日志有 `启动引导完成 … ref=MAIN idps=2 roles=[…]`。
- `curl -H "Authorization: Bearer <运营台员工令牌>" http://127.0.0.1:8090/api/opskit/v1/workspaces/ext:MAIN/me` 返回角色与产品权限。
- `/dev/**` 只在 local 配置档存在，prod 不暴露。
