-- ============================================================
-- ShareHub · 菜单：「系统管理」（OM11）—— 员工与权限 + 系统设置 + 运营管理基础资料 + 流程配置与统计
--
-- 【为什么】2026-10-28 用户确认运营端菜单按运营顺序排列（docs/requirements/运营端菜单清单-按运营顺序.md）。
-- 系统管理回答「谁能做什么、系统按什么规则跑」，分五组：
--   员工与权限：员工 · 组织架构 · 角色权限 · 菜单管理 · 操作审计 · 绩效报表（原「员工与权限」L1）
--   规则与参数：业务规则 · 登录设置 · 系统参数 · 税率与发票（暂留，待发票模块）· 多国家市场 · OpenAPI 应用
--   基础资料：参数字典 · 地区库 · 银行 · 品牌 · 问题 · 应用版本 · 公告（后五项来自运营管理）
--   消息触达：通知模板 · 发送记录 · 触达拉黑
--   流程：流程配置 · 运营配置 · 接入管理 · 流程实例 · 流程统计（来自流程与工单；矩阵组名「流程配置」与同名叶撞 i18n 键，改「流程」）
-- 「员工与权限」「运营管理」两个 L1 撤销；「系统设置」更名「系统管理」，落地页改员工，认领 /employees、/operation、
-- 流程配置与统计五个路径。「流程与工单」只剩待办 / 审批 / 审核队列 / 工单工作台（归工作台 OM00，届时迁走）。
-- 「备份与恢复」（OM11-21）没有后端，不建叶。
--
-- 【做法】四段叶子按 id 打散后按 path 赋最终编号、所属段与分组（重复执行结果相同）；按行更新保留 id、
-- created_at 与在线设置过的 visible。删两个 L1 后其后 L1 排序前移（L1 的 sort 是 nav.ts 数组下标 + 1）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '系统管理', name_en = 'System', name_ar = 'إدارة النظام', path = '/employees?tab=employees',
       match_paths = '["/system","/employees","/operation","/flow/processes","/flow/settings","/flow/access","/flow/instances","/flow/stats"]'
 WHERE menu_no = 'M_system';

UPDATE iam_menu SET menu_no = CONCAT('M_tmp172_', id) WHERE parent_no IN ('M_org', 'M_system', 'M_operation', 'M_flow');

UPDATE iam_menu SET menu_no = 'M_system__1', parent_no = 'M_system', sort = 1, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=employees';
UPDATE iam_menu SET menu_no = 'M_system__2', parent_no = 'M_system', sort = 2, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=org';
UPDATE iam_menu SET menu_no = 'M_system__3', parent_no = 'M_system', sort = 3, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=roles';
UPDATE iam_menu SET menu_no = 'M_system__4', parent_no = 'M_system', sort = 4, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=menus';
UPDATE iam_menu SET menu_no = 'M_system__5', parent_no = 'M_system', sort = 5, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=audit';
UPDATE iam_menu SET menu_no = 'M_system__6', parent_no = 'M_system', sort = 6, group_name = '员工与权限'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/employees?tab=performance';
UPDATE iam_menu SET menu_no = 'M_system__7', parent_no = 'M_system', sort = 7, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=rules';
UPDATE iam_menu SET menu_no = 'M_system__8', parent_no = 'M_system', sort = 8, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=login';
UPDATE iam_menu SET menu_no = 'M_system__9', parent_no = 'M_system', sort = 9, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=params';
UPDATE iam_menu SET menu_no = 'M_system__10', parent_no = 'M_system', sort = 10, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=tax';
UPDATE iam_menu SET menu_no = 'M_system__11', parent_no = 'M_system', sort = 11, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=markets';
UPDATE iam_menu SET menu_no = 'M_system__12', parent_no = 'M_system', sort = 12, group_name = '规则与参数'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=openapi';
UPDATE iam_menu SET menu_no = 'M_system__13', parent_no = 'M_system', sort = 13, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=dict';
UPDATE iam_menu SET menu_no = 'M_system__14', parent_no = 'M_system', sort = 14, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=region';
UPDATE iam_menu SET menu_no = 'M_system__15', parent_no = 'M_system', sort = 15, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/operation/banks';
UPDATE iam_menu SET menu_no = 'M_system__16', parent_no = 'M_system', sort = 16, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/operation/brands';
UPDATE iam_menu SET menu_no = 'M_system__17', parent_no = 'M_system', sort = 17, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/operation/problems';
UPDATE iam_menu SET menu_no = 'M_system__18', parent_no = 'M_system', sort = 18, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/operation/app-versions';
UPDATE iam_menu SET menu_no = 'M_system__19', parent_no = 'M_system', sort = 19, group_name = '基础资料'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/operation/notices';
UPDATE iam_menu SET menu_no = 'M_system__20', parent_no = 'M_system', sort = 20, group_name = '消息触达'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=notify';
UPDATE iam_menu SET menu_no = 'M_system__21', parent_no = 'M_system', sort = 21, group_name = '消息触达'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=notify-log';
UPDATE iam_menu SET menu_no = 'M_system__22', parent_no = 'M_system', sort = 22, group_name = '消息触达'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/system?tab=notify-blacklist';
UPDATE iam_menu SET menu_no = 'M_system__23', parent_no = 'M_system', sort = 23, group_name = '流程'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/processes';
UPDATE iam_menu SET menu_no = 'M_system__24', parent_no = 'M_system', sort = 24, group_name = '流程'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/settings';
UPDATE iam_menu SET menu_no = 'M_system__25', parent_no = 'M_system', sort = 25, group_name = '流程'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/access';
UPDATE iam_menu SET menu_no = 'M_system__26', parent_no = 'M_system', sort = 26, group_name = '流程'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/instances';
UPDATE iam_menu SET menu_no = 'M_system__27', parent_no = 'M_system', sort = 27, group_name = '流程'
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/stats';
UPDATE iam_menu SET menu_no = 'M_flow__1', parent_no = 'M_flow', sort = 1
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/todo';
UPDATE iam_menu SET menu_no = 'M_flow__2', parent_no = 'M_flow', sort = 2
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/approvals';
UPDATE iam_menu SET menu_no = 'M_flow__3', parent_no = 'M_flow', sort = 3
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/queue';
UPDATE iam_menu SET menu_no = 'M_flow__4', parent_no = 'M_flow', sort = 4
 WHERE menu_no LIKE 'M_tmp172_%' AND path = '/flow/tickets';

-- 两个 L1 的叶子已全部迁走，删除这两个 L1 行；其后 L1 排序前移
DELETE FROM iam_menu WHERE menu_no IN ('M_org', 'M_operation') AND parent_no IS NULL;
UPDATE iam_menu SET sort = 5 WHERE menu_no = 'M_device';
UPDATE iam_menu SET sort = 6 WHERE menu_no = 'M_workorder';
UPDATE iam_menu SET sort = 7 WHERE menu_no = 'M_venue';
UPDATE iam_menu SET sort = 8 WHERE menu_no = 'M_agent';
UPDATE iam_menu SET sort = 9 WHERE menu_no = 'M_order';
UPDATE iam_menu SET sort = 10 WHERE menu_no = 'M_settlement';
UPDATE iam_menu SET sort = 11 WHERE menu_no = 'M_finance';
UPDATE iam_menu SET sort = 12 WHERE menu_no = 'M_user';
UPDATE iam_menu SET sort = 13 WHERE menu_no = 'M_report';
UPDATE iam_menu SET sort = 14 WHERE menu_no = 'M_system';
UPDATE iam_menu SET sort = 15 WHERE menu_no = 'M_flow';
