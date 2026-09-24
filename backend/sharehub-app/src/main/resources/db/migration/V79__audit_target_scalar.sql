-- ============================================================
-- ShareHub · 审计的「操作对象」两列存成了 JSON 字符串字面量
--
-- 接 V77：那次核实 json_valid 约束时留了个尾巴 ——
-- iam_audit_log.target_type / target_no 同样是标量列被标成了 JSON，
-- 但两侧代码都在迁就它，要连代码和存量一起改，所以单独一条。
--
-- 【现状】库里存的是 `"cabinets"` / `"CAB1005"`（**带引号的 JSON 字符串字面量**），
-- 因为拦截器为了过 CHECK 把值 json(...) 包了一层，读侧再 unjson(...) 拆回来。
-- 一包一拆之间，**中间那层（按值过滤）是拆不掉的**：
--     SELECT ... WHERE target_type = 'iam'      → 0 行
--     SELECT ... WHERE target_type = '"iam"'    → 300 行
-- AuditLogServiceImpl.page 的 targetType 参数正是按裸值 eq 的。
--
-- 今天这个参数还没接到 /api/platform/audit-logs（端点只传了 keyword），
-- 所以还没人被坑到 —— 但服务端签名已经有它了，**接上去的那天会返回空列表**，
-- 而空列表和「确实没有」长得一模一样。keyword 搜索没事，因为那是 LIKE 子串匹配。
--
-- 【改法】三层一起：本迁移去引号 + 摘掉 CHECK；拦截器不再包 json；
-- 读侧的 unjson 保留（别的环境在跑到这条迁移之前仍有带引号的行，
-- 而它对裸值是无操作的）。
--
-- ⚠️ CHECK 写在列定义里，DROP CONSTRAINT 对它**无效且不报错**（V77 踩过两次），
-- 所以用 MODIFY COLUMN 原样重写、只摘 CHECK 段。
-- 验证同样不能只看迁移有没有报错，要回头查：
--     SELECT COUNT(*) FROM iam_audit_log WHERE target_type LIKE '"%"';   -- 应为 0
-- ============================================================
SET NAMES utf8mb4;

-- ⚠️ 顺序不能反：CHECK 还在的时候把 `"cabinets"` 改成 `cabinets`，
--    这一行自己就违反了 json_valid —— 本迁移第一版正是这么写的，直接 SQL State 23000。

-- ① **先摘 CHECK** 摘掉列定义里的 CHECK（定义从 SHOW CREATE TABLE 原样抄，只去 CHECK 段）
ALTER TABLE iam_audit_log MODIFY COLUMN `target_type` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE iam_audit_log MODIFY COLUMN `target_no` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';

-- ② 再去引号 存量去引号：JSON 字符串字面量 → 业务值（非字符串 JSON 不动）
UPDATE iam_audit_log SET `target_type` = JSON_UNQUOTE(`target_type`)
 WHERE `target_type` IS NOT NULL AND `target_type` LIKE '"%"' AND JSON_VALID(`target_type`);
UPDATE iam_audit_log SET `target_no` = JSON_UNQUOTE(`target_no`)
 WHERE `target_no` IS NOT NULL AND `target_no` LIKE '"%"' AND JSON_VALID(`target_no`);
