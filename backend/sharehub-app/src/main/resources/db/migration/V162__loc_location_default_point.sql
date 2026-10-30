-- 站点与点位（用户 2026-10-28：默认绑到商家的默认站点、默认点位；商家可以有多个站点、多个点位）。
-- 技术设计 §2.3：每个站点恰好一个默认点位，建站时同事务建。
--
-- 写法：不用 MariaDB 的 `ADD COLUMN IF NOT EXISTS`，本机 MySQL 9.7 与 MariaDB 都能跑。

ALTER TABLE loc_location
  ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '站点的默认点位（建站时自动建；绑定不指定点位时落这里）' AFTER status;

-- 「每站至多一个默认点位」用生成列 + 唯一键：可空唯一键不约束 NULL，正好让非默认的、已删的行都不占位
ALTER TABLE loc_location
  ADD COLUMN default_key VARCHAR(36) GENERATED ALWAYS AS (IF(is_default = 1 AND deleted = 0, site_no, NULL)) STORED
      COMMENT '派生：默认点位取所属站点号，其余为空 —— 唯一键靠它保证每站至多一个默认点位' AFTER is_default,
  ADD UNIQUE KEY uk_loc_default (default_key);

-- ── 存量回填 ──
-- ① 点位的商家按站点回填：此前新建点位从不写 agent_no，商家因此看不到自己站点上的新点位
UPDATE loc_location l JOIN loc_site s ON s.site_no = l.site_no
   SET l.agent_no = s.agent_no
 WHERE l.deleted = 0 AND NOT (l.agent_no <=> s.agent_no);

-- ② 已有点位的站点：最早建的那个在用点位升为默认（不另造一个空点位，免得每站多出一个没人用的）
UPDATE loc_location l
  JOIN (SELECT site_no, MIN(id) AS first_id FROM loc_location
         WHERE deleted = 0 AND archived_at IS NULL GROUP BY site_no) f ON f.first_id = l.id
   SET l.is_default = 1;

-- ③ 一个点位都没有的站点：补一个默认点位。编号用「LOCD + 站点号」—— 迁移里没有取号器，
--    这个形状与取号器的「LOC + 数字」不相交，不会撞号
INSERT INTO loc_location (location_no, tenant_id, site_no, agent_no, name, site_name, status, is_default,
                          created_at, created_by, updated_at, updated_by)
SELECT CONCAT('LOCD', s.site_no), s.tenant_id, s.site_no, s.agent_no, '默认点位', s.name, 'ACTIVE', 1,
       NOW(3), 'SYSTEM', NOW(3), 'SYSTEM'
  FROM loc_site s
 WHERE s.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM loc_location l WHERE l.site_no = s.site_no AND l.deleted = 0 AND l.archived_at IS NULL);
