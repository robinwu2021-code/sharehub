-- 一期跳过「合同 / 勘测」两道门禁（用户 2026-10-28：现在都不用管合同、勘测，一期跳过这个环节，平台运营直接操作）。
--
-- 影响：设备上线门禁的 CONTRACT / SURVEY 两项、站点开业清单的同两项、站点恢复营业的合同前置。
-- 门禁项仍然列出（写「一期跳过」并判通过），不删 —— 二期把本参数改成 1 即恢复强制，不用改代码。
INSERT IGNORE INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'site.venue_gate.enabled', '合同 / 勘测门禁：1 强制，0 跳过（一期跳过）', '0', '设备');
