-- 发票开具与作废留痕（前端契约 Invoice）
--
-- **作废必须有原因**：没有原因的作废等于没有记录 —— 税务稽查时无法解释这张票为什么废了。
-- **开票人/作废人取登录态**，与提现审批同一条红线：信前端传的操作人，留痕就可伪造。

SET NAMES utf8mb4;

ALTER TABLE fin_invoice
  ADD COLUMN IF NOT EXISTS issued_by      VARCHAR(64)  NULL COMMENT '开票人（服务端取登录态）',
  ADD COLUMN IF NOT EXISTS void_reason    VARCHAR(255) NULL COMMENT '作废原因；作废时必填',
  ADD COLUMN IF NOT EXISTS voided_at      DATETIME(3)  NULL COMMENT '作废时间',
  ADD COLUMN IF NOT EXISTS voided_by      VARCHAR(64)  NULL COMMENT '作废人（服务端取登录态）',
  ADD COLUMN IF NOT EXISTS invoice_code   VARCHAR(32)  NULL COMMENT '发票代码',
  ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(32)  NULL COMMENT '发票号码',
  ADD COLUMN IF NOT EXISTS source_type    VARCHAR(16)  NULL COMMENT '来源单据类型 ORDER/SETTLEMENT',
  ADD COLUMN IF NOT EXISTS source_no      VARCHAR(36)  NULL COMMENT '来源单据编号';
