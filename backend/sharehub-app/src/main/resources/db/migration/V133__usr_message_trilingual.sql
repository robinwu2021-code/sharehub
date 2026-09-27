-- 站内信补三语列（db-design §1.5）。
--
-- §1.5 是硬约定：**用户可见文本用 xxx + xxx_en + xxx_ar 三列**
-- （对齐 Notice.title/titleEn/titleAr、ProblemEntry、AppVersion.releaseNote*、BankEntry）。
-- 而 usr_message 在 db-design 正文里只定了 title, body 两列 —— **规范自己违反了自己的 §1.5**，
-- 实现照抄了它。单列存中文的后果是：阿语界面上出现一句中文。
-- 同一个错 FeeItem（费用明细返文案）与公告（三语三列下发）都已经各改过一轮。
--
-- 为什么现在才补：这张表此前**全仓零写入**，一条消息都没有，所以「语言不对」这件事
-- 还没有机会表现出来。补写入方的同一个提交里把列补齐，免得先写进一批单语数据。
ALTER TABLE usr_message
    ADD COLUMN title_en VARCHAR(200) NULL COMMENT '标题·英文（§1.5 三语三列）' AFTER title,
    ADD COLUMN title_ar VARCHAR(200) NULL COMMENT '标题·阿语' AFTER title_en,
    ADD COLUMN body_en VARCHAR(1000) NULL COMMENT '正文·英文' AFTER body,
    ADD COLUMN body_ar VARCHAR(1000) NULL COMMENT '正文·阿语' AFTER body_en;
