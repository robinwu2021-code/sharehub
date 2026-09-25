-- 差错逐笔处置留痕落到差错行上。
--
-- 此前处置只写批次（recon_task.handle_result / handle_note / handled_by），
-- 差错行只翻一个 resolved 标记。而 resolve 端点本来就收 diffId（逐笔处置是支持的路径），
-- 于是逐笔处置时**后一笔覆盖前一笔的批次留痕**：
--   把 A 判成 verify「凭证号 X」，再把 B 判成 channel「渠道少记」
--   → 批次上只剩 channel / 渠道少记，A 的结论和说明都没了。
-- 同一个类注释里已经为「重复处置整批会覆盖留痕」加过防护，逐笔这条漏了。
--
-- 事后推不回来：结论是人的判断，不是能从金额算出来的东西。
ALTER TABLE recon_diff
  ADD COLUMN IF NOT EXISTS handle_result VARCHAR(24)  NULL COMMENT '处置结论 VERIFIED_OK/PLATFORM_ERROR/CHANNEL_ERROR/COMPENSATED',
  ADD COLUMN IF NOT EXISTS handle_note   VARCHAR(512) NULL COMMENT '处置说明(金额/凭证号/对接人)',
  ADD COLUMN IF NOT EXISTS handled_by    VARCHAR(64)  NULL COMMENT '处置人(服务端按会话回填,不收前端传参)',
  ADD COLUMN IF NOT EXISTS handled_at    DATETIME(3)  NULL COMMENT '处置时间';

-- 注释单独无条件落一次：ADD COLUMN IF NOT EXISTS 在列已存在时整条跳过，注释也跟着不生效
ALTER TABLE recon_diff
  MODIFY COLUMN handle_result VARCHAR(24)  NULL COMMENT '处置结论 VERIFIED_OK/PLATFORM_ERROR/CHANNEL_ERROR/COMPENSATED',
  MODIFY COLUMN handle_note   VARCHAR(512) NULL COMMENT '处置说明(金额/凭证号/对接人)',
  MODIFY COLUMN handled_by    VARCHAR(64)  NULL COMMENT '处置人(服务端按会话回填,不收前端传参)',
  MODIFY COLUMN handled_at    DATETIME(3)  NULL COMMENT '处置时间';
