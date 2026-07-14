# 功能清单对齐 · 参考(充电桩)Excel × 现状(充电宝)ops-web

> 状态：对齐分析 · 生成 2026-07-14 · 来源 `ref/功能清单.xlsx`（90 页 / 14 顶层模块）
> 对照物：`ops-web/lib/nav.ts` 导航 SSOT（基线 14 域 / 27 模块 / 130 叶，本轮补至 28 模块 / 134 叶）
> 用途：把参考功能清单逐行映射到现状 ops-web，识别「已覆盖 / 需补 / 缺失 / N/A / 反超」，给出对齐结论与落地。

---

## 一、前提：两者是「同构但不同域」

- **参考 Excel（`ref/功能清单.xlsx`）描述的是「充电桩(EV Charging)」运营后台**：通篇充电桩/充电枪、枪态（Type2/CCS/GBT/CHAdeMO）、SOC、kW·h 电量、OCPP 报文、OCPI/EMSP 平台漫游、车辆/车牌管理。
- **现状 ops-web 是「共享充电宝(Power Bank)」运营后台**：柜机/充电宝/仓位/电池台账/归还，与 `ref/充電寶專案_V4.pdf` 一致。
- 二者是**同一套「共享资产租赁 + 支付 + 多级运营商/商户分成结算」中台的两个行业孪生**，结构 1:1 高度重合。下述对齐 = 把充电桩术语**结构性映射**到充电宝 ops-web。术语对照约定：
  - 充电桩 ≈ 柜机（cabinet）；充电枪 ≈ 仓位（slot）；SOC/kW·h ≈ 电量（N/A）；站场 ≈ 站点/点位；充电订单 ≈ 租借订单。

---

## 二、对齐总览

| 覆盖情况 | 数量（约） | 含义 |
|---|---|---|
| ✅ 已覆盖 | 58 | ops-web 有对应叶子/Tab |
| 🟡 部分/需补 | 16 | 模块在，但该子页缺或仅雏形 |
| ❌ 缺失（需补） | 8 | 无对应，且对充电宝有意义 |
| ⛔ N/A（EV 专属） | 8 | 充电宝业务不适用，明确排除 |
| ➕ 反超 | — | ops-web 独有、Excel 无 |

结论：**结构对齐约 90%**；真实需补集中在「平台设置」。

---

## 三、逐模块对齐（Excel 模块 → ops-web）

### 1 概览/全局
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 首页概览 | 经营看板 `/` | ✅ |
| 数据仪表盘（平台/站场/设备切换） | 经营看板 / 数据报表 | 🟡 缺切换维度 |
| 数据大屏 | `reports?tab=screen` 实时大屏 | ✅ |

### 2 运营管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 站场概览 / 站场管理 | `locations?tab=sites/points` | ✅ |
| 收费方案 | `pricing` 计费模板 | ✅ |
| 预约调价 | `pricing?tab=schedule` 分时定价 | 🟡 雏形 |
| 站场分成 / 商户分成 | `pricing?tab=station-revshare / merchant-revshare` | ✅ |
| 应用版本 | `operations?tab=app-versions` | ✅ |
| 银行管理 | `operations?tab=bank-accounts` | ✅ |
| 公告管理 | `operations?tab=announcements` | ✅ |
| 问题管理（App FAQ 字典） | — | ❌ 缺失 |
| 充电电量（kW·h 字典） | — | ⛔ N/A（EV 专属） |

### 3 设备管理（覆盖最全）
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 充电桩概览 | `devices?tab=monitor` 实时监控 | 🟡 概览≈监控 |
| 充电桩编码 | `devices?tab=codes` | ✅ |
| 充电桩库存 | `devices?tab=inventory` | ✅ |
| 运营商设备 | `devices?tab=by-operator` | ✅ |
| 充电桩管理 | `devices` 设备台账 | ✅ |
| 充电枪管理 | `devices?tab=slots` 仓位管理 | ✅（枪≈仓位） |
| 充电桩配置 | `devices?tab=config` | ✅ |
| 充电桩日志（OCPP） | `devices?tab=command-logs` 指令日志 | ✅ |
| RFID 管理 | `devices?tab=rfid / cards` | ✅ |

### 4 告警管理（全覆盖）
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 告警代码 | `alerts?tab=codes` | ✅ |
| 通知规则 | `alerts?tab=rules` | ✅ |
| 告警记录 | `alerts` / `?tab=faults` | ✅ |
| 告警通知 | `alerts?tab=notifications` | ✅ |

### 5 订单管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 订单概览 | — | 🟡 缺 dashboard 页 |
| 充电订单 | `orders` 订单列表 | ✅ |
| 预约订单 | `orders?tab=booking` | ✅ |
| 退款记录 | `orders?tab=refunds` | ✅ |
| 异常订单 | `orders?tab=exceptions` | ✅ |
| 投诉订单 | `orders?tab=disputes` 订单争议 | 🟡 争议≈投诉 |
| 免费用户 / 免费订单 | `orders?tab=free-users / free-orders` | ✅ |
| 设备统计 / 站场统计 / 站场明细 | `orders?tab=stats-device / stats-station / stats-detail` | ✅ |

### 6 充值管理（全覆盖）
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 充值套餐 | `topup` | ✅ |
| 充值订单 | `topup?tab=orders` | ✅ |

### 7 会员管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 会员列表 | `users` | ✅ |
| 会员钱包 | `users?tab=wallets` | ✅ |
| 车辆品牌 / 车辆管理 | — | ⛔ N/A（EV 专属） |

### 8 招商管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 运营商管理 | `partners?tab=operators` | ✅ |
| 分佣商户 | `partners?tab=merchants` / `agents` | ✅ |

### 9 佣金管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 运营商佣金·明细 / 商户佣金·明细 | `finance?tab=records` 分润明细 / `agents` | ✅ |
| 运营商佣金·统计 / 商户佣金·统计 | — | 🟡 缺汇总表 |

### 10 财务管理（全覆盖且更细）
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 平台财务·收支流水 / 收益流水 | `finance` / `?tab=platform-revenue` | ✅ |
| 运营商财务·收支流水 / 提现审核 | `finance?tab=operator-flows / operator-withdraw` | ✅ |
| 商户财务·收支流水 / 提现审核 | `finance?tab=merchant-flows / merchant-withdraw` | ✅ |
| 会员财务·收支流水 | `finance?tab=member-flows` | ✅ |

### 11 平台对接（OCPI）
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| POS 终端 | `integrations?tab=pos` | ✅ |
| Kiosk 终端 | `integrations?tab=kiosk` | ✅ |
| EMSP 平台 / 角色 / Token / 订单 / 日志 | — | ⛔ N/A（OCPI 充电桩漫游专属） |
| 财务建议（POS 对账） | — | ⛔ N/A |

### 12 短信管理
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 短信记录 | `messaging` 发送记录 | ✅ |
| 短信拉黑 | `messaging?tab=blocked` 黑名单 | ✅ |

### 13 平台设置 —— **本轮补齐重点**
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 平台信息 / 应用信息 / 运营信息 | `settings`（应用与品牌） | ✅ 本轮新增 |
| 支付设置 + Stripe/Paypal/Braintree/Yedpay/ABA/Selcom | `settings?tab=payment` | ✅ 本轮新增（原仅 Neargo） |
| 短信/邮件/个推/登录/第三方登录 服务 | `settings?tab=service` | ✅ 本轮新增 |
| 提现/预约/充电(→借还)/发票/充值协议/密钥 设置 | `settings?tab=other` | ✅ 本轮新增 |

### 14 权限设置
| Excel 页面 | ops-web | 状态 |
|---|---|---|
| 用户管理 | `employees?tab=employees` | ✅ |
| 角色管理 | `employees?tab=roles` | ✅ |

---

## 四、真实缺口清单（需补，按优先级）

| # | 缺口 | 建议落地 | 优先级 |
|---|---|---|---|
| G1 | 平台设置（支付网关/服务/提现预约/发票协议密钥/品牌） | **已落地** `app/settings` + `nav.ts` setting 模块（P2，演示态） | ✅ 本轮 |
| G2 | 问题管理（App 帮助 FAQ 字典） | `operations` 或 `settings` 加 FAQ Tab | P2 |
| G3 | 订单概览 dashboard | `orders?tab=overview` KPI 页 | P2 |
| G4 | 佣金统计汇总表（运营商/商户） | `agents?tab=commission` 或 `finance` 汇总 Tab | P2 |
| G5 | 数据仪表盘平台/站场/设备切换 | 经营看板增加维度切换 | P3 |

---

## 五、N/A（充电桩专属，明确排除）

充电电量字典、车辆品牌、车辆管理、EMSP 平台/角色/Token/订单/日志、POS 财务建议 —— 均为 EV 充电/OCPI 漫游语义，共享充电宝业务不涉及，**不纳入 ops-web**。

---

## 六、ops-web 反超参考清单（➕ 独有能力）

工单管理（列表/看板/SLA/巡检）、营销中心（优惠券/活动/推送/裂变/Banner + 整套广告屏）、数据报表（坪效/消费者分析/自定义）、用户风控/KYC/信用免押、门店 Onboarding/进场合同/BD CRM、代理绩效 —— 参考(充电桩)Excel 均无。

---

## 七、本轮落地摘要

- 新增页面 `ops-web/app/settings/page.tsx`：4 Tab（应用与品牌 / 支付设置 / 服务设置 / 其他设置），沿用 `MockTabView` 演示态（KPI 卡 + 表格）。
- `ops-web/lib/nav.ts`：`access` 域新增 `setting` 模块「平台设置」（4 叶，`module: "setting"` → 仅 ADMIN 可见），phase 2 标识、可正常点击。
- 计数：14 域 / 27→**28** 模块 / 130→**134** 叶。
- RBAC：`setting` 未在任何非 ADMIN 角色权限内 → FINANCE 等进「系统权限」域仍仅见 org/system，不见平台设置（已有单测 `visibleModules(access, FINANCE)=["org"]` 不受影响）。

---

## 八、建议后续

1. G2–G4 三个轻量补页可在下一轮按 `MockTabView` 同法补齐。
2. 若平台设置需从「演示态」转「真实可编辑」，改造为 `system` 页那样的 `FormDrawer` + `api` 读写（对接后端配置表）。
3. N/A 项建议在 `运营端功能清单-V2四级体系.md` 标注「充电桩专属·不做」，避免后续误列为缺口。
