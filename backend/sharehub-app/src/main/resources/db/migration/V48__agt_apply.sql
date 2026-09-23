-- ============================================================
-- ShareHub · 入驻申请：商家自助注册与运营商代建**同落一张表**
--
-- 依据：ADR-030 §三（2026-09-23 用户定：「商家可以注册，运营商也可以代建，
--       条件相同，商家注册，也需要运营商审核通过」）
-- 对应 TDD 的「V53」——实际编号取 V48（见 V47 头部说明）。
--
-- 「条件相同」在表上的含义：**不按 source 分叉**。
-- 一张表、一个状态机、一套必填校验（必填由 operator_type 决定，不由 source 决定）。
-- 差别只有 source 与 submitted_by 两个字段。
-- ============================================================

CREATE TABLE IF NOT EXISTS agt_apply (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  apply_no       VARCHAR(36)  NOT NULL                 COMMENT '申请单业务键 AP*',
  source         VARCHAR(16)  NOT NULL                 COMMENT 'SELF_SERVICE 商家自助 / OPS_CREATED 运营商代建；**由服务端按令牌判定，客户端不得传**',
  phone_hash     VARCHAR(64)  NOT NULL                 COMMENT 'HMAC(规范化手机号)；已存在不是重复注册，是多主体申请',
  phone_mask     VARCHAR(32)  NOT NULL                 COMMENT '仅显示',
  phone_enc      VARBINARY(256)   NULL,
  email_hash     VARCHAR(64)  NOT NULL                 COMMENT 'HMAC(lower(trim(邮箱)))；用户 2026-09-23 定必填',
  email_mask     VARCHAR(64)  NOT NULL                 COMMENT '仅显示',
  email_enc      VARBINARY(512)   NULL,
  hash_ver       TINYINT      NOT NULL DEFAULT 1       COMMENT 'pepper 版本，同 agt_principal',
  principal_no   VARCHAR(36)      NULL                 COMMENT '命中已有自然人时回填；空=新人',
  operator_name  VARCHAR(128) NOT NULL                 COMMENT '拟建主体名称',
  operator_type  VARCHAR(16)  NOT NULL                 COMMENT 'AGENT / CITY_PARTNER；**决定必填项，source 不决定**',
  region_scope   JSON             NULL                 COMMENT '申请辖域',
  share_rate     DECIMAL(5,4)     NULL                 COMMENT '拟定默认分润比例，审核时定',
  payload        JSON             NULL                 COMMENT '资质材料；敏感件只存 sharehub_pii 引用，不落明文（PDPL）',
  status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED',
  reject_reason  VARCHAR(512)     NULL                 COMMENT '驳回原因；**原样回显给申请人**，不只给运营看',
  submitted_by   VARCHAR(36)      NULL                 COMMENT '自助=phone_hash 前 12 位；代建=经办 employee_no',
  submitted_at   DATETIME(3)      NULL,
  reviewed_by    VARCHAR(36)      NULL                 COMMENT '审核人；代建一键通过时与 submitted_by 同值，**照写不省**',
  reviewed_at    DATETIME(3)      NULL,
  operator_no    VARCHAR(36)      NULL                 COMMENT '通过后回写，申请↔主体双向可查',
  -- 见下方「为什么不用可空唯一键」
  active_key     VARCHAR(64)  AS (CASE WHEN status IN ('DRAFT','SUBMITTED','REVIEWING')
                                       THEN phone_hash ELSE apply_no END) STORED
                                                       COMMENT '在途取 phone_hash、终态取 apply_no；全程非空，约束真实生效',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN'  COMMENT '历史遗留常量，恒 MAIN（ADR-026）',
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

-- ============================================================
-- 【为什么 active_key 用生成列，而不是「终态置 NULL + 可空唯一键」】
--
-- 想表达的约束是「同一手机号同时至多一张在途申请」。
-- 直觉写法是终态时把列置 NULL，靠 UNIQUE 只约束非空行 ——
-- **而 MySQL/MariaDB 的 UNIQUE 不约束 NULL，可空列进唯一键等于没进。**
-- 这正是 share_record 幂等键失效的同一个根因（ADR-029 §1.1 表第 1 行）。
--
-- 生成列让它全程非空：在途时取 phone_hash（同手机号第二张在途会撞键），
-- 终态时取 apply_no（天然唯一，互不相撞）。
--
-- 「至多一张在途」是**串行建主体**的选择：审核是人工的，并发申请只给审核员
-- 制造重复判断。要放开的话改这个表达式即可，不动其它。
--
-- 【phone_hash 必须是 VARCHAR(64) 不能是 CHAR(64)】—— 实测（MariaDB 12.2.2）
-- CASE 的两个分支类型不同（CHAR vs VARCHAR）时，结果字符集要做合并，
-- 而生成列要求表达式结果确定，于是报 ERROR 1901
-- 「Function or expression … cannot be used in the GENERATED ALWAYS AS clause」。
-- 两个分支同为 VARCHAR 就通过。**改回 CHAR 会让这张表建不出来。**
-- ============================================================
