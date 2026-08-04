-- 会话表 sys_token。
-- 它不在 docs/technical/ddl/ 里（那里只管业务域表），原先由 resources/schema.sql 自建；
-- 关掉 spring.sql.init 后必须由迁移接管，否则 powerbank.auth.token-store=mysql 时全新部署缺表
-- —— 默认是 memory，所以这个缺失在测试里不会暴露，只会在换后端时炸。
-- 源定义：resources/schema.sql（该文件已保留但不再自动执行）。

CREATE TABLE IF NOT EXISTS sys_token (
  token VARCHAR(64) NOT NULL, realm VARCHAR(16) NOT NULL, subject_no VARCHAR(64) NULL,
  role_nos VARCHAR(256) NULL, perm_stamp BIGINT NOT NULL DEFAULT 0, payload TEXT NOT NULL,
  expire_at DATETIME(3) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (token), KEY idx_token_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
