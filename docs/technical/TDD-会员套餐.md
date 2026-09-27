# TDD-会员套餐

状态：**待确认**（本文只给方案，未动代码）
关联需求：[`C端功能清单.md`](../requirements/C端功能清单.md) §10 会员与权益（C-MB，P1）
关联现状：[`C端对齐现状-17模块.md`](C端对齐现状-17模块.md) 模块 10
创建日期：2026-09-27

---

## 1. 现状：这不是一个待修的缺陷，是一个没建完的模块

2026-09-27 实测所得（不是读文档，是打接口 + 查库）：

| 事实 | 证据 |
|---|---|
| **C 端读的 `mbr_plan` 没有任何写入方** | 全仓无该表的 CRUD 端点（含 `plan` 的端点只有巡检计划与计价方案）；`MbrPlan` 仅被实体/mapper/只读服务引用；表 **0 行** |
| 结构化权益 `mbr_benefit` 有 CRUD | `GET/POST /api/user/member-benefits`，表 **0 行** |
| 会员记录 `usr_membership` 有 CRUD | `/api/user/members`（⚠️ 请求体是整实体，含 `id/deleted/createdAt`，在 `known-entity-request-bodies` 台账上），表 **0 行** |
| **发卡直接抛异常** | `MemberServiceImpl.grantCard` → `UnsupportedOperationException("发卡需与支付/权益生效链路一起设计（M5）")` |
| **C 端没有购买端点** | 只有 `GET /mp/user/membership` |
| **权益完全不参与计费** | `trade` 域对 membership **零引用**（grep 全空） |

所以今天的状态是：**套餐建不出来 → C 端会员页永远是空的 → 就算建出来也买不了 → 就算买了权益也不生效。**

c-app 的会员页把「立即购买」如实弹成「即将开放」，并在注释里写明了顺序：
**先接权益计费（C-MB-02），再开购买（C-MB-01）**。这个判断是对的，本方案沿用。

### ⚠️ 权益有两套模型 —— 但不是重复，是两件正交的事

| | 载体 | 形态 | 来源 | 谁在用 |
|---|---|---|---|---|
| 等级权益 | `mbr_benefit` | **结构化列**：`rent_discount` / `free_minutes` / `deposit_free` / `monthly_coupons` / `points_rate` | 积分成长（免费获得） | 只有运营台在配，**无人读** |
| 卡权益 | `mbr_plan.rights` | JSON **文案数组** | 花钱买的方案 | C 端只拿来**显示** |

`UsrMembership` 的注释写明了这层正交：「**等级来自成长值/积分，卡型来自买了哪个方案**」，
而 `MbrPlan` 里确实**没有 `level` 列**。所以「二选一合并」是错的判断（本文初稿如此写，已推翻）。

**真正的问题只有一个**：`mbr_plan.rights` 是**自由文案**，因此卡权益在结构上**不可能**参与计费。
两套都不进计费，是因为**没有「生效权益」这个概念** —— 没人把「等级给的」和「卡给的」合成一个数。

---

---

## 2. 方案：引入「生效权益」，两套都结构化，计价只认合成结果

```
mbr_benefit（等级权益·结构化）  ┐
                               ├─► MemberRightsPort.rightsOf(cUserNo) ─► ChargeChain
mbr_plan.rights（卡权益·改结构化）┘        逐项取更优                      C 端展示文案由此生成
```

三条要点：

1. **`mbr_plan.rights` 从文案数组改为与 `mbr_benefit` 同形状的结构化 JSON**
   （`rentDiscount` / `freeMinutes` / `depositFree`）。不改的话卡权益永远只是广告词。
2. **逐项取更优**（折扣取更低、免费时长取更长、免押取或）——
   不是「卡覆盖等级」：白银用户买了次卡，不该因此丢掉等级折扣。
3. **C 端展示文案由生效权益生成**，不再手填 —— 手填就会出现「页面写着首小时免费，账单照收」，
   而那种不一致没有任何地方会报错。

### 为什么必须走 Port

`user.* → trade.*` 已存在（`RechargeServiceImpl` / `WalletServiceImpl`），`trade.* → user.*` 为零。
计价侧直连会员就是**包循环**，`ArchitectureTest` 会红。照 `CouponUsePort` / `FreeRentPort` 的既有形状：
端口声明在 `sharehub-api` 的 `api.core.port`，本地实现放 `user/member/port/LocalMemberRights`。

### 不做快照

`ChargeChain` 的类注释已经定了口径：「**券与会员权益本来就是结算时才确定的**」——
与倍率（下单时定格）相反。所以会员权益**按归还时刻解析**，不进 `price_snapshot`。
可复原性靠**回写结果**：照 `couponNo` / `couponAmount` 的同一套路，
`ord_order` 记 `member_level`（用了谁的权益）+ `member_discount_amount`（折了多少）。

### 折扣在链上的位置：封顶之后、券之前，且**买断不打折**

```
gross → ③倍率 → 总封顶(买断判定) → ④会员折扣 → ⑤券 → ⑥免单
                      ↑ 买断时跳过会员折扣
```

- **买断判定用未打折的金额**：总封顶就是买断价（宝转 SOLD）。用打折后的数判，
  会员会比普通用户晚很久才触顶，等于「会员逾期更久才买断」。
- **买断成立时不应用会员折扣**：那一刻收的是硬件钱，不是租金；租金折扣打到设备价上没有道理。
- **会员折扣在券之前**：折扣属定价，券属营销抵扣；券的门槛判定基数因此是折后价
  （与既有「门槛用封顶后的应收判」同一条理由 —— 基数只能有一个）。

`免费时长` 不在 `ChargeChain` 里 —— 它作用于**计费分钟数**，在 `priceEngine.price()` 之前扣：
`billingMinutes = max(0, 实际时长 - freeMinutes)`。**`ord_rent_ext.duration_min` 照记真实时长**，
不能把免费时长从时长里抹掉 —— 那是运营数据，不是账。

---

## 3. 分期（顺序不能反）

### P0 · 套餐建得出来、权益真的生效

1. **端口**：`sharehub-api` 加 `MemberRightsPort.rightsOf(cUserNo)` →
   `Rights(level, planNo, rentDiscount, freeMinutes, depositFree)`；非会员返回 `null`
2. **实现**：`user/member/port/LocalMemberRights` —— 读 `usr_membership`（ACTIVE 且未过期）
   → `level` 取 `mbr_benefit`、`planNo` 取 `mbr_plan.rights` → **逐项取更优**
3. **`ChargeChain`**：加会员折扣一层。用**重载**保留 5 参版本（既有 15 处用例不动），
   6 参版本插在封顶之后、券之前，买断时跳过
4. **`RentOrderServiceImpl`**：`freeMinutes` 扣计费分钟（`duration_min` 照记真实时长）；
   4 处 `charge` 调用一并传（结算 / 试算 / 无券重算 / 买断判定）——
   **试算与结算必须逐项对齐**，对不齐的表现是用户盯了一路的数与账单不一样
5. **迁移**：`ord_order` 加 `member_level` + `member_discount_amount`；账单明细加会员折扣行
6. **`mbr_plan` CRUD**：`GET/POST /api/user/member-plans` + `{no}/enable|disable`
   （状态走动作端点，不进保存字段 —— 公告那条链刚踩过）；`rights` 收结构化对象
7. **C 端展示由生效权益生成**，`benefitsOf` 不再直读文案

### P1 · 买得到

4. C 端 `POST /mp/user/membership/{planNo}/subscribe` + 运营侧发卡（`grantCard` 落地）
5. **⚠️ 这一步阻塞在「收款动作」那条待拍板的口径上**：目前**没有任何页面调 `/mp/trade/pay`**、
   `pay_order` 0 行。会员是要收钱的，收款语义没定之前做购买，等于再造一个「记了单没收钱」。

---

## 4. 测试策略

- 链路用例（照 `NoticePublishReachesConsumerTest` 的形状）：运营建套餐 → C 端可见 →
  配权益 → **借还一单** → 账单里出现会员折扣行且**逐项相加 = 实付**
- 边界：免费时长吃满整单（应付 0）· 折扣与券叠加的顺序 · 权益改动不影响在途单（快照）
- 负对照：拆掉权益解析 → 折扣行消失且实付回到原价

## 5. 风险

- **两套权益模型不合一就动手**，等于把分叉固化。P0 第 1 步必须先合。
- **折扣与券的顺序**是金额口径，定错了对账时才发现。
- `usr_membership` 的写入参是整实体（台账项），P1 动到它时要一并转 DTO。

---
确认记录：待确认
