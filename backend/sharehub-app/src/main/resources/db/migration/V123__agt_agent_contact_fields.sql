-- 代理商档案：把自由文本的「联系方式」拆成 联系人 / 手机 / 邮箱。
--
-- ── 为什么拆 ────────────────────────────────────────────────────
-- `agt_agent.contact` 是一个 VARCHAR(64) 自由文本框。生产 9 条的实际内容：
--
--     AG001 North Hub       +97156000000
--     AG002 Marina Partner  +97156000271
--     …（9 条**全是纯手机号**，没有姓名、没有邮箱）
--
-- 也就是说这一列事实上只装了手机号，而「联系人是谁」「邮箱是什么」无处可放。
-- 两个直接后果：
--   · 发对账单 / 合同没有邮箱可用；
--   · **从档案开通代理登录无从下手** —— 登录身份要的是规范化手机号，
--     而自由文本里可能是「Ali +971500001234」这种，取不出来（见 梳理-代理商域.md §四）。
--
-- ── 与 agt_principal 的分工，别混 ───────────────────────────────
-- 这里的 phone/email 是**这家公司的业务联系方式**（找他谈事、发对账单）。
-- 「谁能登录代理端」是 `agt_principal.phone_hash` / `email_hash`，那是另一回事：
-- 带 pepper 的 HMAC + 可逆加密，用于登录反查与唯一约束。
-- 开通登录时把这里的值**灌进去一次**，此后两者各走各的 ——
-- 改档案电话不会改登录方式（改登录要走换绑）。
--
-- ── 存储口径 ────────────────────────────────────────────────────
-- 明文存、出参不掩码，与 `contact` 今天的行为一致（运营要照着打电话）。
-- ⚠️ 这与 `iam_employee.phone` 出参掩码的口径**不同**：员工是本组织的人、
-- 手机号属个人 PII；代理商这里是对公联系方式。两者的差异是有意的，
-- 真要统一成掩码是一次独立的隐私口径裁决，不在本次。

ALTER TABLE agt_agent
  ADD COLUMN IF NOT EXISTS contact_name VARCHAR(64) NULL COMMENT '联系人姓名' AFTER contact,
  ADD COLUMN IF NOT EXISTS phone VARCHAR(32) NULL COMMENT '联系手机（业务联系方式，非登录身份；登录见 agt_principal.phone_hash）' AFTER contact_name,
  ADD COLUMN IF NOT EXISTS email VARCHAR(128) NULL COMMENT '联系邮箱（业务联系方式，非登录身份）' AFTER phone;

-- ── 存量归一 ────────────────────────────────────────────────────
-- 只搬「整条看起来就是个手机号」的（可选 + 号、其余全数字、7~20 位）。
-- 形状不符的一律**原样留在 contact 里**，不猜 —— 猜错会把姓名当电话打出去。
UPDATE agt_agent
   SET phone = TRIM(contact)
 WHERE phone IS NULL
   AND contact IS NOT NULL
   AND TRIM(contact) REGEXP '^[+]?[0-9]{7,20}$';

-- 已经搬走的清掉，免得同一个号在两列里各存一份（下一个人不知道该信哪个）
UPDATE agt_agent SET contact = NULL WHERE phone IS NOT NULL AND TRIM(IFNULL(contact,'')) = TRIM(phone);

-- ⚠️ **不删 contact 列**：搬不动的那些（如「Ali +971500001234」）还留在里面，
-- 删了就真丢了。列注释改成退役说明，读写面已从它移到上面三列。
ALTER TABLE agt_agent
  MODIFY COLUMN contact VARCHAR(64) NULL
  COMMENT 'retired（V123）：已拆成 contact_name/phone/email。纯手机号的已搬走并清空；形状不明的留在此处待人工归位，不要再读写它';
