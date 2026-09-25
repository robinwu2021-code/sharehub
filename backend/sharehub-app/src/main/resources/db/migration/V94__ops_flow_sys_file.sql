SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 文件元数据（TDD-运营核心流程/01）
--
-- 为什么先传后绑：表单里先传照片、后点保存；用户放弃时 TEMP 文件由清理任务回收，
-- 不留无主对象。字节在对象存储（生产 COS，测试本地目录），这里只存「它是谁的、在哪、能不能看」。
--
-- 合同附件此前只存前端声明的文件名与大小（假上传），改为引用 file_no；
-- 历史行 file_no 为空 = 接入对象存储前的附件，只有名字没有字节。
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_file (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  file_no         VARCHAR(36)   NOT NULL COMMENT '业务键 FL*',
  tenant_id       VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  category        VARCHAR(32)   NOT NULL COMMENT 'CONTRACT_SCAN 合同签署件 / WO_PHOTO 工单照片 / SURVEY_PHOTO 勘测照片 / LEAD_PHOTO 选址照片 / AGENT_QUALIFICATION 代理资质 / CS_EVIDENCE 报障截图 / PUBLIC_IMAGE 公开图片',
  status          VARCHAR(16)   NOT NULL DEFAULT 'TEMP' COMMENT 'TEMP 已上传未绑定 / BOUND 已绑定 / REMOVED 业务已移除 / PURGED 对象已清理',
  biz_type        VARCHAR(32)       NULL COMMENT 'CONTRACT 合同 / WORK_ORDER 工单 / LOCATION 点位 / LEAD 商机 / AGENT_APPLY 入驻申请 / CS_TICKET 报障 / BRAND 品牌 / NOTICE 公告',
  biz_no          VARCHAR(36)       NULL COMMENT '归属对象业务号（绑定后写入）',
  original_name   VARCHAR(255)  NOT NULL,
  content_type    VARCHAR(64)   NOT NULL COMMENT '服务端嗅探结果，不取客户端声明',
  size_bytes      BIGINT        NOT NULL,
  sha256          CHAR(64)      NOT NULL,
  storage         VARCHAR(16)   NOT NULL COMMENT 'COS 腾讯云对象存储 / LOCAL 本地目录',
  bucket          VARCHAR(64)   NOT NULL,
  object_key      VARCHAR(255)  NOT NULL,
  pii             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '个人数据：只流式转发，不发限时地址',
  image_width     INT               NULL,
  image_height    INT               NULL,
  uploader_realm  VARCHAR(16)   NOT NULL COMMENT 'STAFF 员工 / AGENT 代理 / CONSUMER C端用户',
  uploader_no     VARCHAR(36)   NOT NULL,
  agent_no        VARCHAR(36)       NULL COMMENT '数据范围锚点：代理上传，或绑定对象属于某代理',
  bound_at        DATETIME(3)       NULL,
  removed_at      DATETIME(3)       NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)       NULL,
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)       NULL,
  version         BIGINT        NOT NULL DEFAULT 0,
  deleted         TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_no (file_no),
  UNIQUE KEY uk_file_object (bucket, object_key),
  KEY idx_file_biz (biz_type, biz_no, status),
  KEY idx_file_temp (status, created_at),
  KEY idx_file_scope (tenant_id, agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据（字节在对象存储）';

ALTER TABLE loc_contract_attach
  ADD COLUMN IF NOT EXISTS file_no VARCHAR(36) NULL COMMENT '→ sys_file.file_no；空 = 接入对象存储前的历史附件';
