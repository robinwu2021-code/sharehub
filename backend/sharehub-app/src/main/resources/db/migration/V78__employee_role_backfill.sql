-- ============================================================
-- ShareHub · 回填 iam_employee_role（用户 → 角色这一段此前是空的）
--
-- 【这一段一直是断的】
-- 表和同步代码（EmployeeServiceImpl「保存后同步主角色」）都在，
-- 但生产的 5 个员工是**种子直灌的**，没走过 service —— 于是
-- iam_employee_role **0 行**，而 iam_employee.role_no（主角色）有值。
--
-- 后果不是「少一张关联表」：**「按用户动态展示菜单」里的「用户」不存在**。
-- 会话的角色来自登录请求或配置，与员工档案毫无关系；
-- 员工级数据范围（V71 那套）也因此匹配不上 —— scopeOf 按 userNo 认人，
-- 而生产会话的 userNo 是 admin，不等于任何 employee_no。
--
-- 【只回填，不发明】
-- 每个员工按他的主角色补一行。不猜「谁还该有第二个角色」——
-- 多角色是管理界面（P4）该给的能力，不是迁移该替人做的决定。
--
-- 幂等：已有的 (employee_no, role_no) 不重复插。
-- 只回填在职的：LEFT 的人补了角色等于把一个离职账号接回权限体系。
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO iam_employee_role (employee_no, role_no)
SELECT e.employee_no, e.role_no
  FROM iam_employee e
 WHERE e.role_no IS NOT NULL AND e.role_no <> ''
   AND e.status = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1 FROM iam_employee_role r
        WHERE r.employee_no = e.employee_no AND r.role_no = e.role_no);
