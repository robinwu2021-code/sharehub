# TDD · 运营主体 / 商户 / 场地 —— 数据库与代码调整方案

状态：**待确认**（2026-09-23）
关联决策：[ADR-029 运营主体·商户·场地](./ADR/ADR-029-运营主体抽象.md) · [ADR-027 代理商类型与责任](./ADR/ADR-027-代理商类型与按站点责任模型.md) · [ADR-026 取消租户](./ADR/ADR-026-取消租户概念-唯一运营方.md)
体检依据：[数据库设计 Review](./数据库设计-review-2026-09-23.md) · [数据库设计规范](./数据库设计规范.md)
迁移号：**从 V46 起**（V44 / V45 已被并行会话占用）

---

## 1. 需求摘要

把 ADR-029 定下的三个概念落到库和代码：

| 概念 | 落点 |
|---|---|
| **运营主体 Operator** | `agt_agent` 升格 + `operator_type` + `PLATFORM` 哨兵行；6 个可空 `agent_no` 去 NULL |
| **商户 Merchant** | 新表 `pay_merchant`（收单：通道 × 市场 × 进件 × 子商户号）—— **只挂运营主体** |
| **场地 Venue** | 保持独立；新增 `loc_venue.operator_no` 表达「同一法人」关联（2026-09-23 用户定：**建立关联**） |
| 收款账户 | 新表 `stl_payout_account`（打款）—— 运营主体与场地方**共用** |
| **账号 × 主体** | `agt_account` 改为成员关系表：换唯一键 + `login_phone` / `is_owner` / `is_primary`（[ADR-030](./ADR/ADR-030-账号与主体-一个账号可属多个运营主体.md)） |

顺带修两个已确认的缺陷：`share_record` 幂等键含可空列、`updated_by` 从插入起就没变过。

---

## 2. 当前架构分析

| 现状 | 影响 |
|---|---|
| 6 张表 `agent_no` 可空，`NULL` = 平台直营 | NULL 进不了唯一键；平台收入无处记；`GROUP BY` 丢行；责任表无法表达直营 |
| `agt_agent.settle_account VARCHAR(128)` | **全仓无代码读它** |
| `loc_venue` 无任何账户列 | 场地方是 Payee，但系统不知道钱打给谁 |
| `stl_withdrawal` 无账户列 | **提现审核页能审批，审批完不知道往哪打钱** ← L0 阻塞 |
| `uk_srec_order_payee (order_no, dimension, payee_no)` 含两个可空列 | MySQL 的 UNIQUE 不约束 NULL，防重投的闸门对 NULL 行失效 |
| `AuditMetaObjectHandler.updateFill` 用 `strictUpdateFill` | 字节码确认只在字段为 null 时填 → `updated_by` 永远是创建人（`updated_at` 有 DDL 的 `ON UPDATE` 兜住） |

**可复用**：`md_bank`（已有 SWIFT 前缀、IBAN 位数，一直没有消费方）· `payee_type + payee_no` 这对列已贯穿三张表 · `agt_assignment` 划拨流水 · `DataScopeRegistration` 注册机制。

---

## 3. 数据库调整

### V46 · 运营主体类型与平台哨兵

```sql
ALTER TABLE agt_agent
  ADD COLUMN IF NOT EXISTS operator_type VARCHAR(16) NOT NULL DEFAULT 'AGENT'
  COMMENT 'PLATFORM 平台自营 / AGENT 代理商 / CITY_PARTNER 城市合伙人';

INSERT INTO agt_agent (agent_no, operator_type, name, status, tenant_id,
                       created_at, created_by, updated_at, updated_by)
VALUES ('PLATFORM','PLATFORM','平台自营','ENABLED','MAIN',
        NOW(3),'SYSTEM',NOW(3),'SYSTEM')
ON DUPLICATE KEY UPDATE agent_no = agent_no;

CREATE INDEX IF NOT EXISTS idx_agent_operator_type ON agt_agent (operator_type);
```

> **「PLATFORM 只能一行」不用 DDL 约束。** MariaDB 无部分唯一索引；生成列 + 唯一索引要求 `PERSISTENT`，
> 为一行数据加一个持久化列不划算。改由**应用层校验 + 守卫测试**（§5）保证，与「内置角色只读」同一做法。

### V47 · 6 个 `agent_no` 去 NULL

```sql
-- loc_site / loc_location / dev_cabinet / dev_alarm / ord_rent / wo_order 各一组
UPDATE loc_site SET agent_no = 'PLATFORM' WHERE agent_no IS NULL;
ALTER TABLE loc_site
  MODIFY agent_no VARCHAR(36) NOT NULL DEFAULT 'PLATFORM'
  COMMENT '运营主体（PLATFORM=平台自营）；下单/开单时快照，不随后续划拨变动';
```

⚠️ **顺序不可颠倒**：先 `UPDATE` 再 `MODIFY`，否则 `NOT NULL` 会被存量 NULL 行拒绝。
⚠️ 这一步**必须在 V50 责任表之前**，否则责任表会先长出一批 NULL 主体的行。

### V48 · 收款账户 + 场地方关联

```sql
CREATE TABLE IF NOT EXISTS stl_payout_account (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_no   VARCHAR(36)  NOT NULL                COMMENT '业务键 PA*',
  payee_type   VARCHAR(16)  NOT NULL                COMMENT 'OPERATOR 运营主体 / VENUE 场地方',
  payee_no     VARCHAR(36)  NOT NULL,
  bank_code    VARCHAR(32)  NOT NULL                COMMENT '→ md_bank.bank_code',
  account_name VARCHAR(128) NOT NULL                COMMENT '户名（须与主体法人名一致）',
  account_masked VARCHAR(64) NOT NULL               COMMENT 'IBAN 掩码；明文入 sharehub_pii',
  currency     VARCHAR(8)   NOT NULL DEFAULT 'AED',
  is_default   TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '同一受益方恰好一个默认账户（应用层保证）',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(36)      NULL,
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(36)      NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_payout_account_no (account_no),
  KEY idx_stl_payout_account_payee (payee_type, payee_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款账户（打款用；运营主体与场地方共用）';

-- 场地方 ↔ 运营主体：同一法人断言
ALTER TABLE loc_venue
  ADD COLUMN IF NOT EXISTS operator_no VARCHAR(36) NULL
  COMMENT '同一法人：本场地方同时是这个运营主体（商场自投自营）；NULL = 纯场地方';
ALTER TABLE loc_venue
  ADD UNIQUE KEY IF NOT EXISTS uk_venue_operator (operator_no);
```

> **这里的可空唯一键是对的，与 `share_record` 那个缺陷不是一回事。**
> `share_record` 需要「每个 NULL 组合也只能有一行」——NULL 不参与唯一约束，闸门失效；
> 这里恰恰**需要**「多个场地方都可以没有关联主体」，同时「一个主体最多被一个场地方认领」。
> **判据**：唯一键里的可空列，是「允许多行为空」还是「每个空值组合也要唯一」？前者可空，后者必须 NOT NULL。

### V49 · 修幂等键 + 分润依据 `basis`

```sql
-- ① 先扫存量。有 NULL 行就停下来人工核，不要盲目回填
SELECT COUNT(*) FROM share_record WHERE dimension IS NULL OR payee_no IS NULL;

UPDATE share_record SET dimension = payee_type WHERE dimension IS NULL;
UPDATE share_record SET payee_no  = 'PLATFORM' WHERE payee_no IS NULL AND dimension = 'PLATFORM';

ALTER TABLE share_record MODIFY dimension VARCHAR(16) NOT NULL COMMENT 'OPERATOR/VENUE';
ALTER TABLE share_record MODIFY payee_no  VARCHAR(36) NOT NULL COMMENT '受益方业务键；平台为 PLATFORM';
ALTER TABLE share_record ADD COLUMN IF NOT EXISTS basis VARCHAR(16) NOT NULL DEFAULT 'OPERATE'
  COMMENT 'INVEST 出资/DEVELOP 拓展/OPERATE 运维/MANAGE 效果管理/REFER 牵线（ADR-027）';

ALTER TABLE share_record DROP INDEX IF EXISTS uk_srec_order_payee;
ALTER TABLE share_record ADD UNIQUE KEY IF NOT EXISTS uk_srec_order_payee_basis
  (order_no, dimension, payee_no, basis);

ALTER TABLE share_rule ADD COLUMN IF NOT EXISTS basis VARCHAR(16) NOT NULL DEFAULT 'OPERATE'
  COMMENT '分润依据，同 share_record.basis';
ALTER TABLE share_rule DROP INDEX IF EXISTS uk_share_rule_payee;
ALTER TABLE share_rule ADD UNIQUE KEY IF NOT EXISTS uk_share_rule_payee_basis
  (dimension, payee_no, basis);
```

⚠️ **换唯一键有窗口**：`DROP` 与 `ADD` 之间无约束。停机窗口内做，或先 `ADD` 新键再 `DROP` 旧键。

### V50 · 站点 × 运营主体 × 责任（ADR-027 步 2）

```sql
CREATE TABLE IF NOT EXISTS loc_site_operator (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  site_no     VARCHAR(36) NOT NULL,
  operator_no VARCHAR(36) NOT NULL                COMMENT '→ agt_agent.agent_no',
  role        VARCHAR(16) NOT NULL                COMMENT 'INVEST/DEVELOP/OPERATE/MANAGE/REFER',
  rule_no     VARCHAR(36)     NULL                COMMENT '该责任的分润规则；空=用类型默认费率',
  effective_from DATETIME(3)  NULL,
  effective_to   DATETIME(3)  NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(36)     NULL,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(36)     NULL,
  version     BIGINT      NOT NULL DEFAULT 0,
  deleted     TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_loc_site_operator (site_no, operator_no, role),
  KEY idx_loc_site_operator_site (site_no),
  KEY idx_loc_site_operator_op (operator_no, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点的运营主体责任（一行一责任）';

-- 存量回填：现有归属视为 OPERATE 责任
INSERT INTO loc_site_operator (site_no, operator_no, role, tenant_id, created_at, created_by, updated_at, updated_by)
SELECT site_no, agent_no, 'OPERATE', 'MAIN', NOW(3),'SYSTEM',NOW(3),'SYSTEM' FROM loc_site
ON DUPLICATE KEY UPDATE site_no = loc_site_operator.site_no;
```

⚠️ **回填之后必须成立的不变量**（[领域模型 §2.4.2](./领域模型-本体与关系.md)，此前两份 ADR 都没钉住）：

> **`loc_site.operator_no` 必须在 `loc_site_operator` 中出现，且持有 `OPERATE`。**

```sql
-- 守卫：应恒为 0 行
SELECT s.site_no FROM loc_site s
 WHERE NOT EXISTS (SELECT 1 FROM loc_site_operator o
                    WHERE o.site_no = s.site_no AND o.operator_no = s.operator_no
                      AND o.role = 'OPERATE' AND o.deleted = 0);
```

不钉这一条就会出现「数据范围归 A，而 A 在这个站点一条责任都没有」——**看得见数据、分不到钱，且没有任何地方报错**。
改站点主责时必须同事务维护 `OPERATE` 那一行；`ShareGenerator` 遇到主责无 `OPERATE` 责任**抛错，不静默跳过**。

---

### V51 · 商户（收单）—— 表延后建，但**唯一键从一开始就带 scope**

按 [ADR-026 连带结论](./ADR/ADR-026-取消租户概念-唯一运营方.md)，首期走 `LEDGER`（平台统一收款），商户进件非必需。
但**唯一键的形状现在就要定准**——ai-shop 的 `mch_payment_merchant` 最初是 `(entity_no, pay_channel)`，
V14 专门改成 `(entity_no, pay_channel, store_no)` 才解开「一个渠道只能一个商户号」的限制，
**而改唯一键是有空窗期的高风险操作**（见 §6）。建表时多两列几乎零成本。

```sql
CREATE TABLE IF NOT EXISTS pay_merchant (
  merchant_no  VARCHAR(36) NOT NULL              COMMENT '业务键 PM*',
  operator_no  VARCHAR(36) NOT NULL              COMMENT '→ agt_agent.agent_no；只挂运营主体，场地方不得有',
  channel      VARCHAR(32) NOT NULL,
  market       VARCHAR(8)  NOT NULL DEFAULT 'AE',
  scope_level  VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'SITE / DEVICE_TYPE / ALL —— 判别维度',
  scope_ref    VARCHAR(64) NOT NULL DEFAULT '*'   COMMENT 'site_no / device_type / *(ALL)',
  sub_mch_no   VARCHAR(64)     NULL              COMMENT '通道回执的子商户号',
  apply_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/APPLYING/ACTIVE/REJECTED/FROZEN',
  reject_reason VARCHAR(255)   NULL              COMMENT '原样给主体看',
  ... 五件套 ...
  UNIQUE KEY uk_pay_merchant (operator_no, channel, market, scope_level, scope_ref)
) COMMENT='收款商户号（收单）';
```

**选号规则**（与 [ADR-028](./ADR/ADR-028-收费方案的适用范围与取价优先级.md) 的取价同构）：
`SITE` → `DEVICE_TYPE` → `ALL`，取最先命中；**一个都没命中就抛异常，绝不静默进错账户**。

> 因此 **`loc_site` 不加 `pay_merchant_no`** —— 站点只是 `scope_ref` 的一种取值。
> 也**不要加 `settle_mode` 枚举**：ai-shop 的结论是「配同一个号就是合并，配不同的就是分开；
> 存一个 settleMode 反而会与 pay_merchant_no 打架——配置说分、开关说合，听谁的都是错的」。

### V52 · 自然人表 + 账号改为「人 × 主体」成员关系（ADR-030）

> 2026-09-23 用户补充「注册信息包含手机号码、邮件」——**本迁移因此从「给 `agt_account` 加几列」
> 改成「先建 `agt_principal`，账号表改挂它」。** 原因见 ADR-030 §2.3 / §2.4，两条：
> ① 现有 `agt_account.login_phone` 存的是**掩码**（V13 列注释原文：「明文脱敏值」），**掩码做唯一键会撞**；
> ② 两个登录标识 + 一套凭据不能在 N 行成员关系上冗余。

```sql
-- ① 自然人（登录主体）：一个人一行
CREATE TABLE IF NOT EXISTS agt_principal (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  principal_no VARCHAR(36)  NOT NULL                COMMENT '业务键 PR*',
  phone_hash   CHAR(64)     NOT NULL                COMMENT 'HMAC-SHA256(规范化手机号, pepper)；登录查找键',
  phone_mask   VARCHAR(32)  NOT NULL                COMMENT '138****8000；仅显示，不得进唯一键或等值条件',
  phone_enc    VARBINARY(256)   NULL                COMMENT '可逆加密明文；pb_pii 建成后迁出',
  email_hash   CHAR(64)     NOT NULL                COMMENT 'HMAC-SHA256(lower(trim(邮箱)), pepper)',
  email_mask   VARCHAR(64)  NOT NULL                COMMENT 'a***@example.com；仅显示',
  email_enc    VARBINARY(512)   NULL,
  cred_ref     VARCHAR(64)      NULL                COMMENT 'sharehub_auth.cred 引用（realm=AGENT）；一个人一套',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(36)      NULL,
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(36)      NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_principal_no    (principal_no),
  UNIQUE KEY uk_agt_principal_phone (phone_hash),
  UNIQUE KEY uk_agt_principal_email (email_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理端自然人（登录主体）';

-- ② 成员关系
ALTER TABLE agt_account
  ADD COLUMN IF NOT EXISTS principal_no VARCHAR(36)  NULL COMMENT '→ agt_principal.principal_no',
  ADD COLUMN IF NOT EXISTS is_owner     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '主体属主：全站点全权限，不进授权表',
  ADD COLUMN IF NOT EXISTS is_primary   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '该人的默认主体；同一人至多一个',
  ADD COLUMN IF NOT EXISTS display_name VARCHAR(64)  NULL COMMENT '在该主体下的显示名';

-- ③ 存量回填：见下方「回填走人工」
-- ④ 换键
ALTER TABLE agt_account MODIFY principal_no VARCHAR(36) NOT NULL;
ALTER TABLE agt_account ADD UNIQUE KEY IF NOT EXISTS uk_agt_account_member (agent_no, principal_no);
CREATE INDEX IF NOT EXISTS idx_agt_account_pr ON agt_account (principal_no, is_primary);
ALTER TABLE agt_account DROP INDEX IF EXISTS uk_agt_username;
```

#### 三条不能省的

| # | 做法 | 不这么做会怎样 |
|---|---|---|
| 1 | **`login_phone` 列留着不动，只当显示用** | 它是掩码。拿它回填 `phone_hash` 得到的是「掩码的哈希」，两个不同的号会撞成同一行 |
| 2 | **回填走人工，不写进迁移脚本** | 掩码**不可逆**，从 `138****8000` 推不回明文。存量代理账号必须由运营逐个补手机号与邮箱，脚本自动填只能填错 |
| 3 | **先 `ADD` 新键再 `DROP` 旧键**（同 V49） | 缩短唯一性空窗 |

> **存量有多少**：`agt_account` 今天**不在登录链路里**——`AuthController` 只有 admin 口令闸与 dev-mode 免密两条路径，
> 全仓 `loginPhone` 只出现在实体与 DTO 各一处，**没有任何业务代码读它**。
> 所以回填的实际工作量取决于运营已录了多少行，很可能接近 0；上线前跑一次
> `SELECT COUNT(*) FROM agt_account WHERE deleted=0;` 确认。

#### 结构守卫（加进测试，否则日后有人「顺手」用掩码查）

```
① 任何唯一键 / 索引都不得包含 *_mask 列
② 代码里不得出现 phone_mask / email_mask 的等值查询（eq / = ?）
③ 写 *_hash 必须经同一个规范化函数 —— 注册与登录用不同规范化 = 注册能成、登录查不到
```

**`agt_account_site`（账号对站点的授权）延后到 L2**，结构见 ADR-030 §五。
⚠️ 它与 `loc_site_operator`（主体对站点的**责任**）**不是一回事**，不要合并。

---

### V53 · 入驻申请：两条入口同落一张表（ADR-030 §三）

用户 2026-09-23 定：**商家可自助注册，运营商也可代建，条件相同，自助注册同样需审核通过。**
「条件相同」在表上的含义就是**不按 `source` 分叉**——一张表、一个状态机、一套必填校验。

```sql
CREATE TABLE IF NOT EXISTS agt_apply (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  apply_no       VARCHAR(36)  NOT NULL                     COMMENT '申请单业务键',
  source         VARCHAR(16)  NOT NULL                     COMMENT 'SELF_SERVICE 商家自助 / OPS_CREATED 运营商代建',
  phone_hash     CHAR(64)     NOT NULL                     COMMENT 'HMAC(规范化手机号)；已存在不是重复注册，是多主体申请',
  phone_mask     VARCHAR(32)  NOT NULL                     COMMENT '仅显示',
  phone_enc      VARBINARY(256)   NULL,
  email_hash     CHAR(64)     NOT NULL                     COMMENT 'HMAC(lower(trim(邮箱)))；🆕 用户 2026-09-23 定必填',
  email_mask     VARCHAR(64)  NOT NULL                     COMMENT '仅显示',
  email_enc      VARBINARY(512)   NULL,
  principal_no   VARCHAR(36)      NULL                     COMMENT '命中已有自然人时回填；空=新人',
  operator_name  VARCHAR(128) NOT NULL                     COMMENT '拟建主体名称',
  operator_type  VARCHAR(16)  NOT NULL                     COMMENT 'AGENT / CITY_PARTNER；决定必填项，source 不决定',
  region_scope   JSON             NULL                     COMMENT '申请辖域',
  share_rate     DECIMAL(5,4)     NULL                     COMMENT '拟定默认分润比例，审核时定',
  payload        JSON             NULL                     COMMENT '资料；敏感件只存 sharehub_pii 引用，不落明文',
  status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'     COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED',
  reject_reason  VARCHAR(512)     NULL                     COMMENT '驳回原因，供回填重提',
  submitted_by   VARCHAR(36)      NULL                     COMMENT '自助=手机号；代建=经办 employee_no',
  submitted_at   DATETIME(3)      NULL,
  reviewed_by    VARCHAR(36)      NULL                     COMMENT '审核人；代建一键通过时与 submitted_by 同值，照写不省',
  reviewed_at    DATETIME(3)      NULL,
  operator_no    VARCHAR(36)      NULL                     COMMENT '通过后回写，申请↔主体双向可查',
  active_key     VARCHAR(64)  GENERATED ALWAYS AS (
                    CASE WHEN status IN ('DRAFT','SUBMITTED','REVIEWING')
                         THEN phone_hash ELSE apply_no END) STORED
                                                           COMMENT '见下方「为什么不用可空唯一键」',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN'      COMMENT '历史遗留常量，恒 MAIN（ADR-026）',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(36)      NULL,
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(36)      NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_apply_no     (apply_no),
  UNIQUE KEY uk_agt_apply_active (active_key),
  KEY idx_agt_apply_queue        (status, submitted_at),
  KEY idx_agt_apply_phone        (phone_hash),
  KEY idx_agt_apply_email        (email_hash),
  KEY idx_agt_apply_operator     (operator_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营主体入驻申请';
```

#### 为什么不用可空唯一键（这个坑本项目刚踩过）

想表达的约束是「**同一手机号同时至多一张在途申请**」（按 `phone_hash`，不按掩码）。直觉写法是终态时把某列置 NULL，
靠 `UNIQUE` 只约束非空行——**这正是 V49 在修的 `share_record` 幂等键失效的根因：
MySQL 的 UNIQUE 不约束 NULL，可空列进唯一键等于没进。**

所以 `active_key` 用**生成列**：在途时取 `login_phone`（同手机号第二张在途会撞键），
终态时取 `apply_no`（天然唯一，互不相撞）。**全程非空，约束真实生效。**

> 「同一手机号至多一张在途」是**串行建主体**的选择——审核是人工的，并发申请只给审核员
> 制造重复判断，没有业务价值。若日后要放开，改生成列表达式即可，不动其它。

#### 激活派生（审核通过，一个事务）

```
APPROVED  ──┬──▶ agt_agent           新主体（operator_type / region_scope / share_rate 取申请单）
            ├──▶ agt_principal       按 phone_hash 查：**命中则复用**，未命中才新建（连同 cred）
            ├──▶ agt_account         属主成员关系行 is_owner=1，挂 principal_no
            │                        该人首个主体 → is_primary=1；第 2 个及以后 → is_primary=0
            ├──▶ stl_payout_account  占位（V48）—— 占位 ≠ 可打款，见下
            └──▶ agt_apply.operator_no 回写
```

⚠️ **`status=ENABLED`（能经营）与「能拿钱」是两个判据，不要合用。**
ai-shop 因为没分开，出现过「商家能卖、订单在来、结算单在生成，而收款号解析不到，
账单留空钱欠着，商家一路上没收到任何提示」。代理门户必须按**收款账户完整性**
（V48 的 `stl_payout_account`）显示「你还不能收款」，而不是按审核状态。

---

## 4. 代码调整

### 4.1 P0 · 修 `updated_by` 永不更新

`backend/sharehub-common/.../AuditMetaObjectHandler.java`

```java
// 改前：strictUpdateFill 只在字段为 null 时填 —— 更新走「查出实体→改→updateById」，
//       查出来的 updatedBy 一定有值，于是永远跳过（MP 3.5.16 字节码确认）
public void updateFill(MetaObject metaObject) {
    setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
    setFieldValByName("updatedBy", currentActor(), metaObject);
}
```

配测试：①已有值也要被覆盖 ②append 表实体（无这两个字段）不抛异常。

### 4.2 实体与新服务

| 文件 | 改什么 |
|---|---|
| `AgtAgent.java` | 加 `operatorType`；类注释写明「本表是**运营主体**登记表，`agent_no='PLATFORM'` 是平台自营」 |
| `LocVenue.java` | 加 `operatorNo`（同一法人关联，可空） |
| 🆕 `StlPayoutAccount.java` + Service | 收款账户 CRUD；**同一受益方恰好一个默认账户**的校验 |
| 🆕 `LocSiteOperator.java` + Service | 责任行 CRUD；`REFER` 与 `DEVELOP` 互斥校验 |
| 🆕 `PayMerchant.java` | 延后，随 `CHANNEL_SPLIT` |

### 4.3 NULL 语义改造（5 处，已实测定位）

| # | 文件 | 改什么 |
|---|---|---|
| 1 | `ScopeAnchorSeeder` | 3 条 SQL 的 `WHERE agent_no IS NULL` → `= 'PLATFORM'` |
| 2 | `LocalCabinetQuery:43` | `w.isNull(DevCabinet::getAgentNo)` → `w.eq(..., "PLATFORM")` |
| 3 | `DataScopeRegistration` 类注释 | 「`IS NULL` 表示平台直营」→「`='PLATFORM'`」 |
| 4 | `AgentAssignmentServiceImpl` | 回收写 `'PLATFORM'` 而非置 NULL；**禁止划拨给 `PLATFORM`**（那是回收，不是划拨） |
| 5 | ops-web `sites/page.tsx:225` | `s.agentNo ?? '平台直营'` → `s.agentNo === 'PLATFORM' ? '平台直营' : s.agentNo`，**删掉 `??` 兜底**（否则真 NULL 被静默显示成直营） |

> **数据范围一行不用改**：`IN ('AG001')` 既不匹配 NULL 也不匹配 `'PLATFORM'`，
> 现有守卫测试 `agent_cannot_see_platform_direct_rows` 照样通过。

### 4.4 分润生成按责任分条

`ShareGeneratorImpl.generate(OrderSettledEvent)`：

```
改前：VENUE 查一条合同费率 → 写一条；AGENT 查一条 share_rule → 写一条
改后：VENUE 不变
     OPERATOR：读 loc_site_operator(site_no) 的责任行
              → 每行按 (payee_no, basis) 查 share_rule
              → 每行写一条 share_record（含 basis 与费率快照 source_no）
     平台净收入：残值写一条 (dimension=OPERATOR, payee_no='PLATFORM', basis='OPERATE')
```

**幂等**：唯一键已含 `basis`，outbox 重投仍只落一条。
**费率缺失**：沿用现有「没有可用比例就 `log.warn` 不写行」——**不要静默按 0 记账**。

### 4.5 提现打款读账户并落快照

`stl_withdrawal` 加三列快照（V49 同批）：`bank_code` · `account_masked` · `payout_account_no`。

> **必须落快照**（ai-shop 的实战教训）：主体改账户后，历史流水仍要打进当初收款的那个账户；
> 退款尤其——从新账户扣会让两个账户各错一笔且方向相反。

### 4.6 关联带来的读侧（这是「建立关联」的收益）

| 能力 | 实现 |
|---|---|
| 同一法人总收益 | `loc_venue.operator_no` 非空时，合并该 venue 的进场分成与该 operator 的运营分润 |
| 收款账户复用 | 建账户时若 venue 已关联 operator，提示「复用已有账户」而非重复录入 |
| 结算页合并展示 | 结算单列表按法人聚合，标注「本主体同时是场地方」 |

---

## 5. 测试策略

| # | 测试 | 断言 |
|---|---|---|
| 1 | `AuditMetaObjectHandlerTest` | `updatedBy` 已有值也被覆盖；append 实体不抛异常 |
| 2 | `PlatformOperatorTest` | `PLATFORM` 行恒存在且唯一；不可停用/归档；`agt_account` 不得挂 `PLATFORM` |
| 3 | `agent_cannot_see_platform_direct_rows`（**已有**） | 改造后**照样通过**——这是本次最重要的回归 |
| 4 | `ShareGenerationByBasisTest` | 一个站点两个主体三条责任 → 生成三条 `share_record`，`basis` 各异；重投不增行 |
| 5 | `PayoutAccountTest` | 同一受益方恰好一个默认账户；提现单落账户快照；改账户后历史提现快照不变 |
| 6 | `VenueOperatorLinkTest` | 一个 operator 最多被一个 venue 认领；venue 可不关联 |
| 7 | `SchemaConventionTest`（规范 §五） | 新建 3 张表带齐五件套；唯一键不含**应为 NOT NULL** 的可空列 |

---

## 6. 风险

| 风险 | 缓解 |
|---|---|
| V47 存量有 NULL 未回填就 `MODIFY` | 迁移内先 `UPDATE` 后 `MODIFY`；预发库先跑（**CI 只重放 DDL 不跑 DML**，ai-shop 踩过两次） |
| V49 换唯一键的空窗 | 先 `ADD` 新键、再 `DROP` 旧键；或停机窗口 |
| 并行会话撞迁移号 | 本方案从 **V46** 起；动手前再 `ls` 一次最大号 |
| `ShareGenerator` 改写影响在跑的分润 | 先补 §5 #4 测试锁住现有行为，再改 |
| 商户表提前建但无消费方 | **不建**（V51 延后），避免又一处「有能力没消费方」 |

---

## 7. 实现任务（分四批，每批可独立验收）

| 批 | 内容 | 迁移 | 级 |
|:-:|---|---|:-:|
| **B1** | 修 `updateFill` + 测试 | — | **P0** |
| **B2** | 运营主体：`operator_type` + `PLATFORM` 哨兵 + 6 列去 NULL + §4.3 五处 | V46 · V47 | **L1** |
| **B3** | 收款账户 + 场地方关联 + 提现落快照 + §4.6 读侧 | V48 | **L1**（解 L0 阻塞「提现不知道打给谁」） |
| **B4** | 幂等键修复 + `basis` + 责任表 + `ShareGenerator` 改写 | V49 · V50 | L1 |
| **B5** | **多主体账号**：`agt_account` 换键 + 登录按手机号查全部成员关系 + `X-Operator-No` 越权闸 + 数据范围锚点改 `currentOperatorNo` | V52 | **L1** |
| **B6** | **入驻双入口**：`agt_apply` + 自助免鉴权端点（OTP）+ 运营端队列/受理/审核 + 激活派生事务 | V53 | **L1** |
| B7 | 商户 `pay_merchant` | V51 | 随 `CHANNEL_SPLIT` |

**B1 必须先做**——它独立、零依赖，且每拖一天审计数据就多错一天。
**B4 依赖 B2**（责任表要求主体非空）。
**B6 依赖 B5 与 B3**：激活派生要写属主成员关系行（B5 的新键）与收款账户占位（B3 的表）；
反过来说，**B6 单独上线会造出一批「审核通过但拿不到钱」的代理商**——正是 ADR-030 §3.6 那个教训的复现。

---

## 8. 待确认

1. 哨兵 id 用 `'PLATFORM'`？
2. ADR-027 的 `agent_type` 同步更名 `operator_type`（那份已「已接受」）？
3. 平台净收入：残值自动写（本方案）还是配 `share_rule(PLATFORM)`？
4. `loc_venue.operator_no` 的关联由谁维护——建场地方时选，还是建运营主体时反向选？
5. **入驻自助入口的落点**：ops-web 登录页旁的公开申请页、C 端「成为合伙人」入口，还是两个都要？
   （端点是同一个，这问的是**放哪个壳**；接口层不受影响）
6. **同手机号至多一张在途申请**——本方案用生成列 `active_key` 强制串行。若业务要允许并发申请多个主体，改表达式即可
7. **代建是否允许同一人录入并放行**：首期允许（配置项，非硬编码）；规模化后可要求换人复核
