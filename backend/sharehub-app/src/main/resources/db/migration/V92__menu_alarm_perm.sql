-- 告警四个菜单叶换权限码：workorder:wo:read → workorder:alarm:read。
--
-- 此前整个告警域（三个菜单叶 + 受理这个写动作）都挂在**工单的只读码**上。
-- 菜单侧的后果是：谁能看工单谁就能看告警，两块的可见性从此绑死，
-- 将来想把告警只给运维看就得连工单一起改。
-- 动作侧的后果更硬 —— 那个码的持有者含 VIEWER，**只读角色能受理告警**。
--
-- 只改这四行，不重新生成整张 iam_menu：全量生成会把别的线与 nav.ts 之间
-- 可能存在的其它差异一起卷进来，而那些不归本次判断。
UPDATE iam_menu SET perm = 'workorder:alarm:read'
 WHERE path IN ('/alarms', '/alarms?tab=notices', '/alarms?tab=codes', '/alarms?tab=rules')
   AND perm = 'workorder:wo:read';
