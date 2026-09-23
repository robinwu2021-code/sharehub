-- ============================================================
-- ShareHub · 站点上的伙伴责任（ADR-027 §三 / TDD-A2 第 1 批）
--
-- 【解决什么】
-- 今天一个代理商在一个站点只能拿**一个比例**（share_rule(AGENT, agent_no) 一条）。
-- 而一个站点上实际有四件事各有其人：谁出的钱、谁找来的、谁在维护、谁牵的线。
-- 压成一个比例的代价不是不精确，是**不可追溯** ——
-- 结算争议时说不清「这 8% 里几个点是运维、几个点是出资」。
--
-- 【一行一责任，不用 JSON 数组列】
-- ai-shop 的教训：责任存成数组列后，撤销单个角色变成读-改-写，
-- 两人同时改会互相覆盖，而覆盖的后果是**有人多分了钱且不报错**。
-- 多行方案里撤销就是删一行，并发天然安全。
--
-- 【loc_site.agent_no 保留，不动】
-- 语义收窄为「主经营方」= 承担 OPERATE 的那个伙伴。它仍是数据范围与工单派单的锚点，
-- 现有查询一行不改 —— 这是本批最省事的一处。只有 REFER 的站点 agent_no 为空（= 直营运维）。
--
-- 【责任词表 4 档，不含 MANAGE】
-- 2026-09-23 定：效果管理与运维先合并进 OPERATE。首批伙伴多半两件都做，
-- 分开只会让每站多配一行、每单多一条记录，而受益方与比例完全一样。
-- **要分的时候再加一档不迁移任何数据；反向（把合并过的拆回去）才要迁。**
-- ============================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS loc_site_agent (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id      VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  site_no        VARCHAR(36) NOT NULL COMMENT '→ loc_site.site_no',
  agent_no       VARCHAR(36) NOT NULL COMMENT '→ agt_agent.agent_no',
  role           VARCHAR(16) NOT NULL COMMENT 'INVEST 出资 / DEVELOP 拓展 / OPERATE 运维 / REFER 牵线',
  rule_no        VARCHAR(36)     NULL COMMENT '该责任对应的分润规则；空 = 用登记类型默认费率',
  effective_from DATETIME(3)     NULL COMMENT '生效起；空 = 立即',
  effective_to   DATETIME(3)     NULL COMMENT '生效止；空 = 长期',
  remark         VARCHAR(256) NOT NULL DEFAULT '' COMMENT '为什么是这个责任——结算争议时的人话依据',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(36)     NULL,
  updated_by     VARCHAR(36)     NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  deleted        TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- 同一个人在同一个站点，同一种责任只能有一行。撤销 = 删这一行。
  UNIQUE KEY uk_site_agent_role (site_no, agent_no, role),
  KEY idx_sa_site (site_no),
  KEY idx_sa_agent (agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点上的伙伴责任（ADR-027）';

-- 存量回填：今天 loc_site.agent_no 上挂着的代理，就是这个站点的运维方。
-- 不回填的话，A2-2 切到「按责任行生成分润」那天，这些站点会一条责任行都没有 ——
-- 虽然有回落分支兜着，但那意味着切换后**所有存量站点都走回落**，等于没切。
INSERT IGNORE INTO loc_site_agent (tenant_id, site_no, agent_no, role, remark, created_by, updated_by)
SELECT COALESCE(s.tenant_id, 'MAIN'), s.site_no, s.agent_no, 'OPERATE',
       '由 loc_site.agent_no 回填（V52）', 'SYSTEM', 'SYSTEM'
  FROM loc_site s
 WHERE s.agent_no IS NOT NULL AND s.agent_no <> ''
   AND COALESCE(s.deleted, 0) = 0;
