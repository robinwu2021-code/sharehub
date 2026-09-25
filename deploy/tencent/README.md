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

> ⚠️ **2026-09-23 起：C 端在生产环境暂时登录不了。这是预期行为，不是故障。**
> 此前 C 端靠固定验证码 `000000` 登录（任何人可登录任意手机号，是个公网上的认证绕过）。
> 该后门已关闭（[TDD-auth-security-hotfix](../../docs/technical/TDD-auth-security-hotfix.md)），
> 但后端**还没有短信通道**（`NotifySendServiceImpl` 是 `TODO(接入层)`，只落日志不投递），
> 所以验证码发不出去。**恢复条件**：接入短信通道（`neargo-notify` / N4）。
> 本机联调可设 `SHAREHUB_DEV_MODE_ENABLED=true`（**生产绝不可设**）。

> ## 发布前先跑这一条
>
> ```bash
> deploy/tencent/preflight.sh          # 全部必查项；退出码非 0 就别发
> deploy/tencent/preflight.sh --skip-ssh   # 只查本地（迁移号），连不上机器时用
> ```
>
> 下面那几条「必查」它都会跑一遍。**留着文字版是为了讲清「为什么」** ——
> 脚本告诉你哪一项没过，这里告诉你没过会怎样。
>
> 为什么值得做成脚本：这些必查项一直都在，问题是散在文档各处、靠人记得。
> 本仓为同一件事改造过一次 —— 前后端漂移检查本来挂在 `npm run check:drift` 下
> （也就是要有人记得去跑），实际没人跑，于是「漂移可被发现」退化成
> 「漂移可被发现，如果你已经知道它在那儿」。搬进测试之后才真的跑到。

> ⚠️ **发布前必查**：`SHAREHUB_ADMIN_PASSWORD` 必须非空，否则运营端**彻底登不进**（fail-closed 的预期行为）：
> ```bash
> ssh soukmind-tx 'sudo grep -c "^SHAREHUB_ADMIN_PASSWORD=.\+" /data/app/powerbank/sharehub-app/sharehub-app.env'
> # 期望 1。为 0 就先补配再发，否则发完没人能登录运营端
> ```

> ⚠️ **发布前必查（2026-09-23 新增）**：`SHAREHUB_IDENTITY_PEPPER` 必须非空且 ≥32 字符，
> 否则**应用根本起不来** —— 它比上面那条更硬：上面是「起得来但没人能登录」，这条是「进程直接退出」。
> ```bash
> ssh soukmind-tx 'sudo grep -cE "^SHAREHUB_IDENTITY_PEPPER=.{32,}" /data/app/powerbank/sharehub-app/sharehub-app.env'
> # 期望 1。为 0 就先补配再发
>
> # 还没有的话先生成一个（生成后存进 env file，**不要进 git**）：
> openssl rand -base64 48
> ```
> 为什么是 fail-closed 而不是给个默认值：手机号的取值空间小到可以穷举，
> 没有 pepper 的哈希等于明文存储；而给默认值等于所有部署共用同一个 pepper，等于没有。
>
> ⚠️ **换 pepper = 全表哈希失效**。轮换必须走 `agt_principal.*_enc`（可逆加密的明文）
> 全表重算后统一切 `hash_ver`，不能直接改 env 重启 —— 那样所有人都登不进去，且查不出原因。

> ⚠️ **发布前必查（2026-09-25 新增）· 文件存储落点**：不配就落 `/tmp`，而那是会被清理的。
> ```bash
> ssh soukmind-tx 'sudo grep -cE "^SHAREHUB_STORAGE_LOCAL_ROOT=/data/" /data/app/powerbank/sharehub-app/sharehub-app.env'
> # 期望 1。为 0 说明还在用默认值 ${java.io.tmpdir}/sharehub-files
> ```
> `LocalFileStorage` 的类注释自己写着「单元 / 集成测试与离线开发」—— 它不是为生产设计的，
> 而 `sharehub.storage.type` 的默认值恰恰是 `local`（`matchIfMissing = true`）。两件事凑起来的后果是：
> 合同扫描件、工单照片、踏勘照片落进 `/tmp`，**重启或系统清理后文件全没了，而 `sys_file` 里记录还在** ——
> 界面上文件列得好好的，点开 404。不报错、不告警，只有点的人知道。
>
> 签名密钥同理：`SHAREHUB_STORAGE_LOCAL_SIGN_SECRET` 不配就是 `dev-only-local-file-sign`
> （名字里就写着 dev-only），**任何人都能按公开算法伪造限时下载链接**。
>
> 本次上线已配（2026-09-25）：
> ```
> SHAREHUB_STORAGE_TYPE=local
> SHAREHUB_STORAGE_LOCAL_ROOT=/data/app/powerbank/files     # 属主 deploy:deploy 750，随应用盘备份
> SHAREHUB_STORAGE_LOCAL_SIGN_SECRET=<openssl rand -base64 36 生成的 48 位>
> ```
> **换 COS 只改这几行**（`type=cos` + region / secret / 三个桶名）；`CosFileStorage` 有启动自检，
> 缺任一项直接启动失败 —— 那是对的，半配好的存储比没配更糟。

> ⚠️ **发布前必查 · 迁移号顺序**（并行开发特有的坑）：
> Flyway 没开 `out-of-order`，`validate-on-migrate` 是 `true` —— 意味着
> **版本号低于「现网已应用的最高版本」的迁移，再也进不去**，而且会让启动直接失败。
>
> 本仓长期有多个会话并行，各自占用不同号段（如 V47/V48 与 V49/V52），
> 谁先部署，谁的号就成了基线。**分批部署最容易踩**：先上了含 V49 的版本，
> 之后含 V47 的那批就永远进不去了。
> ```bash
> # 发布前看现网应用到哪一版
> ssh soukmind-tx 'sudo mysql -N -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM powerbank.flyway_schema_history"'
> # 再看这次要带上去的最小号
> ls backend/sharehub-app/src/main/resources/db/migration/ | sed "s/V\([0-9]*\)__.*/\1/" | sort -n | tail -20
> ```
> 最小号 ≤ 现网最高版本就**先别发**：要么等那批一起合并后整体部署，
> 要么重新编号（改号只在**尚未部署到任何环境**时才安全 —— 已应用过的库里存着校验和）。

| 配置 | 仓库默认 | 生产必须 | 原因 |
|---|---|---|---|
| `SHAREHUB_IDENTITY_PEPPER` | 空 | **必须配，≥32 字符** | 登录标识（手机号/邮箱）哈希的 pepper。**为空即拒绝启动**。手机号取值空间小到可以穷举，裸哈希等于明文存储；给默认值等于所有部署共用一个 pepper。轮换见上方「发布前必查」 |
| `SHAREHUB_DEFAULT_CALLING_CODE` | `971`（AE） | 与业务市场一致，**上线后不可改** | 本地号补哪个国际区号。改了等于所有本地号算出另一个哈希 —— 表现是老用户「注册过但登录查不到」，且只影响没带国际区号的那批 |
| `NEXT_PUBLIC_USE_MOCK` | mock（`!== "0"`） | **`0`** | 漏配 → 静默跑 mock（ai-shop 2026-09-01 踩过，admin 登录看似无权限，其实请求根本没到后端） |
| `NEXT_PUBLIC_API_BASE`（ops-web） | — | 留空（同源） | 走 nginx 反代 `/ops/**`，后端不配 CORS |
| c-app 的 `VITE_API_BASE` | `.env` 里的默认 | **`.env.production` 留空**（同源） | Vite `VITE_*` 只从 `.env` 文件读，shell 环境变量覆盖不了；不覆盖就把本地地址烧进生产包 |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3306/pb_core` | `jdbc:mysql://127.0.0.1:3307/pb_core?...`（**端口 3307**） | 服务器 MySQL 9.7 在 3307，不是 3306 |
| `SERVER_PORT` | 8080 | **8082** | 避让 ai-shop 的 8081（shop-app）与 8083（pay-svc） |
| `PB_DB_USER` / `PB_DB_PASS` | dev 默认 | 生产随机 32 位 | 存 `sharehub-app.env`（600） |
| `SPRING_FLYWAY_PLACEHOLDER_REPLACEMENT` | 默认 true | **视 SQL 内容** | ai-shop 踩过 Flyway 把注释里的 `${}` 也当占位符解析 —— 上线前对 `backend/sharehub-app/src/main/resources/db/migration/*.sql` 扫一遍是否有裸 `${}` |
| `SHAREHUB_DEV_MODE_ENABLED` | `false` | **绝不可设为 true** | 开发便利总开关：固定验证码 `000000` + OTP 明文回传 + **免密登录运营端**。2026-09-23 之前这三件事在所有环境无条件生效，线上任何人可登录任意手机号（TDD-auth-security-hotfix）。开启时启动日志会打 WARN 横幅 |
| `SHAREHUB_ADMIN_PASSWORD` | 空 | **必须配非空** | 2026-09-23 起为 **fail-closed**：为空且 dev-mode 关 → **一律拒绝登录**（此前为空 = 任意用户名 + 前端自选角色直接放行）。角色只由 `SHAREHUB_ADMIN_ROLE` 决定，不认前端传入 |
| `SHAREHUB_SEED_ENABLED` | `false`（2026-09-23 由 `true` 改） | 生产保持 `false`；**演示机**要灌数据才设 `true` | 之前默认 `true`，空库启动即灌 12 个假代理商 / 20 个假站点。Seeder 幂等，改默认不影响存量数据 |
| `SHAREHUB_AUTH_TOKEN_STORE` | `memory` | 多实例用 `redis` | 2026-09-23 修正：此前开关键名写成 `powerbank.auth.token-store`，与配置对不上，**配 redis 也永远回落到内存实现**（且内存实现当时没有过期）。现已修正且内存档也有 TTL（`SHAREHUB_AUTH_TOKEN_TTL`，默认 2h） |
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
