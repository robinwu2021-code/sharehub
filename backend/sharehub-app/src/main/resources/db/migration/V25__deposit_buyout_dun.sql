-- 押金买断与欠款催缴（前端契约 DepositRecord）
--
-- **买断与丢失(LOST)的财务处理相反**：买断计收入（用户付钱把设备买下），
-- 丢失计损失。两者都让充电宝离开流通，但账上方向相反，故必须分开记。
--
-- **催缴次数是累加的**：催了几次是判断转买断/坏账的依据，覆盖就丢了这个依据。

SET NAMES utf8mb4;

ALTER TABLE ord_deposit
  ADD COLUMN IF NOT EXISTS buyout_amount   DECIMAL(18,2) NULL COMMENT '买断金额；null=未买断',
  ADD COLUMN IF NOT EXISTS buyout_at       DATETIME(3)   NULL COMMENT '买断时间',
  ADD COLUMN IF NOT EXISTS dun_count       INT           NOT NULL DEFAULT 0 COMMENT '催缴次数（累加，不覆盖）',
  ADD COLUMN IF NOT EXISTS last_dun_at     DATETIME(3)   NULL COMMENT '最近一次催缴时间',
  ADD COLUMN IF NOT EXISTS last_dun_channel VARCHAR(16)  NULL COMMENT '最近催缴渠道 SMS/PUSH/CALL',
  ADD COLUMN IF NOT EXISTS operator_name   VARCHAR(64)   NULL COMMENT '最近操作人（服务端取登录态，不信入参）',
  ADD COLUMN IF NOT EXISTS note            VARCHAR(255)  NULL COMMENT '备注';
