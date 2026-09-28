# OpsKit 部署（容器）

单宿主 / 私有化部署样板：MariaDB + OpsKit。宿主已有数据库时去掉 `db` 服务、把 `OPSKIT_DB_URL` 指过去（库与用户事先建好，Flyway 只建表）。

```bash
scripts/build-image.sh                          # 打包（JDK 21）+ 构建镜像 opskit:<版本> / opskit:latest
cd deploy
cp .env.example .env                            # 填 OPSKIT_DB_PASSWORD、OPSKIT_CRYPTO_KEY（openssl rand -base64 32）
cp bootstrap.example.yml bootstrap.yml          # 租户号 · 时区 · 身份提供方 · 角色 → 权限
docker compose up -d && docker compose logs -f opskit
```

起来后看日志里的 `启动引导完成 … definitions=published=[…]`；再次启动应为 `unchanged=[…]`。

| 项 | 说明 |
|---|---|
| 镜像 | `eclipse-temurin:21-jre` + 已构建的 jar（与 `scripts/verify.sh` 验过的是同一个产物）；非 root；堆 = 容器内存上限 × 75%，`JAVA_OPTS` 可追加 |
| 端口 | 8090；compose 默认只绑 127.0.0.1，前面放反代（nginx `/api/opskit/` → 8090，保留原路径）|
| 健康 | `/actuator/health`（含调度器与投递积压）；指标 `/actuator/prometheus` |
| 日志 | 控制台 + `/data/log/opskit/spring.log`（卷 `logs`，按天滚动保留 20 天）；每行带 traceId / workspace / actor |
| 时区 | 容器 `TZ=UTC`；业务「今天」「工作日」按空间时区（`bootstrap.yml` 的 `workspace.zone`）算，与容器时区无关 |
| 预置定义 | `definitions/` 挂到 `/etc/opskit/definitions`，按代号幂等发布；格式见 [`deploy/opskit/README.md`](../../deploy/opskit/README.md#预置定义流程--sla--工单类型) |

## 环境变量

| 变量 | 必需 | 说明 |
|---|:-:|---|
| `OPSKIT_CRYPTO_KEY` | ✅ | 32 字节 Base64。**生成后不可更换**（已加密的 IdP 密钥会读不出）|
| `OPSKIT_DB_URL` / `_USER` / `_PASSWORD` | ✅ | compose 里默认指向 `db` 服务 |
| `OPSKIT_BOOTSTRAP_FILE` | | 默认 `/etc/opskit/bootstrap.yml` |
| `OPSKIT_BOOTSTRAP_REQUIRED` | | compose 里为 `true`：引导文件缺失、**被挂成了目录**、或没写 `enabled: true` 都拒绝启动 |
| `OPSKIT_DB_POOL` | | 连接池上限，默认 20 |
| `OPSKIT_LOG_DIR` | | 设了才落盘 |

## 坑

- **挂载源不存在时，Docker 会在容器里建一个同名空目录**。`spring.config.import` 遇到目录静默跳过 —— 服务照样 healthy，但账户 / 身份提供方 / 角色一个都没有，谁也登录不了。
  `BootstrapFileGuard` 把「路径是目录」一律变成启动失败。colima / Docker Desktop 只共享部分宿主目录（colima 默认只有 `$HOME`），放在 `/tmp` 下的文件挂不进去，就是这个表现。
- 首次启动建空间后，`workspace.zone` / `locale` 再改不生效。
