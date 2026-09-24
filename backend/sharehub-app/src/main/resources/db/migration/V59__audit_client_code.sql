-- ============================================================
-- ShareHub · 审计留痕加「从哪个端做的」（TDD-认证隔离与日志三方案 T1-3）
--
-- 【解决什么】
-- 今天 iam_audit_log 记了「谁、做了什么、从哪个 IP」，但记不出**从哪个端**。
-- 而运营端与代理端共用 /api/** 与同一套审计：一条「改了分润规则」的记录，
-- 分不清是运营替代理改的，还是代理自己改的。这恰恰是结算争议时第一个要问的。
--
-- 【为什么不从请求头取】
-- 最省事的做法是让前端传 X-Client。但**审计字段如果能被被审计方自己设置，
-- 比没有这个字段更糟** —— 它会让人以为那一列可信，而实际上改一个请求头就能伪造。
-- 所以这一列从会话的 realm 派生（STAFF→OPS / AGENT→AGENT / CONSUMER→MP），
-- realm 是服务端在发令牌时写的，调用方碰不到。
--
-- 【为什么可空】
-- 历史行没有这个信息，**填任何值都是编造**。留 NULL，查询时它就诚实地表示
-- 「这条记录产生时系统还不记这个」——比回填一个猜出来的 'OPS' 好。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE iam_audit_log
  ADD COLUMN client_code VARCHAR(16) NULL
      COMMENT '从哪个端发起：OPS 运营端 / AGENT 代理端 / MP C端。由会话 realm 派生，不采信请求头。NULL=该列上线前的历史行'
      AFTER actor_name;

-- 审计的常用查法是「某个端最近做了什么」，与 created_at 一起走一个索引。
CREATE INDEX idx_audit_client_created ON iam_audit_log (client_code, created_at);
