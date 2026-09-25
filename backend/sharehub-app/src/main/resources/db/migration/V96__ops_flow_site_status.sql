SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 站点状态与门店生命周期合并（2026-09-25 定；TDD-运营核心流程/03）
--
-- 状态从 ACTIVE/PAUSED 扩为五态，存量值与含义不变；默认值保留 ACTIVE（旧写入路径不带 status），
-- 新建站点由 SiteService 显式写 PREPARING。
--
-- 运维责任人：代理运维复用 loc_site_agent(role=OPERATE)，站点上只加平台员工责任人，
-- 同一事实不存两处。
--
-- 状态日志新建表而不复用 loc_site_lifecycle_log：旧表 from_stage/to_stage 的注释是阶段词表，
-- 混写状态值会让存量词表卡口红。旧两表停止写入，保留作历史。
-- ============================================================
ALTER TABLE loc_site
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'PREPARING 筹备中 / ACTIVE 营业中 / PAUSED 暂停营业 / WITHDRAWING 撤场中 / CLOSED 已关闭',
  ADD COLUMN IF NOT EXISTS ops_employee_no     VARCHAR(36)  NULL COMMENT '平台员工运维责任人（无生效 OPERATE 伙伴时使用）',
  ADD COLUMN IF NOT EXISTS first_live_at       DATETIME(3)  NULL COMMENT '首台设备上线时刻',
  ADD COLUMN IF NOT EXISTS pause_until         DATE         NULL COMMENT '预计恢复日',
  ADD COLUMN IF NOT EXISTS withdraw_reason     VARCHAR(512) NULL,
  ADD COLUMN IF NOT EXISTS withdraw_planned_at DATE         NULL,
  ADD COLUMN IF NOT EXISTS withdraw_started_at DATETIME(3)  NULL,
  ADD COLUMN IF NOT EXISTS closed_at           DATETIME(3)  NULL,
  ADD KEY IF NOT EXISTS idx_site_status (status);

CREATE TABLE IF NOT EXISTS loc_site_status_log (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  site_no      VARCHAR(36)  NOT NULL,
  event        VARCHAR(16)  NOT NULL COMMENT 'CREATE 建档 / GO_LIVE 首台上线 / PAUSE 暂停 / RESUME 恢复 / WITHDRAW 发起撤场 / CLOSE 关闭',
  from_status  VARCHAR(16)      NULL COMMENT 'PREPARING / ACTIVE / PAUSED / WITHDRAWING / CLOSED',
  to_status    VARCHAR(16)  NOT NULL COMMENT 'PREPARING / ACTIVE / PAUSED / WITHDRAWING / CLOSED',
  operator     VARCHAR(36)  NOT NULL COMMENT '员工号；系统触发为 SYSTEM',
  reason       VARCHAR(512)     NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  KEY idx_site_status_log (site_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点状态迁移日志（追加）';
