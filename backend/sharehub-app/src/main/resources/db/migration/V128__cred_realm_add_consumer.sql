-- ════════════════════════════════════════════════════════════════════
-- cred_credential.realm 词表补 CONSUMER
--
-- 这张表建于 V114 时词表写的是 'STAFF/AGENT'。2026-09-26 起 C 端也用它
-- （realm=CONSUMER，见 TDD-C端密码登录与凭据）—— 而列注释是本仓库判定
-- 「库里这个值算不算合法」的裁定依据（StoredValueInVocabularyTest）。
-- 不补的话，库里存着一列自己都没声明的值，卡口报红是对的。
--
-- ⚠️ 用无条件 MODIFY 而不是 ADD COLUMN IF NOT EXISTS：后者在列已存在时
-- **整条语句被跳过，注释也跟着不生效**（CLAUDE.md 记过这个坑，V87 踩过一次）。
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE cred_credential
    MODIFY COLUMN realm VARCHAR(16) NOT NULL COMMENT 'STAFF/AGENT/CONSUMER';
