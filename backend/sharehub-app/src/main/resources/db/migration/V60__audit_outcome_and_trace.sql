-- ============================================================
-- ShareHub · 审计记「成没成」与「哪一次请求」（TDD T3-1 / 三份合一）
--
-- 【outcome：没做成的也要留痕】
-- 今天的拦截器遇到 >=400 直接 return，理由是「400/403 是没做成，记进审计会让
-- 谁改了什么失真」。这个顾虑是对的，但结论下反了 ——
-- **被拒绝的操作恰恰是最该留痕的那一类**：有人拿着没有权限的账号反复点某个
-- 危险操作，这是安全信号；而现在它不留任何痕迹，事后查「谁试过改分润规则」
-- 得到的答案是「没有人」。
--
-- 正确的做法不是不记，是记下来并**标清楚成没成**，让查询能分开这两件事。
--
-- 历史行一律 SUCCESS，这不是猜 —— 拦截器此前只在 2xx 时才写，
-- 库里每一行都确实是成功的操作。（对照 V59 的 client_code 留 NULL：
-- 那个信息历史行**真的没有**，填什么都是编造。两者的区别就在这里。）
--
-- 【trace_id：审计与运行日志之间那根线】
-- 审计回答「谁改了这条分润规则」，运行日志回答「那次请求到底发生了什么」。
-- 两边各有一半，而**此前没有任何东西把它们连起来** —— 只能拿时间戳去猜。
-- 接口出参里早就有 requestId 字段，但没有对应的列，所以一直出空串：
-- 界面上有这一栏、永远是空的，比没有这一栏更让人困惑。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE iam_audit_log
  ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
      COMMENT 'SUCCESS 成功 / DENIED 无权限被拒 / FAILED 出错未完成。历史行均为 SUCCESS（此前只记成功的）'
      AFTER action,
  ADD COLUMN trace_id VARCHAR(32) NULL
      COMMENT '那次请求的链路 id，用于跳到运行日志（%X{traceId}）。NULL=该列上线前的历史行'
      AFTER outcome;

-- 「最近有谁被拒绝过」是这一列的主要查法，和时间一起走一个索引。
CREATE INDEX idx_audit_outcome_created ON iam_audit_log (outcome, created_at);
-- 反向：拿着一个 traceId 回查它对应的操作。
CREATE INDEX idx_audit_trace ON iam_audit_log (trace_id);
