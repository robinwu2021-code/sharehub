# TDD · A2/F1 站点责任与按责任分账

> 2026-09-23 · 落实 [ADR-027](./ADR/ADR-027-代理商类型与按站点责任模型.md) 落地第 2 步，
> 即 [经营链条方案](./经营链条方案-主体到分账.md) 的 **A2 + F1**。
>
> A1（登记类型）已上线（V51）。A1 解决「他大体是哪种人」，**本步解决「他在这个站点做了什么」**。

---

## 零、为什么非做不可

今天一个代理商在一个站点只能拿**一个比例**（`share_rule(AGENT, agentNo).rate` 一条）。
而一个站点上实际有四件事各有其人：**谁出的钱、谁找来的、谁在维护、谁牵的线**。

压成一个比例的代价不是不精确，是**不可追溯**：结算争议时说不清「这 8% 里几个点是运维、
几个点是出资」。而这不是假想 —— 「介绍人先于城市合伙人出现」是开站常态。

---

## 一、要你定的三件事（ADR-027 §待确认，直接决定工作量）

| # | 问题 | 影响 |
|:-:|---|---|
| **Q1** | **一个站点多个伙伴**，L1 就要吗？ | 决定本步是**建关系表**还是只给现有单一代理加一个 `basis`。差别是整整一张表 + 一套维护 UI |
| **Q2** | **效果管理 `MANAGE` 与运维 `OPERATE` 分开计酬吗？** | 决定责任词表是 5 档还是 4 档。首批伙伴多半两件都做 |
| **Q3** | **介绍费 `REFER` 怎么触发？** | 签约一次性 / 首单一次性 / 持续极小比例 —— 决定 REFER 走**事件**还是走**逐单分润** |

我的建议：**Q1 要**（关系表）· **Q2 先合并**（4 档）· **Q3 走签约一次性**。理由见 §五。

---

## 二、库改动（假定三条建议成立）

### 2.1 责任表 `loc_site_agent`

```sql
CREATE TABLE loc_site_agent (
  site_no   VARCHAR(36) NOT NULL,
  agent_no  VARCHAR(36) NOT NULL,
  role      VARCHAR(16) NOT NULL,   -- INVEST / DEVELOP / OPERATE / REFER（Q2 定：不含 MANAGE）
  rule_no   VARCHAR(36)     NULL,   -- 该责任对应的分润规则；空 = 用登记类型默认
  effective_from / effective_to,
  UNIQUE KEY (site_no, agent_no, role)
);
```

**一行一责任，不用 JSON 数组列**。ai-shop 的教训：撤销单个角色变成读-改-写，
两人同时改会互相覆盖 —— 而覆盖的后果是**有人多分了钱且不报错**。多行方案里撤销就是删一行。

`REFER` 与 `DEVELOP` 互斥（牵线是拓展的弱形式），在服务层校验。

### 2.2 `loc_site.agent_no` 保留，语义收窄

收窄为**主经营方** = 承担 `OPERATE` 的那个伙伴。它仍是数据范围与工单派单的锚点，
**现有查询一行不改**。只有 `REFER` 的站点，`agent_no` 为空（= 直营运维）。

> 这是本方案最省事的一处：不动锚点，就不动数据范围、不动派单、不动门户。

### 2.3 分账加「依据」

| 表 | 改动 |
|---|---|
| `share_rule` | 加 `basis`；`(dimension=AGENT, payee_no, basis)` 唯一 |
| `share_record` | 加 `basis`；**幂等键从 `(order_no, dimension, payee_no)` 变成 `(order_no, payee_no, basis)`** |
| 存量 `share_record` | 回填 `basis='OPERATE'`（今天那一条就是运维分成的含义） |

⚠️ **幂等键变更是本步最危险的一处**。V37 那条 `uk_srec_order_payee` 是「outbox 重投不重复记账」
的唯一保证。换键的迁移必须：先加列 → 回填 → 建新唯一键 → 删旧键，**顺序不能反**；
中间任何一步失败都要能停在一个仍然有唯一约束的状态上。

---

## 三、`ShareGeneratorImpl` 的改写

```
现在：  if (agentNo != null) { 查一条 share_rule(AGENT, agentNo) → 写一条记录 }

改后：  读 loc_site_agent(site_no = 订单站点) 的所有行
        每行：按 (AGENT, agent_no, role) 查规则 → 写一条 share_record(basis=role)
        一行都没有 → 回落到今天的行为（查 share_rule(AGENT, agentNo) 写 basis=OPERATE）
```

**回落分支必须留**：责任表是逐站点配的，没配的站点不能因此不分账 ——
那是静默少付合作伙伴，与「静默免单」同一性质。回落时日志说明用的是回落值（与 VENUE 的回落同款）。

跨模块：`loc_site_agent` 在 platform，`ShareGenerator` 在 finance → 走 **Port**
（`SiteAgentQueryPort`，与既有 `SiteSharingQueryPort` 同族），不直连别人的表。

---

## 四、拓展归因：商机能记到伙伴头上

`loc_lead.owner`（今天只接受 `employee_no`）→ `owner_type (STAFF | AGENT) + owner_no`。
商机 `SIGNED` 且 owner 是代理商时，**自动写一行** `loc_site_agent(role=DEVELOP)`。
拓展佣金的依据就是这一行，不靠人记。

> 这条把 A1 时「表单要素 review」里改过的那个 `owner` 字段再推一步：
> 当时把它从「填姓名」改成「选员工」，现在它要能选**伙伴**。

---

## 五、三条定论的理由

**Q1「要」**：ADR 说「介绍人先于城市合伙人出现是常态」。若不建关系表、只给单一代理加
`basis`，那么「A 牵线、B 经营」这个最早出现的组合就表达不了 —— 而它一出现就是**钱的问题**，
补做要迁移已经生成的 `share_record`。表空着不花钱。

**Q2「先合并」**（词表 4 档：INVEST / DEVELOP / OPERATE / REFER）：
`MANAGE` 与 `OPERATE` 首批伙伴多半两件都做，分开只会让每个站点多配一行、每单多一条记录，
而两条记录的受益方与比例完全一样。**要分的时候再加一档不迁移任何数据** —— 责任是数据不是枚举代码。
（反向做就要迁：合并过的记录拆不回去。）

**Q3「签约一次性」**：介绍费的对价是「把关系介绍过来」这个一次性动作，
按持续比例付会出现「介绍一次、分十年」。触发点选 `ContractSigned` 而不是首单结算 ——
合同签了钱就该付，不该让介绍人等第一个顾客。
实现上它**不进逐单分润**，而是合同签署事件写一条 `share_record(basis=REFER, order_no=合同号)`。

---

## 六、分几次做

| 批 | 内容 | 能独立上线吗 |
|:-:|---|---|
| **A2-1** | `loc_site_agent` 建表 + 服务层 + 运营端「站点详情 › 合作伙伴」页签（增删责任行） | 能。纯新增，不碰分账 |
| **A2-2** | `share_rule/share_record` 加 `basis` + 幂等键迁移 + `ShareGenerator` 按责任行生成 | 能，但**必须在 A2-1 之后**（没有责任行就没有多条记录） |
| **A2-3** | `loc_lead.owner_type` + 商机签约自动写 DEVELOP 行 | 能，独立 |
| **A2-4** | REFER 的签约事件触发 | 依赖 A2-2 的 `basis` |

**先做 A2-1 并上线**，让运营能先把责任配起来（配了也不影响现有分账），
再做 A2-2 切换分账逻辑。这样切换那天，数据已经是齐的 —— 而不是切完才开始配。

---

## 七、本步不做

| 不做 | 为什么 |
|---|---|
| 数据范围按关系表派生（ADR §六） | 锚点仍是 `loc_site.agent_no`，现有范围不变。要加维度得**先补覆盖测试**（ai-shop 的两条性质） |
| 门户「我的商机」（ADR 落地第 3 步） | 依赖 A2-3 的归因数据先有 |
| `md_agent_type` 的「默认责任/默认费率」 | A1 时刻意没建。等责任行跑起来、确实需要「建档时一键展开」再说 |
| 伙伴自助配责任 | 责任决定分钱，**只能运营方配**。伙伴自助是 L3 的事 |
