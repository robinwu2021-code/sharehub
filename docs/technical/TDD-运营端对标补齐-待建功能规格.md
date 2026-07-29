# TDD · 运营端对标补齐 —— 16 项待建功能规格

状态：**规格已定，待开发**（2026-07-29）
定位：**开发锚点**。本文是 [运营端功能清单 §三](../requirements/运营端功能清单.md) 中 16 个 `⬜ 待建` 项的字段级规格——**先有本文，再写代码**。
关联：[补齐清单](../competitor/jiandianyun/05-补齐清单.md)（判定与落位）· [菜单对照表](../competitor/jiandianyun/04-菜单对照表.md) · [导航结构优化](TDD-运营端导航结构优化-按机构角色.md)

## 0. 开发约定（每一项都适用）

| 项 | 约定 |
|---|---|
| 样板 | **`app/alarms/page.tsx` 是最新样板**（TabHeader + Toolbar + DataTable + FormDrawer + useQuery/useMutation）。勿自创写法 |
| 数据层 | `lib/types.ts` 加类型 → `lib/mock/db.ts` 加 mock + `upsert`/`nextNo` → `lib/api/{contract,mock,http}.ts` 三件套同步。**追加式修改**，勿重排他人内容 |
| 菜单 | **`lib/nav.ts` 叶子已存在且标 `soon: true`**；实现完成后**删掉该叶的 `soon`**，否则菜单仍灰显 |
| tab key | 必须与 nav 深链的 `?tab=` 完全一致（下表「入口」列） |
| 分期 | 页面 `TABS` 里该 tab 的 `phase` 必须与 nav 叶子、与功能清单 §三「阶段」列三处一致 |
| 权限 | 写操作一律 `useCan()` 门控；无权限时**显式提示"仅可查看"**，不要静默隐藏（沿用退款记录页做法）|
| mock 数据 | 必须引用**现有** mock 的真实编号（机柜 `CAB1000+`、站点名、订单 `ORD5000xx`、用户 `U30xx`、代理 `AGT00x`），币种 `AED`；**禁止写入任何真实密钥**，占位用 `****` |
| 验收 | `npx tsc --noEmit` + `npx vitest run` + `npm run build` 三绿，并实机核对字段 |
| 回填 | 完成后回填 功能清单 §三 该行「前端」列 + [实现状态总表](实现状态总表.md) 变更日志 |

---

## 1. 设备日志 · `/devices?tab=logs` · 阶段 2 · P1

**对标**：竞品「充电桩日志」。**我们更清晰**：竞品只有设备上报日志；我们做**双流合一**——「指令下发」与「设备上报」在同一时间轴，排障时因果可见。

| 字段 | 类型 | 说明 |
|---|---|---|
| `logNo` | string | 日志号 |
| `cabinetNo` | string | 机柜号 |
| `stream` | `COMMAND` \| `REPORT` | **双流标识**（下发 / 上报）|
| `direction` | `DOWN` \| `UP` | 方向，与 stream 对应 |
| `eventType` | string | 如 `EJECT` / `HEARTBEAT` / `SLOT_STATE` / `FW_UPGRADE` |
| `payload` | string | 报文摘要（JSON 字符串截断展示，行内 `...` 省略）|
| `vendorCode` | string | 厂商 |
| `occurredAt` | string | 时间 |
| `result` | `OK` \| `TIMEOUT` \| `FAILED` | 结果 |

交互：只读列表 + 搜索（机柜号/事件类型）+ **stream 筛选**（全部/下发/上报）+ 时间倒序。行点击展开完整 payload（Drawer）。

## 2. 设备编码 · `/devices?tab=codes` · 阶段 2 · P1

**对标**：竞品「充电桩编码」。**我们更清晰**：按**批次 + 供应商**归集，竞品是平铺列表。

| 字段 | 类型 | 说明 |
|---|---|---|
| `batchNo` | string | 贴码批次号 |
| `vendorCode` | string | 供应商 |
| `codeType` | `QR` \| `SN` | 二维码 / 出厂序列号 |
| `rangeStart` / `rangeEnd` | string | 编码区间 |
| `total` / `bound` | number | 总数 / 已绑定数（列表显示 `已绑定/总数` + 进度）|
| `producedAt` | string | 生产日期 |
| `status` | `PENDING` \| `PARTIAL` \| `BOUND` \| `VOID` | 批次状态 |

交互：可增改（FormDrawer）。**校验**：`rangeEnd ≥ rangeStart`；`bound ≤ total`。

## 3. 预约订单 · `/orders?tab=reservations` · 阶段 2 · P1

**对标**：竞品「预约订单」（电车预约充电桩）。**充电宝映射**：预约取宝 / 预约还位（热门点位高峰占位）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `reservationNo` | string | 预约号 |
| `userNo` | string | 用户 |
| `type` | `BORROW` \| `RETURN` | **预约取宝 / 预约还位** |
| `siteNo` / `siteName` | string | 目标站点 |
| `cabinetNo` | string \| null | 指定机柜（可空=站点级）|
| `reservedFrom` / `reservedTo` | string | 预约时段 |
| `holdFee` | number | 占位费（超时未取产生）|
| `status` | `PENDING` \| `FULFILLED` \| `EXPIRED` \| `CANCELLED` | |
| `orderNo` | string \| null | 履约后关联的租借订单 |

交互：只读列表 + 状态筛选 + 「取消预约」（PENDING 才可，`order:order:update`）。
**依赖**：占位费规则在 §11 业务规则页配置。

## 4. 免费订单 · `/orders?tab=free` · 阶段 2 · P1

**对标**：竞品「免费订单」。与 §7 免费用户白名单联动。

| 字段 | 类型 | 说明 |
|---|---|---|
| `orderNo` | string | 复用租借订单号 |
| `userNo` / `nickname` | string | 用户 |
| `whitelistReason` | string | 免费来源（内测/VIP/BD 演示/商户自用）|
| `waivedAmount` | number | 减免金额 |
| `siteName` / `cabinetNo` | string | |
| `startedAt` / `endedAt` | string | |
| `duration` | number | 时长（分）|

交互：只读列表 + 来源筛选。**统计条**：本月免费单数 / 累计减免金额（放页头，便于成本管控）。

## 5. 分润统计 · `/finance?tab=summary` · 阶段 2 · P1

**对标**：竞品「佣金统计」按运营商/商户切两套表。**我们更清晰**：**一张表按主体维度切换**，少一次跳转。

| 字段 | 类型 | 说明 |
|---|---|---|
| `dimension` | `VENUE` \| `AGENT` | **顶部维度切换器**（不是两张表）|
| `payeeNo` / `payeeName` | string | 分成方 |
| `period` | string | 统计周期 `2026-07` |
| `orderCount` | number | 订单数 |
| `gmv` | number | 交易额 |
| `shareAmount` | number | 分润额 |
| `settledAmount` | number | 已结算 |
| `pendingAmount` | number | 待结算（= 分润 − 已结算）|

交互：只读 + 维度切换 + 周期选择 + 排序（按分润额）。行点击 → 深链到分润明细并带 payee 筛选。

## 6. 充值订单 · `/finance?tab=recharges` · 阶段 3 · P1

| 字段 | 类型 | 说明 |
|---|---|---|
| `rechargeNo` / `userNo` | string | |
| `packageNo` | string \| null | 套餐（可空=自定义金额）|
| `payAmount` / `giftAmount` / `creditAmount` | number | 实付 / 赠送 / 到账 |
| `channelCode` | string | 支付渠道（关联 §系统设置·支付渠道）|
| `status` | `PENDING` \| `PAID` \| `FAILED` \| `REFUNDED` | |
| `paidAt` / `psgTxnNo` | string | |

交互：只读 + 状态筛选。归「用户账」分组，与钱包同主体。

## 7. 免费用户白名单 · `/users?tab=whitelist` · 阶段 2 · P1

**对标**：竞品「免费用户」（放订单域）。**我们更清晰**：归**用户域**（它本质是用户属性），并强制标注用途。

| 字段 | 类型 | 说明 |
|---|---|---|
| `userNo` / `nickname` / `phone` | string | |
| `reason` | `INTERNAL_TEST` \| `VIP` \| `BD_DEMO` \| `MERCHANT_SELF` | **必填用途**（枚举，非自由文本）|
| `quotaType` | `UNLIMITED` \| `TIMES` \| `AMOUNT` | 额度类型 |
| `quotaValue` | number | 额度（次数/金额）|
| `usedValue` | number | 已用 |
| `validFrom` / `validTo` | string | 有效期 |
| `grantedBy` | string | 授予人（审计用）|
| `status` | `ACTIVE` \| `EXPIRED` \| `REVOKED` | |

交互：可增改 + 「撤销」按钮（`user:risk:update`）。**校验**：`quotaType != UNLIMITED` 时 `quotaValue > 0`。

## 8. 充值套餐 · `/users?tab=recharge` · 阶段 3 · P1

**对标**：竞品「充值套餐」（充值+赠送）。**我们更清晰**：加**有效期**与**适用市场**（多国家场景）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `packageNo` / `name` | string | |
| `payAmount` / `giftAmount` | number | 充值金额 / 赠送金额 |
| `currency` | string | AED 等 |
| `markets` | string | **适用市场**（`AE,SA`，关联多国家市场）|
| `validDays` | number | **赠送金额有效期（天）** |
| `sortNo` | number | 排序 |
| `status` | `ENABLED` \| `DISABLED` | |

交互：可增改。列表显示「充 X 送 Y」合并列，更直观。

## 9. 发送记录 · `/system?tab=notify-log` · 阶段 2 · P1

**对标**：竞品「短信记录」。**我们更清晰**：**全渠道**（短信/邮件/Push/WhatsApp）而非仅短信，且带**计费**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `logNo` | string | |
| `channel` | `SMS` \| `EMAIL` \| `PUSH` \| `WHATSAPP` | |
| `templateNo` | string | 关联通知模板 |
| `target` | string | 目标（手机/邮箱/token，**脱敏中间位**）|
| `scene` | string | 场景（OTP/订单完成/告警…）|
| `sentAt` | string | |
| `status` | `SENT` \| `FAILED` | |
| `failReason` | string \| null | |
| `cost` | number | **单条计费**（OTP 是真金白银）|

交互：只读 + 渠道/状态筛选。**页头统计**：今日发送量 / 失败率 / 今日成本。

## 10. 触达拉黑 · `/system?tab=notify-blacklist` · 阶段 2 · P1

**对标**：竞品「短信拉黑」。**我们更清晰**：全渠道拉黑。

| 字段 | 类型 | 说明 |
|---|---|---|
| `target` | string | 号码/邮箱 |
| `channel` | `SMS` \| `EMAIL` \| `PUSH` \| `ALL` | |
| `reason` | `USER_OPT_OUT` \| `HARD_BOUNCE` \| `ABUSE` \| `MANUAL` | 原因枚举 |
| `blockedAt` / `blockedBy` | string | |
| `expireAt` | string \| null | 可空=永久 |

交互：可增（手动拉黑）+ 「解除」按钮。

## 11. 业务规则 · `/system?tab=rules` · 阶段 2 · P1

**对标**：竞品拆成「提现设置」「预约设置」「充电设置」三个菜单。**我们更清晰**：**合并为一页分区表单**，减少菜单噪音。

三个分区（同一页面，Card 分区，各自保存）：

| 分区 | 字段 |
|---|---|
| **提现规则** | 最低提现额 · 手续费率 · 手续费封顶 · 结算周期(T+N) · 单日限额 · 是否需人工审批 |
| **预约规则** | 预约时长上限(分) · 提前预约上限(小时) · 超时未取占位费(元/分) · 单用户同时预约上限 |
| **计费默认值** | 默认免费时长 · 默认计费单位 · 默认日封顶 · 默认买断价 · 超时判定阈值 |

交互：表单 + 分区保存，`system:*` 门控。**校验**：数值非负；手续费率 0~1。

## 12. 登录设置 · `/system?tab=login` · 阶段 2 · P1

**对标**：竞品「登录设置 / 第三方登录」两个菜单。**我们更清晰**：合并，且**按国家可配**（MENA 多国）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `country` | string | 国家码（`AE`/`SA`/`*`=默认）|
| `otpEnabled` / `passwordEnabled` | boolean | |
| `appleEnabled` / `googleEnabled` | boolean | |
| `otpExpireSec` / `otpDailyLimit` | number | OTP 有效期 / 单用户日限 |
| `forceRealName` | boolean | 是否强制实名 |

交互：按国家分行的列表 + 编辑抽屉。

## 13. 应用版本 · `/system?tab=app-version` · 阶段 2 · P1

**对标**：竞品「应用版本」（单一版本）。**我们更清晰**：**按平台分 + 灰度比例**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `versionNo` | string | 如 `1.4.2` |
| `platform` | `IOS` \| `ANDROID` \| `H5` | **按平台分** |
| `buildNo` | number | |
| `releaseNote` / `releaseNoteEn` / `releaseNoteAr` | string | **三语更新说明** |
| `forceUpdate` | boolean | 强更 |
| `minSupported` | string | 最低支持版本 |
| `rolloutPercent` | number | **灰度比例 0~100** |
| `downloadUrl` | string | |
| `status` | `DRAFT` \| `RELEASED` \| `ROLLBACK` | |

交互：可增改 + 「回滚」按钮。**校验**：`rolloutPercent` 0~100；`forceUpdate` 为真时必须填 `minSupported`。

## 14. 银行管理 · `/system?tab=banks` · 阶段 2 · P1

**对标**：竞品「银行管理」。**我们更清晰**：带**国家/币种**（MENA 多国）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `bankCode` / `bankName` / `bankNameEn` | string | |
| `country` | string | 国家码 |
| `currency` | string | 币种 |
| `swiftPrefix` | string | SWIFT 前缀 |
| `ibanLength` | number | **IBAN 长度**（用于提现账户校验）|
| `status` | `ENABLED` \| `DISABLED` | |

交互：可增改。被提现审核页的收款方选择引用。

## 15. 问题管理 · `/system?tab=problems` · 阶段 2 · P1

**对标**：竞品「问题管理」（FAQ）。**我们更清晰**：**三语 + 关联建议处置**，直接喂给 C 端报障与客服。

| 字段 | 类型 | 说明 |
|---|---|---|
| `problemNo` | string | |
| `category` | `RENT` \| `RETURN` \| `BILLING` \| `DEVICE` \| `ACCOUNT` \| `OTHER` | |
| `title` / `titleEn` / `titleAr` | string | **三语**（C 端报障选项直接取这里）|
| `answer` / `answerEn` / `answerAr` | string | 三语标准答复 |
| `suggestedAction` | `SELF_SERVICE` \| `TO_WORKORDER` \| `TO_REFUND` \| `TO_CS` | **关联建议处置** |
| `sortNo` / `status` | number / enum | |

交互：可增改。**下游依赖**：C 端报障页的问题类型下拉、客服受理页的快捷答复。

## 16. 税率与发票 · `/system?tab=tax` · 阶段 3 · P1

**对标**：竞品「发票设置」（仅税率）。**我们更清晰**：税率配置 + 与现有「发票」列表互补。

| 字段 | 类型 | 说明 |
|---|---|---|
| `country` | string | 国家码 |
| `taxName` | string | 如 `VAT` |
| `ratePercent` | number | 税率 % |
| `trn` | string | 税号（UAE TRN）|
| `invoiceTitle` | string | 默认开票抬头 |
| `includedInPrice` | boolean | **价内税 / 价外税**（影响计费展示）|
| `effectiveFrom` | string | 生效日期 |

交互：按国家分行 + 编辑抽屉。

---

## 17. 附：4 个 `◐ 部分实现` 项的补完规格 —— ✅ **已实现（批次 B1，2026-07-29）**

| 项 | 位置 | 补了什么 |
|---|---|---|
| **提现审核** | `/finance?tab=withdrawals` | 补 4 列：`fee`(手续费) · `auditorName`(审批人) · `auditedAt` · `rejectReason` —— **资金审批合规底线**（对标发现我们缺）|
| **钱包** | `/users?tab=wallets` | 补用户价值画像：`orderCount` / `orderAmount` / `rechargeCount` / `rechargeAmount`（对标 D5）|
| **员工** | `/employees?tab=employees` | 补新增/编辑抽屉（原只读）|
| **多国家市场** | `/system?tab=markets` | 补增改（原纯只读）|

### 17.1 实施中产生的决策（规格未覆盖 → 已回灌，后续批次沿用）

> 「先补文档再开发」的闭环：开发者遇到规格没写的地方，**先记录再回灌**，不让口径散落在代码里。

| # | 决策 | 结论 |
|---|---|---|
| 1 | **资金审批一律用抽屉，不用行内按钮** | 已定为规范。理由：驳回必填原因，行内放不下；且与 `/orders?tab=refunds` 的退款审批形态统一 |
| 2 | 提现列表加 **「实际到账」列**（= 金额 − 手续费）| 保留。避免审批人心算，属"信息更清晰"的正例 |
| 3 | **手续费口径 0.6% / 下限 2 AED** | ⚠️ **仅为 mock 占位，非业务口径**。正式实现时**必须改为读 §11 业务规则页的「提现手续费率 / 手续费封顶」配置**，不得硬编码——否则两处口径会漂移 |
| 4 | `Employee` 新增 `email` 字段 | 保留。运营端通知与账号找回需要；**后端 `iam_employee` 建表时须包含该列**（[db-design](db-design.md) 待同步）|
| 5 | 员工写操作复用 `org:employee:update`，未区分 create | 保留。RBAC SSOT 已定义 `:create`/`:update`/`:delete` 三码，**前端暂统一用 `:update`**；若将来要区分"能改不能建"再拆 |
| 6 | 新造权限码 `system:market:update` | ✅ **已补进 [功能权限清单 §13](../requirements/功能权限清单.md)**，连同本 TDD 其余 11 项待建功能的权限码一并补齐。**规则：新造权限码必须先补 RBAC SSOT 再用** |
| 7 | `auditWithdrawal` 契约变更：返回值 `{ok:true}` → `Withdrawal`，签名加 `rejectReason?`/`auditorName?` | 保留（与 `auditRefund` 一致）。⚠️ **属破坏性契约变更**，后端 `POST /api/trade/withdrawals/{no}/audit` 实现时须按新签名，[api/README](../api/README.md) 待同步 |
| 8 | 钱包画像取真实关联数据（按用户号聚合其订单）而非随机数 | 已定为规范：**mock 数据必须跨页自洽**，同一用户在不同页的口径要一致 |
| 9 | 搜索字段随新增列扩展（提现搜审批人、员工搜工号/手机/邮箱）| 已定为规范：新增可见列时同步扩搜索域 |
| 10 | `countryCode` 等主键字段：新增必填 + 自动大写，编辑时只读 | 已定为规范。`FormDrawer` 无校验能力 → 在 `onSubmit` 拦截并 `notify.error` |

---

## 18. 建议开发批次

| 批次 | 内容 | 理由 |
|---|---|---|
| ~~**B1**~~ ✅ | ~~§17 四个 `◐` 补完~~ | **已完成 2026-07-29**，决策回灌见 §17.1 |
| **B2** | §9 发送记录 · §10 触达拉黑 · §15 问题管理 | 都是运营**当下就要用**的治理能力，且互相关联（问题管理喂 C 端报障）|
| **B3** | §11 业务规则 · §12 登录设置 · §13 应用版本 · §14 银行管理 | 配置类，集中在系统设置页，可一次做完 |
| **B4** | §1 设备日志 · §2 设备编码 · §3 预约订单 · §4 免费订单 · §7 白名单 | 业务类，依赖前面的规则配置 |
| **B5** | §5 分润统计 · §6 充值订单 · §8 充值套餐 · §16 税率发票 | 阶段 3 为主，可延后 |

## 19. 变更记录

| 日期 | 内容 |
|---|---|
| 2026-07-29 | 建档：16 项待建 + 4 项补完的字段级规格，作为开发锚点 |
