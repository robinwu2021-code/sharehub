-- OpenAPI 应用密钥重置留痕列
--
-- 背景：运营端已上线「密钥重置」（硬确认需输入 appNo、只回掩码），但后端没有重置端点，
-- 也没有「上次重置时间」可回显 —— 页面上那一列只能空着。
--
-- **只加时间戳，不加明文列**：`openapi_app.app_secret_hash` 的既有注释已经定了口径 ——
-- 明文落 KMS/vault、不入库、不出参。重置的正确形状是「服务端生成 → 只回一次明文给调用方
-- → 库里只留哈希 + 重置时间」。若在库里加一个 app_secret 明文列，等于把凭据的爆炸半径
-- 从 vault 扩大到整个业务库与所有备份，这比「没有重置功能」危险得多。
--
-- 幂等：IF NOT EXISTS，与本目录其它迁移同一约定。

ALTER TABLE openapi_app
  ADD COLUMN IF NOT EXISTS secret_reset_at DATETIME(3) NULL COMMENT '上次密钥重置时间(null=从未重置)';
