# TDD · P1 取价引擎改读「适用范围」

> 2026-09-23 · 落实 [ADR-028](./ADR/ADR-028-收费方案的适用范围与取价优先级.md) §落地顺序 第 1+2 步，
> 即 [经营链条方案](./经营链条方案-主体到分账.md) 的 **P1 + P3 + D1 + P2** 一批。
>
> 判据沿用链条方案：**「今天就错」＞「上第二种设备就错」＞「结构不够但今天不错」**。
> 本批全部属于前两类。

---

## 零、动手前实测到的四处缺陷（第 4 处 ADR 没记）

| # | 缺陷 | 证据（代码行） | 今天的实际后果 |
|---|---|---|---|
| 1 | 站点规则不看设备类型 | `PriceResolver.matchPlanNo` ① 层只比 `site_no` | L2 按摩椅会按充电宝价收 |
| 2 | 「适用范围」写了没人读 | `price_plan_scope` 只被 `PricePlanServiceImpl` 读写；`PriceResolver` 读的是 `price_rule` | 运营在方案上勾的适用站点不生效 |
| 3 | 时段倍率配了不生效 | `RentOrderServiceImpl:236` → `chargeChain.charge(gross, **null**, …)` | 活动/时段价一分钱都不多收 |
| **4** | **下单根本没传站点** | `RentOrderServiceImpl:199` → `priceResolver.resolve(POWERBANK, **null**, **null**)` | **连 `price_rule` 那套也从未生效**：每一单都落到「设备类型默认方案」。<br>缺陷 2 说的是「新机制没人读」，缺陷 4 说的是「旧机制也没人喂」—— 也就是**今天站点级差价完全不存在**，两个菜单都是摆设 |

> 第 4 处是这次动手才发现的。它把缺陷 2 的严重性又抬高一级：
> 原以为是「两套机制并存、生效的是另一套」，实际是**两套都没生效**。

另有一处结构问题，ADR 未涉及：

| # | 问题 | 说明 |
|---|---|---|
| 5 | `price_schedule.period` 存的是**展示串** | 实际值形如 `周六-周日 18:00-22:00`、`每天`、或任意中文表达式。后端要用它判倍率，就得复刻前端那个**按中文标签解析**的 parser —— 而界面还有英文/阿语。**用展示串做判断**与本项目栽过的「按名字连表」是同一类错 |

---

## 一、决策（含 ADR-028 待确认项的取舍）

| # | 决策 | 理由 |
|---|---|---|
| D1 | `price_plan_scope` 扩列，**一次扩到位**（含 `vendor_code` / `model` / `brand_no` / `location_no` / `device_no` 所需的 level+ref） | 链条方案 P2：事后补等于再迁一次。列是空的不花钱，迁移是要停机的 |
| D2 | **层序照 ADR §二**：DEVICE > LOCATION > SITE > VENUE > AGENT > SCENE > REGION > ALL | ADR 待确认 #1（VENUE 高于 AGENT）按 ADR 原议执行：场地方的价来自合同、是对外承诺，伙伴定价是内部安排 |
| D3 | 时段倍率按**订单开始时刻**取值并写进快照 | ADR 待确认 #2 按原议。跨时段长单不分段——分段的争议成本高于精度收益 |
| D4 | **`price_schedule` 加结构化列**（`days` / `time_from` / `time_to` / `expr`），`period` 降级为展示串 | 缺陷 5。后端只读结构化列；`expr` 型（节假日）本期**不参与**倍率计算，原样保留待日历能力 |
| D5 | `price_rule` **改名退役**而不是 DROP | 与 V45 同一处置：别的环境行数无法在迁移里确认，改名可回退 |
| D6 | VENUE 级方案**先手工建**，不从合同自动带出 | ADR 待确认 #3：合同价格约束字段还没有，自动带出会凭空造一个字段 |
| D7 | 币种硬过滤**本期不做** | ADR §七 依赖 `md_region.market_code`（S2）。单市场恒真，做了也验证不了 |

---

## 二、库改动（V46）

```sql
-- price_plan_scope 扩列
ALTER TABLE price_plan_scope
  ADD COLUMN device_type    VARCHAR(32)  NOT NULL DEFAULT 'POWERBANK',
  ADD COLUMN level          VARCHAR(16)  NOT NULL DEFAULT 'ALL',   -- DEVICE/LOCATION/SITE/VENUE/AGENT/SCENE/REGION/ALL
  ADD COLUMN vendor_code    VARCHAR(32)      NULL,
  ADD COLUMN model          VARCHAR(64)      NULL,
  ADD COLUMN brand_no       VARCHAR(36)      NULL,
  ADD COLUMN priority       INT          NOT NULL DEFAULT 0,
  ADD COLUMN effective_from DATETIME(3)      NULL,
  ADD COLUMN effective_to   DATETIME(3)      NULL;
```

- `scope_type` / `scope_ref` **保留原名**：`scope_type` 就是 `level`（同义），
  改名要动实体、服务、前端三处，而语义没变 —— 不值。新列只加 `level` 之外的部分。
  **修正**：不加 `level`，直接扩 `scope_type` 的取值域（SITE/SCENE/ALL → 八档）。
- UK 从 `(plan_no, scope_type, scope_ref)` 扩为
  `(device_type, scope_type, scope_ref, vendor_code, model, brand_no)` —— ADR §一：
  **同一范围只能有一个启用中的方案**。这条 UK 才是「取价必然唯一」的保证，
  放在 `plan_no` 上的旧 UK 保证的是「一个方案不重复登记同一范围」，不是一回事。

`price_rule` 迁入：

```sql
INSERT IGNORE INTO price_plan_scope (plan_no, scope_type, scope_ref, device_type, priority)
SELECT plan_no, 'SITE',  site_no,    'POWERBANK', priority FROM price_rule WHERE site_no IS NOT NULL;
INSERT IGNORE INTO price_plan_scope (plan_no, scope_type, scope_ref, device_type, priority)
SELECT plan_no, 'SCENE', scene_type, 'POWERBANK', priority FROM price_rule WHERE site_no IS NULL AND scene_type IS NOT NULL;
RENAME TABLE IF EXISTS price_rule TO price_rule_deprecated_v1;
```

`price_schedule` 结构化列（D4）：

```sql
ALTER TABLE price_schedule
  ADD COLUMN days      VARCHAR(16) NULL COMMENT '生效星期 CSV，1=周一…7=周日；空=每天',
  ADD COLUMN time_from CHAR(5)     NULL COMMENT 'HH:mm；与 time_to 同时为空=全天',
  ADD COLUMN time_to   CHAR(5)     NULL COMMENT 'HH:mm；可跨零点',
  ADD COLUMN expr      VARCHAR(64) NULL COMMENT '节假日等日历表达式，本期不参与计算';
```
存量 `period` **不做自动回填**：中文串解析不可靠，宁可让存量行「不生效」也不要猜错倍率。
前端保存时同时写结构化列与 `period`（它已经有 `PeriodSpec`，只是没往后端传）。

---

## 三、取价引擎重写

```java
Resolved resolve(PriceQuery q)   // q: deviceType, siteNo, locationNo, deviceNo,
                                 //    venueNo, agentNo, sceneType, regionId,
                                 //    vendorCode, model, brandNo, at
```

裁决（ADR §二）：
1. 硬过滤：`device_type` 相符 + 生效期覆盖 `at` + 方案 `status='ACTIVE'`
2. 过滤器相符：`vendor_code` / `model` / `brand_no` 要么为空（不限），要么等于入参
3. 取**层序最小**的
4. 同层：**过滤器命中数多**的优先
5. 仍并列：`priority` 降序 → 最新
6. **一条都没有 → 抛异常**（沿用「绝不静默免单」）

返回值加 `hit`（level/ref/filters/planNo/multiplier）写进快照（ADR §五）。

---

## 四、把站点喂进去（修缺陷 4）

`RentOrderServiceImpl.rent()` 只有 `cabinetNo`。需要 机柜 → 点位/站点 → 场地方/代理/区域/场景。

- 机柜在 core 自己的库（`CabinetMapper` 已注入）：拿 `locationNo` / `siteNo` / `agentNo` / `vendorCode` / `model`
- 站点在 platform：走 **`SiteQueryPort`**。`SiteBrief` 目前只有 `(siteNo, name, regionId)`，
  需补 `venueNo` / `agentNo` / `sceneType` —— 这是跨模块 DTO，扩字段而非传实体（ADR-017 §5.2 纪律不变）

拿不到站点时**不静默降级**：记 WARN 并按 `ALL` 层取价（那是「默认方案」的本意），
但 `hit.level` 如实记 `ALL`，不伪装成 SITE。

---

## 五、时段倍率接入（修缺陷 3）

`PriceMultiplierResolver`：按 `regionId` + 订单**开始时刻**的星期与时刻，
从 `price_schedule`（`active=1`，结构化列）选出倍率；多条命中取**最大**倍率并记录来源。
结果传给 `chargeChain.charge(gross, multiplier, …)` 并写进快照的 `hit.multiplier`。

> 为什么取最大而不是相乘：相乘会让「高峰 1.5 × 节假日 1.5 = 2.25」这种叠加悄悄出现，
> 运营配的时候不会意识到。取最大是可预期的，且任何一条都能单独解释。

---

## 六、前端

| 页 | 改动 |
|---|---|
| 运营管理 › 收费方案 | 「适用范围」从展示字段变成可编辑：设备类型（必填）+ 层 + 引用 + 厂商/型号/品牌过滤 + 优先级 + 生效期 |
| 运营管理 › 收费方案 · 时段倍率 | 保存时把 `PeriodSpec` 的结构化字段一起提交（现在只存展示串） |
| 计费定价 › 差异化定价 | **整页下线**；`计费定价` L1 随之撤销（菜单收敛方案第 3 步） |

---

## 七、验证

- 单测：层序裁决（八层逐一）、过滤器命中数裁决、并列 priority、无命中抛异常、生效期边界
- 单测：倍率按星期/时刻命中与不命中、跨零点、多条取最大
- 端到端：下单 → 快照含 `hit` → 归还 → 金额 = 方案价 × 倍率
- 迁移后实测：`price_plan_scope` 行数 = 原 `price_rule` 行数（按 site/scene 分别核）

---

## 八、本批不做

| 不做 | 为什么 |
|---|---|
| 币种硬过滤 | 依赖 `md_region.market_code`（S2），单市场恒真 |
| 伙伴自助定价与上下限 | ADR 待确认 #4，L2/L3 |
| `expr` 型（节假日）倍率 | 需要节假日日历，本期只保留原文 |
| 合同带出 VENUE 级方案 | 合同还没有价格约束字段（D6） |
| `price_rule` DROP | 只改名（D5），DROP 留给确认各环境行数之后 |
