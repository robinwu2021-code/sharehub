# 服务端 API 接口定义（api/README.md）

> 状态：**v2 全量重整**（2026-07-29）· 初版 2026-07-11
> 关联：[db-design.md](../technical/db-design.md)（库表 SSOT）· [architecture.md](../technical/architecture.md) · [功能权限清单](../requirements/功能权限清单.md)（RBAC SSOT）
> 约定对齐 ai-neargo：统一 `Result<T>`、`AuthHeaders` 可信头、`Query/PageResult` 分页、`ErrorCode`。
> 本文为**端点目录 + 设计裁决**（前缀怎么划、权限码怎么定、文档与代码冲突谁赢、写入口归谁）。
>
> ## 三份文档的分工（2026-07-30 拆分，改文档前先看这里）
>
> 逐端点的输入输出**不写在本文里**，改由脚本从控制器源码抽取 —— 268 个端点的字段级契约手写一遍，
> 下一次改代码就过期，而**过期的接口文档比没有文档更危险**：前端会照着它写，然后对不上。
>
> | 文件 | 内容 | 谁维护 |
> |---|---|---|
> | **本文** | 为什么这么设计：前缀划分、权限码约定、写入口判据、冲突裁决 | **手写** |
> | [reference.md](./reference.md) | 全量 268 端点的入参/出参/权限码/数据结构 | 脚本生成，**不要手改** |
> | [contract.json](./contract.json) | 机器可读真值。文档生成器与 ops-web 的 parity 门禁都读它 | 脚本生成 |
> | [前后端对齐缺口.md](./前后端对齐缺口.md) | 后端 × 前端调用 × 前端类型 三方比对出的缺口清单 | 脚本生成 |
>
> ```bash
> python3 backend/scripts/api-extract.py    # 控制器源码 → contract.json
> python3 backend/scripts/gen-api-doc.py    # contract.json → reference.md
> python3 backend/scripts/api-align.py      # 三方比对 → 前后端对齐缺口.md
> ```
>
> **contract.json 是唯一真值**：ops-web 的 `scripts/check-backend-parity.py` 原来自己用正则扫 Java，
> 与后端侧的抽取器各数出 267 和 268 —— 同一个问题两套口径就必然给出两个答案，
> 「后端到底有多少端点」变得无法回答。现已统一改读 contract.json。
>
> ## 本次重整的依据与口径（必读）
> v1 写于运营端 73 项菜单之前，且 `/mp` 端点是**照 C 端功能清单推演**的，与 c-app 实际在调的路径不符。v2 改为**以已落地的前端为准反查端点**：
>
> | 输入源 | 作用 |
> |---|---|
> | `ops-web/lib/api/https/*.ts` | 运营端**已在调的真实路径**（161 契约方法），本文运营端章节与之逐条对齐 |
> | `c-app/src/api/http.ts` | C 端**已在调的真实路径**（24 端点），本文 `/mp` 章节与之逐条对齐 |
> | [运营端功能清单 §三](../requirements/运营端功能清单.md) | 98 菜单叶 —— 每叶必须有端点 |
> | [C端功能清单](../requirements/C端功能清单.md) | 17 模块 · `C-XX-NN` 编号 |
> | [功能权限清单](../requirements/功能权限清单.md) | 89 权限码 —— 每个写端点必须挂码 |
>
> **v1 → v2 的关键裁决**：v1 文档路径与代码实际冲突的 5 处，**一律以代码为准**（前端已上线、改文档成本远低于改代码），见 §九。
>
> **端点数**：v1 约 70 → v2 **248**（运营端 196 · C端 38 · 内部/南向/回调 14）。

---

## 一、通用约定

### 1.1 响应包（commons `Result<T>`）
```json
{ "code": 0, "message": "ok", "data": {...} }
```
`ErrorCode`：0 成功 · 400 参数 · 401 未认证 · 403 无权 · 404 不存在 · 409 冲突/幂等 · 500 服务端。业务细分码在 `message` + 扩展 `bizCode`。
> ⚠️ 2026-09-23 更正：本行原写 `msg`，与本文件 §分页 的 `{list,total}` 及第 5 行「对齐 ai-neargo」自相矛盾。权威是两个项目共同依赖的 `neargo-common-core`：`Result{code,message,data}`。c-app 曾照抄错的那一行，已一并修。

> ⚠️ 前端 `ops-web/lib/types/common.ts` 的 `Result<T>` 用的是 `message` 字段，后端 `ApiResponseWrapper` 产出 `msg`。**以后端 `msg` 为准**，前端 http-client 已做兼容；新代码一律 `msg`。

### 1.2 路径前缀（**6 个运营前缀 + 4 个非运营前缀**）
> 运营端 6 前缀是 2026-07-29 治理后的定案（commit「端点前缀 12 → 6」），与 `ops-web/lib/api/https/*` 一致，**不得再新增运营前缀**。

| 前缀 | 面向 | 鉴权 | 承载 |
|------|------|------|------|
| `/api/auth/**` | 运营端登录 | 无（登录本身）→ 换 Bearer | 登录/登出/我的权限/动态菜单 |
| `/api/ops/**` | 运营端·运营侧 | Bearer + RBAC | 看板·设备·告警·工单·场地·客服·报表 |
| `/api/trade/**` | 运营端·交易侧 | Bearer + RBAC | 订单·计费·支付·财务·分润·结算 |
| `/api/user/**` | 运营端·用户侧 | Bearer + RBAC | C端用户·风控·会员钱包·营销·广告 |
| `/api/agent/**` | 运营端·代理侧 | Bearer + RBAC | 代理档案·账号·划拨·分润·绩效 |
| `/api/platform/**` | 运营端·平台侧 | Bearer + RBAC | 组织权限·审计·系统设置·字典·主数据 |
| `/mp/**` | C端 App/小程序 | C 池会话（auth-core CONSUMER） | C端 17 模块 |
| `/internal/**` | 域间 RestClient | 内网 + 受信头透传 | 域间编排 |
| `/gw/**` | 硬件供应商南向回调 | 供应商验签（`DeviceDriver.verify`） | 设备回调 |
| `/notify/**` | 支付渠道异步回调 | 渠道验签 | nearpay 回调 |
| `/openapi/**` | 第三方 | AppKey 签名 + 限流 | 开放平台 |

> **前缀 ≠ 领域**：前缀是「运营端页面按域分的接入面」，表子域见 db-design §1.2。唯一显式错位：`pay_channel` 表归 trade 子域，端点在 `/api/platform/payment-channels`（因为它在「系统设置」菜单下）。

### 1.3 受信头（commons `AuthHeaders`，边缘注入、内部只读）
`X-Region-Id` · `X-User-Id` · `X-Merchant-Id`（承载 powerbank `tenant_id`，ADR-007，MVP 恒 `MAIN`）· `X-Roles` · `X-Store-Scope`。**客户端伪造的同名头在入口一律剥离。**

### 1.4 分页 / 排序 / 筛选
- 分页：query `page/size` → `PageResult<T>{list,total,page,size}`。
- 关键词：`keyword`（各列表自定义搜索列，见各模块 TDD）。
- **受控排序**：仅白名单列表支持 `sort`/`dir`，白名单在 `ops-web/lib/api/query.ts` 定义（如 `notify-logs` 只允许 `sentAt|cost`，`share-summaries` 只允许 `shareAmount|pendingAmount|gmv|orderCount`）。**后端必须按白名单校验，不得拼接任意列**（SQL 注入面）。
- 域特有筛选参数以 `query.ts` 的 `<Domain>Q` 为准，本文各表「筛选」列即其展开。

### 1.5 写操作约定
- **Upsert 统一形态**：`POST /{collection}` = 新建 · `POST /{collection}/{no}` = 更新。**全站零 `DELETE` 端点**（软删除，见 db-design §1.3 / G1）；下线一律 `POST /{collection}/{no}` 改 `status`，或走专用动作端点（`/release`、`/revoke`、`/rollback`）。
- **动作端点**用动词后缀：`/dispatch` `/intervene` `/audit` `/handle` `/cancel` `/revoke` `/release` `/rollback` `/work-order`。
- **幂等**：写操作带 `Idempotency-Key`（或业务键 `*_no`）；重复返回**首次结果**（200 原样，非 409）。落点见 db-design §1.6。
- **审批类强约束**：`/audit` 驳回时 `rejectReason` **必填**，且服务端必须回填 `auditorNo/auditorName/auditedAt`（不信前端传的审批人）。
- 版本：路径不带版本，破坏性变更走新端点；契约演进只增不改（对齐 neargo）。

### 1.6 脱敏约定（服务端出参即脱敏，不靠前端遮）
| 字段 | 规则 |
|---|---|
| `notify_log.target` / `notify_blacklist.target` | 手机留前 6 后 2；邮箱留首字母 + 域名 |
| `pay_channel.apiKey` | **只出 `apiKeyMasked`**，明文永不出网关 |
| `gw_vendor_config.appSecret` / `verifyKey` | 同上，只出掩码 |
| `iam_employee.phone` / `usr_user.phone` | 列表页掩码；明文需 `pii:read` 且逐次审计 |

---

## 二、`/api/auth` —— 认证与主体（8 端点）

| Method Path | 用途 | 权限码 |
|---|---|---|
| `POST /api/auth/login` | 账号登录 → Bearer token + 角色 + 权限集 | — |
| `POST /api/auth/logout` | 登出（作废 token） | — |
| `GET /api/auth/me` | 当前登录人（角色/数据范围/权限集） | — |
| `GET /api/auth/menus` | **动态菜单树**（按 RBAC + phase 过滤） | — |
| `GET /api/auth/permissions` | 当前登录人权限码集合 | — |
| `POST /api/auth/otp` 🆕 | 代理端登录发码（**匿名**） | — |
| `GET /api/auth/operators` 🆕 | 我的运营主体列表（ADR-030） | — |
| `POST /api/auth/operators/{agentNo}/switch` 🆕 | 切换当前主体（**换发 token**） | — |

> 菜单来自 `iam_menu` 表，与前端 `lib/nav.ts` 的 98 叶 1:1。前端目前用本地 nav.ts，切后端后改读本端点（见[前端-动态菜单权限接入指引](../technical/前端-动态菜单权限接入指引.md)）。

### 2.1 代理端实名登录：三个裁决（2026-09-23）

补这三个端点是为了接上一处**静默的断裂**：入驻审核通过后 `ApplyService.activate()`
会建出 `agt_principal` + `agt_account`，而 `AuthController` 里 `agt_account` 出现
**0 次** —— 生产登录只认一个硬编码的 `admin`，**入驻建出来的号登不进去**。
两边的测试当时都是绿的，因为没有任何一条用例跨过入驻与登录的边界。

**① 登录只认「手机号 + 验证码」，不做口令、不做邮箱**

| 为什么不做 | 理由 |
|---|---|
| 口令 | 全仓没有凭据存储：`iam_user` 无口令列，`cred_credential` 在 `V4` 里是**注释掉的设计**，归属 `pb_auth` / auth-core。现在另起一张平行凭据表，auth-core 落地时要做数据迁移 —— 而 OTP 登录本身就是完整的登录方式，不是权宜之计 |
| 邮箱 | 发码要明文，而 `activate()` 只写了 `email_hash`/`email_mask`，**`email_enc` 是空的**、掩码不可逆 —— 发不出去。手机号同理，所以必须由用户**自己输入**，服务端规范化后按 hash 反查 |

**② 查无此号也返回成功**

`POST /api/auth/otp` 对未注册手机号**静默返回 ok**，登录失败时「号不存在」与「码不对」
说**同一句话**。分开的话，这两个接口合起来就是一台代理商手机号探测器。
代价是输错号的人会等一条永远不到的短信 —— 这个代价值得付。

**③ 切换主体换发 token，而不是改会话字段**

`agentNo` 是数据范围的锚点（`PermissionService.resolveDataScope` 按它做 AGENT 硬过滤）。
原地改字段的话，旧 token 仍在别处使用时会拿着**旧范围**继续跑；换发后老 token 立刻吊销，
没有两个范围并存的窗口。

> **接口在 `platform`、实现在 `agent`**：`AuthController` 直接依赖 `agent` 会闭合
> `agent → loc → platform → agent` 的环（ArchUnit `noCyclesBetweenDomains` 会红）。
> 按依赖倒置由消费方声明端口 `platform.iam.port.AgentIdentityPort`，`agent` 去实现 ——
> 与既有的 `OtpGate` 同手法，方向相反。

---

## 三、`/api/ops` —— 运营侧（74 端点）

### 3.1 经营看板（菜单：概览 1 叶 + 4 页内块）
| Method Path | 用途 | 权限码 |
|---|---|---|
| `GET /api/ops/dashboard` | 总览 KPI + 趋势 + 实时告警 + 待办中心 + 排名榜单（一次返回） | `dashboard:overview:read` |

> 单端点聚合返回 `DashboardStats{gmvToday, ordersToday, activeCabinets, onlineRate, openWorkOrders, currency, trend[], todos{}, alerts[], rankings[]}`。**待办中心**的三个数（待派工单/待审退款/待审提现）来自 `wo_order`/`ord_refund`/`stl_withdrawal`，需 `dashboard:todo:read`，无码时该块置空而非 403。

### 3.2 设备管理（菜单 8 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/cabinets` | 机柜台账（筛选 `keyword/onlineStatus/status/locationNo/agentNo/vendorCode`） | `device:cabinet:read` | 设备台账 |
| `GET /api/ops/cabinets/{cabinetNo}` | 机柜详情 + 仓位明细 | `device:slot:read` | 机柜详情（页内）|
| `POST /api/ops/cabinets` · `POST /api/ops/cabinets/{no}` | 机柜建档 / 编辑 | `device:cabinet:create` `:update` | 机柜建档（页内，**当前缺**）|
| `POST /api/ops/cabinets/{no}/status` | 退役/停用（软删除语义） | `device:cabinet:delete` | 同上 |
| `POST /api/ops/cabinets/import` | Excel 批量导入台账（校验报告） | `device:cabinet:import` | 导入/导出（**G2**）|
| `GET /api/ops/cabinets/export` | 台账导出 CSV | `device:cabinet:read` | 同上 |
| `POST /api/ops/cabinets/{no}/commands` | 单柜远程指令（弹出/锁/解锁/重启/定位/语音）→ 转 access-gateway | `device:command:send` | 单柜远程指令（页内）|
| `POST /api/ops/cabinets/commands/batch` | **批量指令**（多柜，带限速） | `device:command:batch` | 批量指令（**G3**）|
| `POST /api/ops/cabinets/{no}/location` | 绑定/换绑/调拨点位 | `device:cabinet:assign` | 设备-点位绑定 |
| `GET /api/ops/powerbanks` | 充电宝列表 / 生命周期 | `device:powerbank:read` | 充电宝管理 |
| `POST /api/ops/powerbanks` · `POST /api/ops/powerbanks/{no}` | 充电宝建档 / 状态变更（报废/丢失） | `device:powerbank:update` | 充电宝管理 |
| `GET /api/ops/cabinet-monitor` | **实时监控**（在线/心跳/信号/温度/故障数） | `device:cabinet:read` | 实时监控 |
| `GET /api/ops/command-records` | 指令下发记录（状态：发出/确认/超时/失败） | `device:command:send` | 远程控制·指令记录 |
| `GET /api/ops/device-logs` | **设备日志双流**（筛选 `stream=COMMAND\|REPORT` + `from/to`） | `device:cabinet:read` | 设备日志 |
| `GET /api/ops/inventory-transfers` | 库存调拨单列表 | `device:inventory:read` | 库存调拨 |
| `POST /api/ops/inventory-transfers` · `/{no}` | 建调拨单 / 更新（发出/收货） | `device:inventory:transfer` | 库存调拨 |
| `GET /api/ops/ota-rollouts` | OTA 投放列表 + 进度 | `device:ota:read` | 固件 OTA |
| `POST /api/ops/ota-rollouts` · `/{no}` | 创建/更新投放（灰度比例、回滚） | `device:ota:manage` | 固件 OTA |
| `GET /api/ops/ota-releases` · `POST /api/ops/ota-releases` | 固件版本管理 | `device:ota:manage` | 固件 OTA |
| `GET /api/ops/device-code-batches` | 设备编码批次（区间 + 绑定进度） | `device:cabinet:read` | 设备编码 |
| `POST /api/ops/device-code-batches` · `/{no}` | 建批次 / 更新 | `device:cabinet:update` | 设备编码 |
| `GET /internal/ops/cabinets/{no}/availability` | 可借/可还库存（trade 借出前校验） | 内部 | — |
| `POST /internal/ops/devices/events` | 接收 access-gateway 上行设备事件 | 内部 | — |

> **设备日志不落新表**：服务端把 `gw_command_log`（下行）与 `gw_message_log`（上行）UNION 后按 `occurredAt` 排序返回，`stream` 字段区分双流。这是「比竞品清晰」的落点——对方只有单向日志。

### 3.3 告警管理（菜单 4 叶 · v2 新增模块）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/alarms/records` | 告警记录（筛选 `level/status/cabinetNo`） | `workorder:wo:read` | 告警记录 |
| `POST /api/ops/alarms/records/{alarmNo}/ack` | 确认告警 | `workorder:wo:read` | 告警记录 |
| `POST /api/ops/alarms/records/{alarmNo}/work-order` | **一键转工单（幂等）** | `workorder:wo:create` | 告警记录 |
| `GET /api/ops/alarms/notices` | 告警通知流水（渠道/目标/成败/失败原因） | `workorder:wo:read` | 告警通知 |
| `GET /api/ops/alarms/codes` | 告警代码字典 | `workorder:wo:read` | 告警代码 |
| `POST /api/ops/alarms/codes` · `/{code}` | 维护告警码（含**建议处置 + 自动开单开关**） | `workorder:wo:update` | 告警代码 |
| `GET /api/ops/alarms/rules` | 通知规则列表 | `workorder:wo:read` | 通知规则 |
| `POST /api/ops/alarms/rules` · `/{ruleNo}` | 维护规则（含**静默窗口 + 升级策略**） | `workorder:wo:update` | 通知规则 |
| `POST /internal/ops/alarms` | 告警上报（网关/系统 → 自动开单联动） | 内部 | — |

> **转工单幂等**：`POST .../{alarmNo}/work-order` 以 `alarmNo` 为幂等键（`wo_order.source_ref` UNIQUE）。重复调用返回**首次生成的 `woNo`**，不产生第二张单。投诉转工单同理。

### 3.4 工单管理（菜单 4 叶 + 页内动作）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/work-orders` | 工单列表/看板（筛选 `status/type/assignee`） | `workorder:wo:read` | 工单列表/看板 |
| `GET /api/ops/work-orders/{woNo}` | 工单详情 | `workorder:wo:read` | 工单详情 |
| `POST /api/ops/work-orders` | **手工开单**（补 G6 缺口） | `workorder:wo:create` | 开单（页内）|
| `POST /api/ops/work-orders/{woNo}/dispatch` | 派单（就近/负载/手动） | `workorder:wo:dispatch` | 派单（页内）|
| `POST /api/ops/work-orders/{woNo}/accept` | 接单（运维 App） | `workorder:wo:process` | 处理与验收 |
| `POST /api/ops/work-orders/{woNo}/handle` | 现场处理（打卡/拍照/换件） | `workorder:wo:process` | 处理与验收 |
| `POST /api/ops/work-orders/{woNo}/close` | 完成 / 审核关单 | `workorder:wo:audit` | 处理与验收 |
| `GET /api/ops/sla-rules` · `POST` · `/{slaNo}` | SLA 规则配置 | `workorder:wo:read` `:update` | SLA 管理 |
| `GET /api/ops/inspection-plans` · `POST` · `/{planNo}` | 巡检计划 | `workorder:wo:read` `:update` | 巡检计划 |

> **G6 工单闭环断裂**：前端目前只迁移了 `dispatch` 一个动作，`create`/`accept`/`handle`/`close` 四个端点是补齐闭环的后端前提。状态机迁移合法性由 `WoStateMachine` 校验（已实现），非法迁移返 409。

### 3.5 站点与点位（菜单 8 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/sites` · `POST` · `/{siteNo}` | 站点（含 `agentNo` 归属） | `location:poi:read` `:create` | 站点管理 |
| `GET /api/ops/locations` · `POST` · `/{locationNo}` | 点位 | `location:poi:read` `:create` | 点位管理 |
| `GET /api/ops/site-analysis` | **站点坪效**（收入/订单/翻台/回本天数） | `location:analysis:read` | 站点坪效 |
| `GET /api/ops/venues` · `POST` · `/{venueNo}` | 场地方档案 | `location:venue:read` `:create` | 场地方 |
| `GET /api/ops/contracts` · `POST` · `/{contractNo}` | 进场合同（分成/进场费/账期） | `location:contract:read` `:create` | 进场合同 |
| `GET /api/ops/venue-onboardings` | 门店自助进件列表 | `location:venue:read` | 门店 Onboarding |
| `POST /api/ops/venue-onboardings/{no}/review` | 审核（通过则**建 `loc_venue` 并回填 `venueNo`**） | `location:venue:create` | 门店 Onboarding |
| `GET /api/ops/site-lifecycles` | 门店生命周期阶段表 | `location:venue:read` | 门店生命周期 |
| `POST /api/ops/site-lifecycles/{siteNo}/stage` | 阶段流转（留痕到 `loc_site_lifecycle_log`） | `location:venue:update` | 门店生命周期 |
| `GET /api/ops/leads` · `POST` · `/{leadNo}` | BD 商机跟进 | `location:crm:read` `:update` | BD 拓展 CRM |

### 3.6 客服管理（菜单 4 叶，2 叶为跨域深链）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/cs/tickets` | 报障受理列表 | `cs:ticket:read` | 报障受理 |
| `POST /api/ops/cs/tickets/{ticketNo}` | 受理/更新（含备注、处理人） | `cs:ticket:update` | 报障受理 |
| `POST /api/ops/cs/tickets/{ticketNo}/work-order` | 报障 → 转工单（幂等） | `workorder:wo:create` | 报障受理 |
| `POST /api/ops/cs/tickets/{ticketNo}/refund` | 报障 → 转退款申请（幂等） | `order:refund:apply` | 报障受理 |
| `GET /api/ops/cs/sessions` | 客服会话列表 | `cs:session:read` | 客服会话 |
| `GET /api/ops/cs/sessions/{sessionNo}/messages` | 会话消息 | `cs:session:read` | 客服会话 |
| `POST /api/ops/cs/sessions/{sessionNo}/messages` | 客服回复 | `cs:session:reply` | 客服会话 |

> 「退款/补偿」与「黑名单处理」两叶是**跨域深链**（→ `/orders`、`/users`），不另设端点。

### 3.7 数据报表（菜单 6 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/reports/device` | 设备运营分析（在线率/翻台/故障率） | `report:device:read` | 设备运营分析 |
| `GET /api/ops/reports/location` | 点位坪效（收入/成本/回本/ROI） | `report:location:read` | 点位坪效 |
| `GET /api/ops/reports/finance` | 财务报表（GMV/分润/结算/净额） | `report:finance:read` | 财务报表 |
| `GET /api/ops/reports/screen` | 实时大屏指标 | `report:screen:read` | 实时大屏 |
| `GET /api/ops/reports/custom` | 自定义报表（自选维度×指标） | `report:custom:read` | 自定义报表 |
| `GET /api/ops/reports/consumer-segments` | 消费者分析（画像/复借/漏斗） | `report:consumer:read` | 消费者分析 |
| `GET /api/ops/reports/{key}/export` | 报表导出 CSV（统一导出口，**G2**） | 各自读码 | — |

> 六张报表全是**实时聚合**（db-design §11.1 标 `[读]`）。单次聚合 >2s 时再引 `rpt_*` 物化表。

### 3.8 营销·公告（落 `/api/ops` 的唯一营销端点）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/marketing/notices` · `POST` · `/{noticeNo}` | 公告管理（三语 + 生效期 + 置顶） | `marketing:notice:read` `:update` | 公告管理 |

> 公告是 C 端首页公告条的发布口，其余营销能力在 `/api/user`（§五）。**此处的前缀归属沿用前端现状**，未来若统一到 `/api/user/notices` 需前后端同改。

### 3.9 广告位（挂机柜，故归 ops）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/ops/ad-slots` · `POST` · `/{slotNo}` | 广告位登记（挂 `cabinetNo`） | `marketing:ad:read` `:update` | 广告位管理 |

---

## 四、`/api/trade` —— 交易与资金（62 端点）

### 4.1 订单管理（菜单 7 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/trade/orders` | 订单列表（筛选 `status/keyword/siteNo/cabinetNo/userNo`；进行中 = `status=IN_USE`） | `order:order:read` | 订单列表 |
| `GET /api/trade/orders/{orderNo}` | 订单详情 + 状态时间线 | `order:order:read` | 订单详情（页内）|
| `GET /api/trade/orders/export` | 订单导出 | `order:order:export` | — |
| `POST /api/trade/orders/{orderNo}/intervene` | 客服干预（远程弹出/强制归还/免单/补偿） | `order:intervene:execute` | 订单干预（页内）|
| `GET /api/trade/reservations` | 预约订单（筛选 `status/type`） | `order:order:read` | 预约订单 |
| `POST /api/trade/reservations/{no}/cancel` | 取消预约（**仅 `PENDING` 可取消，服务端复校**） | `order:order:update` | 预约订单 |
| `GET /api/trade/order-exceptions` | 异常订单（未弹出/未归还/超时买断/重复扣费） | `order:exception:read` | 异常订单 |
| `POST /api/trade/order-exceptions/{no}/handle` | 异常处置 | `order:exception:handle` | 异常订单 |
| `GET /api/trade/complaints` | 投诉订单 | `order:exception:read` | 投诉订单 |
| `POST /api/trade/complaints` | **投诉登记**（补缺口） | `order:exception:handle` | 投诉订单 |
| `POST /api/trade/complaints/{no}/handle` | 投诉处理（退款/补偿/驳回/已解释 + 处理人留痕） | `order:exception:handle` | 投诉订单 |
| `POST /api/trade/complaints/{no}/work-order` | 投诉 → 转工单（幂等） | `workorder:wo:create` | 投诉订单 |
| `GET /api/trade/refunds` | 退款审批队列 | `order:refund:audit` | 退款记录 |
| `POST /api/trade/refunds` | **退款申请**（带 `Idempotency-Key`，补缺口） | `order:refund:apply` | 退款记录 |
| `POST /api/trade/refunds/{refundNo}/audit` | 退款审批（**驳回必填 `rejectReason`**；通过则发起 nearpay 退款） | `order:refund:audit` | 退款记录 |
| `GET /api/trade/deposits` | 押金与欠费（冻结/解冻/买断/欠费） | `order:order:read` | 押金与欠费 |
| `POST /api/trade/deposits/{no}/release` | 手工解冻押金 | `order:intervene:execute` | 押金与欠费 |
| `GET /api/trade/free-orders` | 免费订单（`ord_rent.free_reason IS NOT NULL`） | `order:order:read` | 免费订单 |
| `GET /api/trade/free-orders/stats` | **全量口径统计**（月单量/减免总额，非当前页） | `order:order:read` | 免费订单 |
| `POST /internal/trade/orders/{orderNo}/return` | 归还结单（设备归还事件驱动） | 内部 | — |

> **`/refunds` 是「业务审批单」不是「渠道退款」**：`POST /api/trade/refunds` 建 `ord_refund`（幂等键在此刻生成），审批通过后服务端才调 `PaymentPort` 建 `pay_refund` 并回填 `psgTxnNo`。这就是「我们多审批链与幂等键」的落点。

### 4.2 计费定价（菜单 3 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/trade/price-plans` · `POST` · `/{planNo}` | 计费模板（免费时长/单位价/日封顶/买断价） | `pricing:plan:read` `:create` | 计费模板 |
| `GET /api/trade/pricing-diffs` · `POST` · `/{ruleNo}` | 差异化定价（按点位/场景取价） | `pricing:rule:read` `:update` | 差异化定价 |
| `GET /api/trade/pricing-schedules` · `POST` · `/{ruleNo}` | 活动/时段价（分时段、节假日） | `pricing:rule:read` `:update` | 活动/时段价 |

> 模板改动**仅影响新订单**：订单落 `price_plan_no` 快照，历史单不重算。

### 4.3 财务：分润与结算（菜单 4 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/trade/share-rules` · `POST` · `/{ruleNo}` | 分润规则（维度 VENUE/AGENT，双模式，优先级） | `finance:share_rule:read` `:create` | 分润规则 |
| `GET /api/trade/share-records` | 分润明细（逐单） | `finance:share_record:read` | 分润明细 |
| `GET /api/trade/share-summaries` | **分润统计**（`dimension` 维度切换 + 受控排序） | `finance:share_record:read` | 分润统计 |
| `GET /api/trade/settlements` | 结算单（对象 = 场地方/代理） | `finance:settlement:read` | 结算单 / 代理收益结算 |
| `GET /api/trade/settlements/{settleNo}` | 结算单详情 + 明细 | `finance:settlement:read` | 结算单 |
| `POST /api/trade/settlements/{settleNo}/confirm` | 确认结算单 | `finance:settlement:confirm` | 结算单 |
| `POST /internal/trade/settlements/generate` | **周期出账**（批处理作业调用，非页面入口） | 内部 | — |

> **一张表 + 维度切换器**：`share-summaries` 用 `dimension=VENUE\|AGENT` 切主体，对方是按运营商/商户切两套表。排序列白名单：`shareAmount/pendingAmount/gmv/orderCount`。

### 4.4 财务：平台账（菜单 3 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/trade/ledger` | 账务分录（复式，只增） | `finance:ledger:read` | 账务分录 |
| `GET /api/trade/reconciles` | 对账批次（nearpay ↔ 支付引用 ↔ 账务 三方） | `finance:recon:read` | 对账 |
| `GET /api/trade/reconciles/{batchNo}/diffs` | 对账差错明细 | `finance:recon:read` | 对账 |
| `POST /api/trade/reconciles/{batchNo}/resolve` | 差错平账处置 | `finance:recon:resolve` | 对账 |
| `GET /api/trade/invoices` · `POST` · `/{invoiceNo}` | 发票（开票/红冲，VAT/TRN） | `finance:invoice:read` `:issue` | 发票 |

### 4.5 财务：伙伴账 · 用户账（菜单 4 叶，2 叶为跨域深链）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/trade/withdrawals` | 提现审核队列 | `finance:withdrawal:read` | 提现审核 |
| `POST /api/trade/withdrawals/{no}/audit` | 提现审批（**驳回必填原因**；手续费口径取 `sys_biz_rule(WITHDRAW)`） | `finance:withdrawal:audit` | 提现审核 |
| `POST /api/trade/withdrawals` | 提现申请（代理端/商户端发起） | `finance:withdrawal:apply` | —（代理端）|
| `POST /api/trade/withdrawals/{no}/pay` 🆕 | **打款回执登记**（成功必填流水号 / 失败必填原因） | `finance:withdrawal:pay` | 提现审核 |
| `GET/POST /api/trade/payout-accounts` · `/{no}/disable` | 收款账户（审批通过的前置） | `finance:payout_account:read` `:update` | 收款账户 |

> **`:pay` 为什么与 `:audit` 分开发码**：审批是「同意把钱打出去」，回执是「钱确实出去了」——
> 中间隔着一次真实资金动作，可能失败、可能延迟几天。合成一步就等于默认审批必然成功，
> `PAYING` 这个状态本身也就没有意义了。FINANCE 当前持 `finance:*` 通配、两码都有，
> **但码分开了，将来要做双人复核只是改角色配置；码没分开，就得改代码**。
>
> 在此之前状态机里的 `PAY`/`FAIL` 迁移**没有任何入口调用** —— 审批完的单子永远停在
> `PAYING`：钱算得清、批得了，批完不会动。回执入口与代付通道（nearpay，硬阻塞 2）是
> **两件事**：通道接通后只是换一个调用方来调同一个服务方法，状态机与幂等不必重写。
> 幂等键 `uk_stl_withdrawal_payref(pay_channel, pay_ref)` 拦「同一笔银行流水记到两张单上」。

> 「代理分润配置」「用户钱包」两叶是跨域深链（→ `/agents?tab=commission`、`/users?tab=wallets`）；「充值订单」端点在 `/api/user`（§五）。

### 4.6 支付编排（**委托 nearpay，延后集成 · ADR-005**）
> powerbank 经 `PaymentPort` 调 nearpay；MVP 用 Stub。以下为 powerbank 侧编排端点，实际收单在 nearpay。

| Method Path | 用途 |
|---|---|
| `POST /internal/trade/pay` | 发起支付（`type=DEPOSIT/RENT/BUYOUT/RECHARGE/MEMBERSHIP`） |
| `POST /internal/trade/auth/freeze` · `/capture` · `/release` | 免押 auth / capture / void 全流程 |
| `POST /internal/trade/refund` | 发起渠道退款（由 `ord_refund` 审批通过后触发） |
| `POST /notify/pay/nearpay` | nearpay 支付结果回调（**验签 → 幂等 → 改状态 → 发 PayEvent**） |
| `POST /notify/refund/nearpay` | nearpay 退款回调 |

> 回调幂等键 = `pay_event_log` UK(`ref_no`,`event_type`)。**以回调为准，前端不臆断支付结果**（C-PAY-03）。

---

## 五、`/api/user` —— 用户与营销（38 端点）

### 5.1 用户管理（菜单 7 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/user/users` | C端用户列表（昵称/手机掩码/信用分/订单数） | `user:cuser:read` | 用户列表 |
| `GET /api/user/users/{cUserNo}` | 用户详情（订单/钱包/券/风控 聚合） | `user:cuser:read` | 用户列表 |
| `GET /api/user/risk-users` | 风控用户（信用分 + 风险等级 + 原因） | `user:risk:read` | 风控用户 |
| `GET /api/user/blacklist` | 黑名单列表 | `user:risk:read` | 黑名单 |
| `POST /internal/user/credit/blacklist` | 拉黑 / 解除（客服/系统） | `user:risk:update` | 黑名单 |
| `GET /api/user/free-whitelist` | 免费用户白名单（用途 + 额度 + 有效期） | `user:risk:read` | 免费用户白名单 |
| `POST /api/user/free-whitelist` · `/{userNo}` | 授予 / 修改白名单 | `user:risk:update` | 免费用户白名单 |
| `POST /api/user/free-whitelist/{userNo}/revoke` | **撤销（软删除：`status=REVOKED` 留记录）** | `user:risk:update` | 免费用户白名单 |
| `GET /api/user/members` · `POST` · `/{userNo}` | 会员/次卡 | `user:member:read` `:update` | 会员/次卡 |
| `GET /api/user/wallets` · `POST /{userNo}` | 钱包（余额/赠金 + **用户价值画像**） | `user:wallet:read` `:adjust` | 钱包 |
| `GET /api/user/wallets/{userNo}/txns` | 钱包流水 | `user:wallet:read` | 钱包 |
| `GET /api/user/recharge-packages` · `POST` · `/{packageNo}` | 充值套餐（含赠送 + 有效期 + 适用市场） | `user:wallet:read` `:update` | 充值套餐 |
| `GET /api/user/recharge-orders` | 充值订单（筛选 `status/from/to`） | `user:wallet:read` | 充值订单（财务·用户账）|

> **用户价值画像**（`orderCount/orderAmount/rechargeCount/rechargeAmount`）由服务端聚合 `ord_rent` + `usr_recharge_order`，**不落冗余列** —— 落列会与订单页不自洽。

### 5.2 营销管理（菜单 8 叶，公告在 §3.8、广告位在 §3.9）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/user/coupons` · `POST` · `/{couponNo}` | 券模板（发放规则/库存/防刷） | `marketing:coupon:read` `:create` | 优惠券 |
| `POST /api/user/coupons/{tplNo}/issue` | 定向发券 | `marketing:coupon:issue` | 优惠券 |
| `GET /api/user/campaigns` · `POST` · `/{campaignNo}` | 活动（规则/权益计算） | `marketing:campaign:read` `:update` | 活动 |
| `GET /api/user/push-messages` · `POST` · `/{pushNo}` | 推送触达（App Push / 订阅消息） | `marketing:push:send` | 推送触达 |
| `GET /api/user/referrals` | 邀请裂变记录（归因/反作弊） | `marketing:campaign:read` | 邀请裂变 |
| `GET /api/user/ad-campaigns` · `POST` · `/{adNo}` | 广告活动（广告主/创意/排期） | `marketing:ad:read` `:update` | 广告活动 |
| `GET /api/user/ad-deliveries` | 投放与曝光统计 | `marketing:ad:read` | 投放与曝光 |

---

## 六、`/api/agent` —— 代理商（12 端点）

| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/agent/agents` · `POST` · `/{agentNo}` | 代理商档案（辖域/分润比例/结算账户） | `agent:agent:read` `:create` | 代理商档案 |
| `GET /api/agent/accounts` · `POST` · `/{accountNo}` | 代理登录账号（开通/停用 + 数据范围） | `agent:account:manage` | 代理账号管理 |
| `GET /api/agent/assignments` | 设备/点位归属现状 | `agent:scope:assign` | 设备/点位划拨 |
| `POST /api/agent/assignments` | 划拨 / 收回（留痕到 `agt_assignment`） | `agent:scope:assign` | 设备/点位划拨 |
| `GET /api/agent/commissions` · `POST` · `/{ruleNo}` | 代理分润配置（GMV/单量维度） | `agent:share:config` | 分润配置 |
| `GET /api/agent/performance` | 代理绩效（片区 GMV/设备数/在线率/排名） | `agent:performance:read` | 代理绩效 |

### 6.1 代理端自助视图（AGENT 角色，**整体未建 · G5**）
> 复用上述端点 + 数据范围强制 `AGENT`（只见自己 `agent_no`），**不另设前缀**。落地时只需在 `iam_data_scope` 为该账号注册 `scope_type=AGENT`。

| 代理端功能 | 复用端点 | 数据范围 |
|---|---|---|
| 我的看板 | `GET /api/ops/dashboard` | AGENT |
| 我的设备 | `GET /api/ops/cabinets` | AGENT |
| 我的订单 | `GET /api/trade/orders`（脱敏） | AGENT |
| 我的收益 | `GET /api/trade/share-records` `/settlements` + `POST /api/trade/withdrawals` | AGENT |
| 设备报修 | `POST /api/ops/work-orders` | AGENT |

---

## 六·A、写入口与生产者（业务梳理定稿 · 2026-07-29）

> 前端 161 个契约方法里**零 DELETE**，且有 5 类单据「只有读 / 只有审批、没有创建」。这不是遗漏就是设计——本节按**业务上谁产生这张单**逐条定稿，端点、触发方、权限码三者对齐。**结论即 SSOT**。

### 6A.1 五个缺失写入口的生产者

| 单据 | 业务上谁产生 | 触发方式 | 端点 | 权限码 |
|---|---|---|---|---|
| **工单** `wo_order` | ① 系统（告警命中 `autoWorkOrder`）② 系统（C端报障/投诉转单）③ **运维/客服人工** | ①② 自动 ③ 页面 | ① `POST /internal/ops/alarms` ② `.../work-order` ③ **`POST /api/ops/work-orders`** | `workorder:wo:create` |
| **退款单** `ord_refund` | ① C端报障转退款 ② **客服在订单页发起**（多扣费/免单补偿）③ 投诉处理结果 = `REFUND` | ① 页面动作 ② 页面 ③ 自动 | ① `POST /api/ops/cs/tickets/{no}/refund` ② **`POST /api/trade/refunds`** ③ `POST /api/trade/complaints/{no}/handle` 内联 | `order:refund:apply` |
| **投诉单** `ord_complaint` | ① **C端提交**（争议类问题）② 客服代客登记（电话/线下投诉） | ① 自动 ② 页面 | ① `POST /mp/user/report`（争议类自动派生）② **`POST /api/trade/complaints`** | `order:exception:handle` |
| **提现单** `stl_withdrawal` | **代理商/场地方本人**（代理端「我的收益」）—— **运营端没有也不该有创建入口** | 代理端页面 | `POST /api/trade/withdrawals` | `finance:withdrawal:apply`（AGENT 角色）|
| **结算单** `stl_settlement` | **后端定时批处理**（按结算周期出账）—— 无人工入口 | 调度作业 | `POST /internal/trade/settlements/generate` | 内部 |

**三条判据**（用于将来判断新单据该由谁创建）：
1. **由外部事实触发的 → 系统自动建**（设备告警、C端提交、周期到点）。人不该手动补录事实。
2. **需要人做判断才成立的 → 运营端页面建**（客服认定该退款、运维认定该开单）。
3. **涉及本人权益申领的 → 权益人自己建**（提现是代理商的钱，运营只审不代申）。第 3 条是**合规红线**：运营人员代申提现会让审批链失去制衡。

### 6A.2 「诉求受理」的统一分流（报障 / 投诉 / 工单 / 退款 四者的关系）

v1 把这四者各写各的，导致 C 端一个报障可能同时落进 `cs_ticket` 和 `ord_complaint` 而无从对账。v2 定稿为**单一入口 + 字典驱动分流**：

```
C端 POST /mp/user/report  ──▶  cs_ticket（唯一受理单，必建）
                                  │
                     按 md_problem.suggested_action 分流
                                  │
   ┌──────────────┬───────────────┼────────────────┬──────────────┐
   ▼              ▼               ▼                ▼              ▼
SELF_SERVICE  TO_WORKORDER    TO_REFUND         TO_CS      （争议类问题额外派生）
自助解决        开工单          建退款申请       转人工会话      ord_complaint
直接关单     wo_order         ord_refund       cs_session    （挂 order_no）
            (source=USER)   (待财务审批)
```

- **`cs_ticket` 是唯一受理单**，任何 C 端诉求都先落它 —— 它持有 `wo_no` / `refund_no` 两个出口字段，保证「一个诉求 → 处置去向可追溯」。
- **`ord_complaint` 只在「针对某笔订单的争议」时派生**（计费争议、多扣费），粒度是**订单**；`cs_ticket` 粒度是**诉求**。二者 0..1 关联，不重复受理。
- **分流规则来自 `md_problem.suggested_action` 字典**（运营端「问题管理」维护），**不硬编码在代码里** —— 这样运营调整处置策略无需发版。
- 三个出口动作（转工单/转退款/转会话）**全部幂等**，幂等键见 [db-design §1.6](../technical/db-design.md)。

### 6A.3 为什么全站没有 DELETE

**软删除是唯一删除语义**（[G1](../requirements/运营端功能清单.md#三b-横向缺口跨模块2026-07-29-梳理发现)），这是有意设计不是缺口：

| 场景 | 做法 | 端点形态 |
|---|---|---|
| master 数据下线（设备/站点/场地方/模板） | 改 `status` 停用 | `POST /{collection}/{no}` |
| 已产生业务引用的记录 | **连逻辑删除都禁止**（设备出过订单、场地方签过合同） | 只允许改 `status` |
| 需要留痕的解除类操作 | 专用动作端点 + 保留原记录 | `/release`（触达拉黑）`/revoke`（白名单）`/rollback`（版本）`/cancel`（预约）|
| 个人数据注销（PDPL） | **唯一真正的物理删除**，走冷静期后的清除作业 | `POST /mp/user/logoff` → 到期作业清 `pb_pii` |

---

## 七、`/api/platform` —— 组织权限与系统设置（38 端点）

### 7.1 员工与权限（菜单 5 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/platform/employees` · `POST` · `/{employeeNo}` | 员工（新增/编辑/离职） | `org:employee:read` `:create` | 员工 |
| `GET /api/platform/departments` · `POST` · `/{deptNo}` | 组织架构 | `org:dept:read` `:create` | 组织架构 |
| `GET /api/platform/roles` | 角色列表（**裸数组，不分页**） | `org:role:read` | 角色权限 |
| `POST /api/platform/roles` · `/{roleNo}` | 角色增改 | `org:role:update` | 角色权限 |
| `PUT /api/platform/roles/{roleNo}/permissions` | 角色授权（功能权限集） | `org:role:update` | 角色权限 |
| `GET /api/platform/permissions` | 权限目录（全局） | `org:role:read` | 角色权限 |
| `PUT /api/platform/data-scopes/{subjectType}/{subjectNo}` | **数据权限保存**（补 G7 缺口） | `org:role:update` | 数据权限（页内）|
| `GET /api/platform/audit-logs` | 操作审计 | `org:audit:read` | 操作审计 |
| `GET /api/platform/staff-performance` | 员工绩效（运维/客服/BD） | `org:performance:read` | 绩效报表 |

> **G7 数据权限只有 UI**：前端抽屉当前 `onSave` 只 invalidate、不调 API。`PUT /data-scopes/...` 是它落库的对端。

### 7.2 系统设置 · 接入与支付（菜单 2 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /internal/gw/vendors` | 供应商列表（**裸数组，不分页**） | `device:vendor:read` | 供应商接入 |
| `POST /internal/gw/vendors/{vendorCode}/config` | 供应商接入配置（driver 注册/密钥，出参掩码） | `device:vendor:config` | 供应商接入 |
| `GET /api/platform/payment-channels` · `POST` · `/{channelCode}` | 支付渠道（一页承载全渠道，密钥全掩码） | `system:payment_channel:read` `:update` | 支付渠道 |

### 7.3 系统设置 · 消息触达（菜单 3 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/platform/notify-templates` · `POST` · `/{templateNo}` | 通知模板（多通道 + 三语） | `system:notify_template:read` `:update` | 通知模板 |
| `GET /api/platform/notify-logs` | 发送记录（受控排序 `sentAt\|cost`；目标脱敏） | `system:notify_log:read` | 发送记录 |
| `GET /api/platform/notify-logs/stats` | **全量口径统计**（今日发送/失败/失败率/成本） | `system:notify_log:read` | 发送记录 |
| `GET /api/platform/notify-blacklist` · `POST` | 触达拉黑（全渠道，含 `ALL`） | `system:notify_blacklist:read` `:update` | 触达拉黑 |
| `POST /api/platform/notify-blacklist/{blockNo}/release` | **解除（软删除：置 `expireAt`，留记录）** | `system:notify_blacklist:update` | 触达拉黑 |
| `POST /internal/platform/notify/send` | 发送通知（域间调用；发前查黑名单） | 内部 | — |

### 7.4 系统设置 · 业务规则（菜单 3 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/platform/biz-rules` | 业务规则（**单例，三分区**：提现/预约/计费默认） | `system:biz_rule:read` | 业务规则 |
| `POST /api/platform/biz-rules` | 分区保存（`Partial<BizRules>`，**只合并传入分区**） | `system:biz_rule:update` | 业务规则 |
| `GET /api/platform/login-settings` · `POST` · `/{country}` | 登录设置（按国家；**至少留一种登录方式**校验） | `system:login_setting:read` `:update` | 登录设置 |
| `GET /api/platform/app-versions` · `POST` · `/{versionId}` | 应用版本（按平台 + 灰度比例 + 三语说明） | `system:app_version:read` `:update` | 应用版本 |
| `POST /api/platform/app-versions/{versionId}/rollback` | **回滚（软：`status=ROLLBACK` + `rolloutPercent=0`，留记录）** | `system:app_version:update` | 应用版本 |

> `biz-rules` 是**提现手续费口径的唯一来源**，财务侧不得另存。页面三个保存按钮 → 三次分区 POST，服务端按 `category` 落三行。

### 7.5 系统设置 · 基础字典（菜单 5 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/platform/dict-entries` · `POST` · `/{dictNo}` | 参数字典 | `system:dict:read` `:update` | 参数字典 |
| `GET /api/platform/regions` · `POST` · `/{regionId}` | 地区库 | `system:dict:read` `:update` | 地区库 |
| `GET /api/platform/banks` · `POST` · `/{bankCode}` | 银行字典（国家/币种/**IBAN 长度**，供提现校验） | `system:bank:read` `:update` | 银行管理 |
| `GET /api/platform/problems` · `POST` · `/{problemNo}` | 问题字典（三语 + **建议处置**） | `system:problem:read` `:update` | 问题管理 |
| `GET /api/platform/sys-params` · `POST` · `/{paramKey}` | 系统参数（心跳阈值/指令超时/计费默认） | `system:param:read` `:update` | 系统参数 |

### 7.6 系统设置 · 开放与市场（菜单 3 叶）
| Method Path | 用途 | 权限码 | 菜单叶 |
|---|---|---|---|
| `GET /api/platform/tax-settings` · `POST` · `/{country}` | 税率与发票（VAT/TRN + **价内/价外税** + 生效日期） | `system:tax:read` `:update` | 税率与发票 |
| `GET /api/platform/markets` · `POST` · `/{countryCode}` | 多国家市场（国家/币种/合规/开城） | `system:market:read` `:update` | 多国家市场 |
| `GET /api/platform/openapi-apps` · `POST` · `/{appNo}` | OpenAPI 应用（AppKey/限流） | `system:openapi:read` `:update` | OpenAPI 应用 |

### 7.7 租户（🔒 休眠口子，**不暴露到产品**）
| Method Path | 用途 |
|---|---|
| `GET/POST /internal/platform/tenants` | 租户开通/列表 —— **仅内部，无 UI**（ADR-011） |
| `GET/PUT /internal/platform/tenants/{tenantNo}/config` | 租户配置 |

---

## 八、`/mp` —— C端 BFF（38 端点，对齐 c-app 实际调用）

> **本章以 `c-app/src/api/http.ts` 的真实路径为准**。v1 文档里的 `/mp/user/login`、`/mp/nearby/sites/{siteNo}`、`/mp/user/wallet/transactions` 均与代码不符，v2 已改（见 §九）。
> 末列为 [C端功能清单](../requirements/C端功能清单.md) 的子功能编号；`✅` = c-app 已在调，`🆕` = 清单要求但 c-app 未接。

### 8.1 账户与登录（C-AC，模块 1/16）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `POST /mp/auth/otp` | 发送手机 OTP（返回 `cooldown`，频控） | C-AC-01 | ✅ |
| `POST /mp/auth/login` | 登录建户（`grantType` = `phone_otp\|password\|apple\|google\|wechat_miniapp`） | C-AC-01 | ✅ |
| `POST /mp/auth/register` | 注册 + 登录 | C-AC-01 | ✅ |
| `POST /mp/auth/password/reset` | OTP 重置密码 | C-AC-01 | ✅ |
| `POST /mp/auth/password/change` | 修改密码（已登录） | C-ME-05 | 🆕 |
| `POST /mp/auth/token/refresh` | 会话续期 | C-AC-03 | 🆕 |
| `POST /mp/auth/logout` | 登出 | C-AC-03 | ✅ |
| `GET /mp/user/profile` | 我的资料 + 信用分 + 免押状态 | C-AC-04 / C-ME-02 | ✅ |
| `POST /mp/user/profile` | 修改昵称/头像/邮箱 | C-ME-02 | ✅ |
| `POST /mp/user/logoff` | 账号注销（PDPL 冷静期 → 到期清除） | C-AC-05 | 🆕 |
| `GET /mp/user/agreements` | 用户/隐私协议（ar/en/zh） | C-AC-06 | 🆕 |
| `POST /mp/user/consent` | 同意/撤回留痕（PDPL 可举证） | C-AC-06 | 🆕 |
| `POST /mp/user/data-export` | 个人数据导出（PDPL） | C-AC-06 | 🆕 |

> 登录策略与 unionid 归并见 [TDD-认证鉴权-实现细节](../technical/TDD-认证鉴权-实现细节.md)。**登录开关按国家读 `sys_login_setting`**（§7.4），端上不硬编码可用方式。

### 8.2 找柜与地图（C-MAP，模块 2）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `GET /mp/nearby/cabinets` | 附近网点（`lat/lng/keyword/returnable`）。出参 `NearbyCabinetVO`；给了 `lat/lng` 按距离升序，**算不出的距离/坐标回 null 不回 0** | C-MAP-01/02/05 | ✅ |
| `GET /mp/nearby/cabinets/{cabinetNo}/availability` | 借出前可借校验 + 价格/押金/免押额 | C-RT-02 | ✅ |
| `GET /mp/sites/{siteNo}` | 网点详情（地址/营业时间/柜机列表/价格/是否收藏） | C-MAP-03 | ✅ |
| `GET /mp/user/favorites` | 我的收藏门店 —— 与找柜**同一张门店卡片**（`NearbyCabinetVO`），可带 `lat/lng` 算距离 | — | ✅ |
| `POST /mp/user/favorites/{siteNo}` | 收藏 / 取消收藏（toggle） | — | ✅ |

### 8.3 借还主流程（C-RT / C-US / C-RE，模块 3/6/7）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `POST /mp/trade/orders/rent` | 扫码借出（`cabinetNo/useFreeDeposit/couponNo`；幂等） | C-RT-03 | ✅ |
| `GET /mp/trade/orders` | 我的订单（`status/keyword` 分页，属主过滤） | C-BL-01/05 | ✅ |
| `GET /mp/trade/orders/ongoing` | **进行中订单**（首页悬浮入口） | C-US-04 | ✅ |
| `GET /mp/trade/orders/{orderNo}` | 订单详情（实时计费 + `fees[]` + `timeline[]`） | C-BL-02/03 · C-US-01 | ✅ |
| `POST /mp/trade/orders/{orderNo}/buyout` | 买断充电宝 | C-US-02 | ✅ |
| `POST /mp/trade/deposit/free` | 免押授权（卡预授权冻结 → `{authNo, frozen}`） | C-DF-01 | ✅ |
| `POST /mp/trade/pay` | 收银台（`scene` = `rent\|deposit\|recharge\|membership`） | C-PAY-01 · C-DF-03 | ✅ |
| `GET /mp/trade/orders/{orderNo}/pay-status` | 支付结果轮询兜底（回调为准，此为降级） | C-PAY-03 | 🆕 |

> **归还不走 C 端主动调用**：设备归还事件 → `POST /internal/trade/orders/{no}/return`（停计费 → capture → 用券 → 结算）。C 端只轮询/推送刷新详情（C-RE-01/02）。

### 8.4 钱包与押金（C-WA，模块 9）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `GET /mp/user/wallet` | 钱包总览（余额/赠金/押金/冻结） | C-WA-01 · C-DF-04 | ✅ |
| `GET /mp/user/wallet/txns` | 钱包流水（分页） | C-WA-05 | ✅ |
| `GET /mp/user/recharge-packages` | 充值套餐（按用户所在市场过滤） | C-WA-02 | ✅ |
| `POST /mp/user/recharge` | 按套餐充值并即时结算。**只收 packageNo，金额由服务端按套餐算** | C-WA-02 | ✅ |
| `POST /mp/user/deposit/refund` | 退押金申请（无进行中订单/无欠费才可退） | C-WA-04 | 🆕 |

### 8.5 会员 · 券 · 营销（C-MB / C-CP / C-SH，模块 10/11/15）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `GET /mp/user/membership` | 会员方案 + 我的会员（有效期/剩余次数/权益） | C-MB-01/03 | ✅ |
| `POST /mp/user/membership` | 购买会员/次卡 | C-MB-01 | 🆕 |
| `DELETE /mp/user/membership/auto-renew` | 取消自动续费 | C-MB-04 | 🆕 |
| `GET /mp/user/coupons` | 我的券包（**已领到手的券实例**，PageResult） | C-CP-02 | ✅ |
| `GET /mp/user/coupons/claimable` | 领券中心（可领的**券模板**，带 `claimed`/剩余量） | C-CP-01 | ✅ |
| `POST /mp/user/coupons/{couponNo}/claim` | 领券（幂等 + 库存校验）。路径上传的是**模板号** `tplNo`，不是券包里的 `couponNo` | C-CP-01 | ✅ |
| `GET /mp/notice` | 运营公告（广播，三语三列由端取；**无已读态**，已读属站内信） | C-CP-04 | ✅ |
| `GET /mp/user/invite` | 邀请码/邀请链接 | C-SH-01 | 🆕 |
| `POST /mp/user/invite/bind` | 新用户绑定邀请人（归因/反作弊） | C-SH-03 | 🆕 |

### 8.6 报障与客服（C-CS，模块 12）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `POST /mp/user/report` | 自助报障 → `cs_ticket`。入参 `problemNo` 取自 `/mp/faq`，**分流全靠它** | C-CS-01 | ✅ |
| `GET /mp/user/reports` | 我的报障列表 | C-CS-02 | ✅ |
| `GET /mp/user/reports/{reportNo}` | 报障进度/结果（联动工单/退款状态） | C-CS-02/03 | ✅ |
| `GET /mp/faq` | 帮助中心 FAQ（读 `md_problem`，三语）。**同时是报障问题下拉的唯一来源** | C-CS-05 | ✅ |
| `GET /mp/cs/session` · `POST /mp/cs/messages` | 客服会话 | C-CS-04 | 🆕 |

> **报障问题类型来自 `md_problem` 字典**（运营端「问题管理」维护），端上不硬编码；字典的 `suggestedAction` 决定报障提交后是自助解决、转工单、转退款还是转人工。

### 8.7 发票（C-IV，模块 13）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `GET/POST /mp/user/invoice-titles` | 抬头管理（个人/企业 + VAT/TRN） | C-IV-02 | 🆕 |
| `POST /mp/user/invoices` | 开票申请（选已结算订单，防重复） | C-IV-01 | 🆕 |
| `GET /mp/user/invoices` | 发票记录 / 下载 / 重发 | C-IV-03 | 🆕 |

### 8.8 消息触达与设置（C-MS / C-ME，模块 14/16）
| Method Path | 用途 | 编号 | 状态 |
|---|---|---|---|
| `POST /mp/user/push-token` | 注册 Push token（APNs/FCM/UniPush） | C-MS-01 | 🆕 |
| `GET /mp/user/messages` | 站内消息中心（历史/已读未读） | C-MS-03 | ✅ |
| `POST /mp/user/messages/{messageNo}/read` | 标记已读 | C-MS-03 | ✅ |
| `GET/PUT /mp/user/notify-prefs` | 通知偏好（分类开关/免打扰时段/语言） | C-MS-04 | 🆕 |
| `GET /mp/app/version` | 版本检查（读 `sys_app_version`，按平台 + 灰度） | C-ME-06 | ✅ |

---

## 九、`/gw` `/internal` `/notify` `/openapi` —— 南向与域间

### 9.1 access-gateway（南向接入 · 独立进程）
| Method Path | 用途 |
|---|---|
| `POST /gw/vendors/{vendor}/callback` | 供应商 HTTP 回调入口（验签 → `driver.parseWebhook` → 发 `DeviceEvent`） |
| `POST /internal/gw/commands` | 业务域下发统一指令（`EJECT_SLOT` 等，返回 `commandId`） |
| `GET /internal/gw/commands/{commandId}` | 指令状态查询（幂等/结果） |
| `GET /internal/gw/devices/{sn}/online` | 设备在线态查询 |
| `GET /internal/gw/vendors` · `POST /internal/gw/vendors/{code}/config` | 供应商接入配置（driver 注册/密钥） |

> TCP/MQTT 上行不经 REST：Netty/EMQX 接入 → `driver.decode` → 统一 `DeviceEvent` → `DomainEventPublisher`（ops/trade 消费）。指令下发内部走 `/internal/gw/commands`，网关经 `driver.encode/invoke` 发设备。

### 9.2 开放平台（P1，首期范围待定）
| Method Path | 用途 |
|---|---|
| `GET /openapi/devices/{cabinetNo}/status` | 设备状态查询 |
| `GET /openapi/orders/{orderNo}` | 订单查询 |
| `GET /openapi/settlements` | 对账/结算查询 |

---

## 十、v1 → v2 冲突裁决（文档 vs 代码，一律以代码为准）

| # | v1 文档 | 代码实际 | v2 裁决 |
|---|---|---|---|
| 1 | `POST /mp/user/login` | `POST /mp/auth/login`（c-app + 后端 `ConsumerAuthController` 双向一致） | **取 `/mp/auth/*`**；认证类端点全部归 `/mp/auth`，与业务端点 `/mp/user/*` 分离 |
| 2 | `GET /mp/nearby/sites/{siteNo}` | `GET /mp/sites/{siteNo}`（c-app 在调） | **取 `/mp/sites/{siteNo}`**；`/mp/nearby/*` 只承载「按位置搜」，站点详情是资源本身 |
| 3 | 借出校验走 `GET /internal/ops/cabinets/{no}/availability` | c-app 直调 `GET /mp/nearby/cabinets/{no}/availability` | **两个都要**：`/mp` 是 C 端读（含价格/免押额，走 BFF 聚合），`/internal` 是 trade 下单时的**权威二次校验**（防端上绕过） |
| 4 | `GET /mp/user/wallet/transactions` | `GET /mp/user/wallet/txns` | **取 `/txns`** |
| 5 | 订单详情返回未定义 | 后端返 `{feeAmount, rentStartAt}` 扁平；c-app 期望 `{amount, startAt, fees[], timeline[]}` | **取 c-app 形状**：`/mp` 是 BFF，必须返回端上直接可渲染的结构（含费用拆解与状态时间线）；`/api/trade` 侧保留扁平结构给运营端 |

### 10.1 v1 完全缺失、c-app 已在调的 6 个端点（v2 已补入 §八）
`GET /mp/notice` · `POST /mp/auth/register` · `POST /mp/auth/password/reset` · `GET|POST /mp/user/favorites` · `GET /mp/trade/orders/ongoing` · `POST /mp/trade/orders/{no}/buyout`

### 10.2 v2 新增的运营端端点族（v1 无，对应对标补齐的菜单）
告警管理 9 · 订单售后（预约/投诉/退款/押金/免费）16 · 用户风控与资产（黑白名单/充值）13 · 系统设置（银行/问题/版本/登录/规则/发送记录/拉黑/税率/市场）24 · 场地拓展（Onboarding/生命周期/CRM/坪效）9 · 代理（账号/划拨/分润/绩效）10 · 报表 7 · 客服 7

---

## 十一、借还主流程端点编排

```
借出:  POST /mp/trade/orders/rent
        → GET /internal/ops/cabinets/{no}/availability   （权威二次校验，不信端上）
        → POST /mp/trade/deposit/free（免押）| POST /mp/trade/pay（押金降级）
        → POST /internal/gw/commands (EJECT_SLOT)
        ← DeviceEvent RENT_CONFIRMED → 订单 IN_USE → 借出成功页
使用:  GET /mp/trade/orders/{orderNo}（实时计费/封顶）
        GET /mp/trade/orders/ongoing（首页悬浮）
归还:  DeviceEvent RETURNED
        → POST /internal/trade/orders/{no}/return
        → 停计费 → capture 请款 → 用券 → share_record → 通知
异常:  POST /mp/user/report          （C端自助报障 → cs_ticket）
        → POST /api/ops/cs/tickets/{no}/work-order   （转工单，幂等）
        | POST /api/ops/cs/tickets/{no}/refund       （转退款申请，幂等）
        → POST /api/trade/refunds/{no}/audit         （财务审批，驳回必填原因）
        → POST /internal/trade/refund                （经 PaymentPort → nearpay）
        ← POST /notify/refund/nearpay                （回调幂等 → 回填 psgTxnNo）
告警:  DeviceEvent FAULT → POST /internal/ops/alarms
        → 命中 dev_alarm_code.autoWorkOrder → 自动开单（source_ref 幂等）
        → 命中 dev_alarm_rule → 发通知（避开 quiet 窗口；超时按 escalateMinutes 升级）
```

---

## 十二、覆盖核对

| 面 | 应覆盖 | 本文端点 | 缺口 |
|---|---|---|---|
| 运营端菜单叶 | 98（其中 6 为跨域深链，不产生新端点） | 92 叶有端点 | 0 |
| 运营端页内动作 | 18 | 18 | 0（G1/G2/G3/G6/G7 的端点已在本文定义，待实现）|
| 代理端自助 | 5 | 5（复用 + AGENT 数据范围） | 0 |
| C端子功能 | 17 模块 · 77 子功能 | 38 端点覆盖 | 0（蓝牙 C-BT 走网关，无独立端点）|

---

## 十三、待确认

1. **BFF 边界**：`/mp` 与 `/api` 是否各自独立 BFF 应用？编排（指令下发/支付）放 BFF 还是域？**本文倾向编排在域、BFF 仅聚合与形状适配**（§十 裁决 5 即此原则的体现）。
2. **南向指令确认方式**：`/internal/gw/commands` 同步返 `commandId` + 异步事件确认，业务侧**等事件**（本文）还是轮询？
3. **`/api/ops/marketing/notices` 的前缀归属**：公告本质是营销内容，却落在 `/api/ops`（前端现状）。是保持现状还是迁到 `/api/user/notices`？迁移需前后端同步改，**建议保持现状并在此备注**。
4. **OpenAPI 首期开放范围**：设备状态 / 订单查询 / 对账 三选几？
5. **`Result` 字段名 `msg` vs `message`**（§1.1）：前后端目前靠兼容层，是否借这次统一为 `msg` 并清掉兼容？
6. **报表导出的统一口**：本文定义 `GET /api/ops/reports/{key}/export`，但设备台账/订单导出各自有 `export`。是否收敛为一个通用导出网关（`POST /api/export` + 任务化）？大数据量导出必须异步，**建议 P2 任务化**。
7. C端 `🆕` 标记的 21 个端点是清单要求但 c-app 未接 —— 是端点先行（后端先建）还是等前端排期？**建议随 C端 P1 批次一起**。
