SET NAMES utf8mb4;
-- ════════════════════════════════════════════════════════════════════
-- 入驻申请：业务联系方式（明文），与身份标识彻底分开
--
-- 【问题】2026-09-26 生产验证审核流时发现：审核通过建出来的代理商，
-- `agt_agent.contact_name/phone/email`（V123 新加的业务联系方式列）**全是 NULL**，
-- 运营台代理商列表显示 `-`，「开通登录」按钮还会提示「缺联系手机/邮箱」。
-- 链路是通的，值是空的。
--
-- 【根因】明文在提交那一刻就丢了。`ApplyServiceImpl.submit()` 只写
-- `*_hash` + `*_mask` + `hash_ver`，明文参数在方法返回时就没了。
-- 而 `agt_apply` / `agt_principal` 的 `phone_enc` / `email_enc` 四列
-- **全仓没有任何一处写入**（`grep setPhoneEnc` 零命中），生产上全 NULL ——
-- 设计文档里「需要还原时（发短信/邮件）」那个用途从来没有落过地。
--
-- 【为什么不是去把 *_enc 写起来】那要引入密钥管理，而且把两件不同的事
-- 继续捆在一起。**业务联系方式与登录标识本来就是两回事**：
--   · 登录标识要能查找、要唯一、**不可逆** —— 继续走 hash/mask，本次不动；
--   · 业务联系方式是运营要看见、要拨打的东西，**它本就该是明文**，
--     而且联系人完全可能不是那个登录的自然人（换了对接人是常事）。
-- 捆在一起才是当初埋雷的原因，所以这里把它们并列开。
--
-- 【不让人填两遍】`contact_phone` / `contact_email` 默认取提交时填的那个
-- 手机号/邮箱（归一化后的明文），运营可在代理商档案里单独改。
-- `contact_name` 可选 —— 自助注册时申请人未必填，留空即可。
--
-- 【历史行】已有的申请单明文已经丢了，补不回来，三列留 NULL。
-- 生产当前只有 AP001 一张（已 APPROVED），它派生的 AG011 需要人工补联系方式。
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE agt_apply
    ADD COLUMN contact_name  VARCHAR(64)  NULL COMMENT '业务联系人姓名（明文，可与登录自然人不同）' AFTER operator_type,
    ADD COLUMN contact_phone VARCHAR(32)  NULL COMMENT '业务联系电话（明文，运营要拨打；登录标识另走 phone_hash）' AFTER contact_name,
    ADD COLUMN contact_email VARCHAR(128) NULL COMMENT '业务联系邮箱（明文，运营要发件；登录标识另走 email_hash）' AFTER contact_phone;
