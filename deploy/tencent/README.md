# powerbank · 腾讯云部署方案

> **状态：方案稿（未落地）**。目标：把 powerbank 后端 + 运营端 + C 端（c-app H5）
> 部署到 ai-shop 已在用的那台腾讯云 Lighthouse，与 ai-shop 共存互不干扰。
>
> 参照 [`ai-shop/deploy/tencent/README.md`](../../../ai-shop/deploy/tencent/README.md) 的实践，逐条搬。

---

## 1. 目标机（复用 ai-shop 的机器）

| 项 | 值 |
|---|---|
| SSH 别名 | `soukmind-tx`（deploy 用户，免密 sudo）· `soukmind-tx-root`（救火） |
| 主机 | 腾讯云轻量应用服务器 · `106.55.27.246`（Lighthouse `lhins-98lm5asj`） |
| 系统 | Ubuntu 24.04.4 LTS · 内存 7.5 GB · 磁盘 59 GB 单盘（2026-09-14 已用 16G） |
| 已装 | nginx 1.24 · JDK 21 · Node 20.20 · MySQL 9.7 LTS（`127.0.0.1:3307`）· systemd · acme.sh 泛域证书 `*.ichain.top` |
| 域名 | powerbank 用 **`powerbank.ichain.top`**（`*.ichain.top` 泛域证书直接吃；`ichain.top` 已备案） |

## 2. 拓扑

```
现有 nginx(443/80)
  ── 现有 server: www.hxmall.top      → ai-shop（不动）
  ── 新增 server: powerbank.ichain.top ─┬─ /            → /data/app/powerbank/web/ops-web  (Next.js export)
                                       ├─ /c/          → /data/app/powerbank/web/c-app    (uni-app H5)
                                       └─ /mp /biz /ops /actuator → 127.0.0.1:8082  sharehub-app.jar (systemd)
MySQL 9.7 复用 127.0.0.1:3307（新建库 pb_core，独立账号）
```

| 组件 | 版本 | 运行方式 | 端口 |
|---|---|---|---|
| 后端 `sharehub-app` | Spring Boot / Java 21 | `systemd: powerbank` | **8082**（ai-shop 8081/8083 已占，避让） |
| ops-web | Next.js export 静态 | nginx 静态 | — |
| c-app | uni-app H5 静态 | nginx 静态 | — |
| MySQL | 9.7 LTS（复用） | ai-shop 已装 | 3307 |
| nginx | 1.24（复用） | ai-shop 已装 | 443/80 |

**后端仅上 `sharehub-app` 一个 jar**（对标 ai-shop 的 shop-app）。`sharehub-app-gateway` 是服务网关，
只有等真要拆分服务时才有必要独立部署，首期不上。

## 3. 落位（新建，全部在 `/data/` 下）

按 ai-shop 的「类型 / 项目 / 服务」三级：

| 路径 | 内容 |
|---|---|
| `/data/app/powerbank/sharehub-app/` | `sharehub-app-<时间>-<SHA>.jar` 若干版 + `sharehub-app.jar` 软链 · `sharehub-app.env`（600，含 DB 密码等真凭据）· `deploy.log` |
| `/data/app/powerbank/web/{ops-web,c-app}` | 前端静态产物 |
| `/data/log/powerbank/sharehub-app/` | 应用日志（logback 自滚，控制台只 ERROR 进 journal） |
| `/data/backup/powerbank/db/` | 每日 mysqldump 短留 3 天，同步入 `hxmall-backup-1301656997/db/powerbank/`（复用现有 COS 桶） |
| `/etc/systemd/system/powerbank.service` | 独立 systemd 单元（**不与 ai-shop 复用**） |
| `/etc/nginx/sites-available/powerbank.ichain.top` | 独立站点（源文件在本目录 `nginx/`） |
| `/etc/logrotate.d/powerbank` · `/etc/cron.d/powerbank-backup` | 兜底 |

MySQL 库与账号：
- 库 `pb_core`（Flyway `V1..V10` 已定义 131 张表，`baseline-on-migrate=true`）
- 账号密码 32 位随机生成，保存在**本机** `~/work/env/tencent/powerbank.env`（仓库外）

## 4. 部署流程（都在本机构建、再传上去）

**照抄 ai-shop 两把武器**（在本仓库 `scripts/` 下）：

```bash
scripts/deploy-backend.sh                # 后端 sharehub-app
scripts/deploy-frontend.sh ops-web       # 运营端
scripts/deploy-frontend.sh c-app         # C 端 H5
```

要点（与 ai-shop 完全同套规矩）：

1. **本机 `mvn package` / `npm build`**，产物打时间戳 + SHA 传上去，切软链，systemctl restart
2. **`git worktree` 拉干净 HEAD 构建** —— 主工作区常有多会话在改，共享工作区会带进别人未提交的东西
3. **锁（每服务一把）** —— 阻断并发部署互相覆盖
4. **健康守候**（不许提前返回）—— 后端等 `/actuator/health=200`；前端 curl `/ops-web/` 与 `/c/` 拿到 title
5. **可回滚** —— 备份上一版 jar 与 `web/<app>.bak-<stamp>/`，`--rollback` 切软链回上一版
6. **JDK 21 强检查** —— 父 POM enforcer 卡死；找不到就 die，不猜

## 5. 三个**必须由部署侧覆盖**的配置

| 配置 | 仓库默认 | 生产必须 | 原因 |
|---|---|---|---|
| `NEXT_PUBLIC_USE_MOCK` | mock（`!== "0"`） | **`0`** | 漏配 → 静默跑 mock（ai-shop 2026-09-01 踩过，admin 登录看似无权限，其实请求根本没到后端） |
| `NEXT_PUBLIC_API_BASE`（ops-web） | — | 留空（同源） | 走 nginx 反代 `/ops/**`，后端不配 CORS |
| c-app 的 `VITE_API_BASE` | `.env` 里的默认 | **`.env.production` 留空**（同源） | Vite `VITE_*` 只从 `.env` 文件读，shell 环境变量覆盖不了；不覆盖就把本地地址烧进生产包 |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3306/pb_core` | `jdbc:mysql://127.0.0.1:3307/pb_core?...`（**端口 3307**） | 服务器 MySQL 9.7 在 3307，不是 3306 |
| `SERVER_PORT` | 8080 | **8082** | 避让 ai-shop 的 8081（shop-app）与 8083（pay-svc） |
| `PB_DB_USER` / `PB_DB_PASS` | dev 默认 | 生产随机 32 位 | 存 `sharehub-app.env`（600） |
| `SPRING_FLYWAY_PLACEHOLDER_REPLACEMENT` | 默认 true | **视 SQL 内容** | ai-shop 踩过 Flyway 把注释里的 `${}` 也当占位符解析 —— 上线前对 `backend/sharehub-app/src/main/resources/db/migration/*.sql` 扫一遍是否有裸 `${}` |
| `SHAREHUB_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | **`https://<生产域名>,http://localhost:3000`** | 即使同源，浏览器 fetch 的 POST 仍会带 `Origin` 头 → Spring CorsFilter 若在白名单外会返 403「Invalid CORS request」→ 前端映射为「无权限」；curl 默认不发 Origin 头，所以 shell 测试通过而浏览器登录失败 |

**发完必须验的两句**（同 ai-shop）：

```bash
# 1) 生产 env 里不许有 IP 字面量
ssh soukmind-tx 'sudo grep -hoE "(jdbc:mysql://|http://)[0-9]{1,3}(\.[0-9]{1,3}){3}" \
  /data/app/powerbank/sharehub-app/sharehub-app.env'
# 期望：无输出。有输出就是漏用内部名称（未来换 IP 会炸）

# 2) 运营端连的是真后端还是 mock
ssh soukmind-tx 'curl -sk -H "Host: powerbank.ichain.top" https://localhost/ | \
  grep -o "x-api-mode\" content=\"[a-z]*"'
# 期望 http；若是 mock，就是构建漏了 NEXT_PUBLIC_USE_MOCK=0 —— 重新构建，别只重发
```

第 2 条依赖 ops-web 的 `app/layout.tsx` 输出 `x-api-mode` meta；若当前未输出，部署脚本里补上一个类似机制。

## 6. 凭据

| 文件 | 内容 |
|---|---|
| `~/work/env/tencent/powerbank.env` | powerbank 数据库账号密码（**新建，本机保管**） |
| `~/work/env/server/tencent/soukmind_tx(.pub)` | SSH 部署密钥（**复用 ai-shop 的那把**） |
| `/data/app/powerbank/sharehub-app/sharehub-app.env`（服务器 600） | 从本机 env 原样搬运的真凭据 |

服务器上 `powerbank.env` 至少包含：`PB_DB_USER` · `PB_DB_PASS` · `SERVER_PORT=8082` ·
`SPRING_DATASOURCE_URL=jdbc:mysql://db.svc.internal:3307/pb_core?...` ·
（如启用 SMS/邮件/微信登录 stub 为 false，则补对应真凭据）。

内部地址**一律用名称**（`db.svc.internal` 等），映射在服务器 `/etc/hosts` 里 —— 换 IP 那天只改那一处。

## 7. nginx 站点

新增 `/etc/nginx/sites-available/powerbank.ichain.top`（源文件见本目录 `nginx/`），
两个 server 块：
- `powerbank.ichain.top` · 443（TLS 用 `/etc/nginx/ssl/ichain.top/`）+ 80 跳 443

location 与 ai-shop 完全对齐语义：
- `/` → ops-web 静态
- `/c/` → c-app 静态（构建时 `H5_BASE=/c/`，与 ai-shop 同坑：不给会**整站白屏而 index 200**）
- `/mp /biz /ops /actuator` → 反代 `127.0.0.1:8082`
- `/uploads /media` → 若后端要暴露文件目录，与 ai-shop 同款反代

## 8. 备份

新增 cron：每天 03:30（避开 ai-shop 的 03:20 抢锁）跑
`/data/app/powerbank/ops/backup-to-cos.sh`：mysqldump `pb_core` → gzip → 上传
`hxmall-backup-1301656997/db/powerbank/`，本机 `/data/backup/powerbank/db/` 只留最近 3 天。

**复用 ai-shop 的 COS 桶**（`hxmall-backup-1301656997`），只是加子路径 `db/powerbank/`；
桶生命周期（30 天→低频、90 天→归档、365 天→删）自动生效。

## 9. 证书（无操作）

`*.ichain.top` 已在 `/etc/nginx/ssl/ichain.top/`，acme.sh v3.1.5 自动续签，
下次自动续期 2026-10-17（见 ai-shop README §10）。powerbank 直接复用，**不用签新证书、不用改 acme 配置**。

## 10. 验证与排障

```bash
curl https://powerbank.ichain.top/actuator/health          # {"status":"UP"}
curl https://powerbank.ichain.top/                          # ops-web 首页 HTML
curl https://powerbank.ichain.top/c/                        # c-app H5 首页
ssh soukmind-tx 'tail -f /data/log/powerbank/sharehub-app/sharehub-app.log'
ssh soukmind-tx 'sudo journalctl -u powerbank -p err --since -1h'
ssh soukmind-tx 'systemctl status powerbank'
```

## 11. 待办 / 未决

1. **DB 库账号建立**：进服务器 `mysql -uroot -P3307` 建 `pb_core` 库、`powerbank` 账号，密码存 `~/work/env/tencent/powerbank.env`（**动手前先做，Flyway 首次启动要用**）。
2. **磁盘容量**：现在 16G/59G，加 powerbank 后估算 +5G 以内（jar 300M x 3 版 + 前端 100M + logback 封顶 200M + Flyway 数据初期 <1G）。不宽裕但足够；数据长胖后要考虑挪数据盘。
3. **ops-web 的 `NEXT_PUBLIC_USE_MOCK=0` 已在 `build:prod`？** 部署脚本里要显式传，别信默认。仓库现在 `ops-web/package.json` 是否有 `build:prod`：**动手前确认**，没有就加。
4. **`sharehub-app-gateway` 首期不部**：若后续要拆分部署，加 `scripts/deploy-backend.sh sharehub-app-gateway`（对标 ai-shop 的 `pay-svc` 支路）。
5. **`c-app` App/小程序打包**：与本次 Web 部署无关，H5 是当前唯一目标；App/微信小程序分发另开一份 release 流程。
6. **迁移到 ShareHub 名字**：`ADR-014` 定名但迁移待放行。本方案的路径继续用 `powerbank/`（跟仓库名），改名时**一次性把 `/data/app/powerbank/` 软链到 `/data/app/sharehub/`**（同 ai-shop `/opt/ai-shop*` 兼容软链的做法），不做原地重命名。

## 12. 落地顺序（你放行后我做）

1. 写 nginx site 文件（本目录 `nginx/powerbank.ichain.top`）
2. 写 systemd unit（本目录 `systemd/powerbank.service`）
3. 写 `scripts/deploy-backend.sh` 与 `scripts/deploy-frontend.sh`（照抄 ai-shop 结构，改路径与端口）
4. 服务器上建库、装 systemd/nginx/cron，跑一次 Flyway 迁移干跑
5. 本机构建 → 上传 → 切软链 → 起服务 → 三条 health 全绿
6. 公网复验（不在服务器上 curl）→ 三条 URL 全绿并写回本文档
