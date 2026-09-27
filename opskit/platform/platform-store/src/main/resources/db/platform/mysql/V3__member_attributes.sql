-- 成员属性定义（21/07 O1-12）：属性键 → 值 → 多语种显示名。
-- 列表里 assignee.attrs.{key}.name 从这里取；前端因此不必内置「代理 / 员工」这类业务词。
CREATE TABLE plat_member_attr (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(32) NOT NULL DEFAULT '',     -- '' = 账户级模板
  attr_key VARCHAR(64) NOT NULL,
  name JSON NOT NULL,                               -- 属性本身的名称 {"zh":"身份"}
  value_labels JSON NOT NULL,                       -- {"AGENT":{"zh":"代理","en":"Agent"}, …}
  updated_by VARCHAR(80) NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_attr (account_id, workspace_id, attr_key)
);
