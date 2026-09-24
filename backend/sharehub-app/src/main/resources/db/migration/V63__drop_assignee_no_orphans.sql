-- ============================================================
-- ShareHub · 删掉三张表上 @TableField 改名留下的孤儿列
--
-- 【怎么来的】
-- 三个实体都写着 `@TableField("assignee_id") private String assigneeNo`，
-- 也就是**字段叫 assigneeNo、列叫 assignee_id**。而 V13 那次对账用的
-- entity-column-diff.py 当时<b>不读 @TableField</b>，于是它按字段名推出
-- 「应该有一列 assignee_no」，判定缺列，V13 就照着建了一份。
--
-- 结果每张表上有两列：真列 assignee_id 装着全部数据，assignee_no 从未被写过。
--
-- 【删之前量过】
--   wo_dispatch         assignee_id 797/797 非空   assignee_no 797/0
--   wo_handle           assignee_id 504/504        assignee_no 504/0
--   wo_inspection_plan  assignee_id   2/2          assignee_no   2/0
-- 并且三个实体都只映射 assignee_id，没有任何代码按 assignee_no 读写
-- （XML、UpdateWrapper 的按列名写入都查过）。
--
-- 【为什么值得删，而不是留着不管】
-- 留着的代价不是几个字节：下一个人看到 wo_handle 上同时有 assignee_id 与
-- assignee_no，会**以为它们各有含义**，然后花时间去找「什么时候写哪一个」——
-- 而答案是「其中一个从来没被写过」。孤儿列误导的是人，不是机器。
--
-- 【别再发生】
-- TableFieldOrphanTest 盯住这个签名：只要实体上有 @TableField 改名，
-- 就不许库里同时存在「按字段名直译出来的那个列」。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE wo_dispatch        DROP COLUMN IF EXISTS assignee_no;
ALTER TABLE wo_handle          DROP COLUMN IF EXISTS assignee_no;
ALTER TABLE wo_inspection_plan DROP COLUMN IF EXISTS assignee_no;

-- 第四个是**卡口自己抓出来的**，手工扫描漏了它：
--   DevOtaRelease 的乐观锁字段 lockVersion 映射到 version_col
--   （注释写着「让位给固件版本列」），而库里还留着一个直译出来的 lock_version。
-- 表是空的、没有任何代码按 lock_version 读写。
-- 写完卡口第一次跑就红，说明这类孤儿光靠人翻是翻不干净的。
ALTER TABLE dev_ota_release    DROP COLUMN IF EXISTS lock_version;
