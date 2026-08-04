-- 自动生成，勿手改：源文件 docs/technical/ddl/pb_core-v2-platform-system.sql
-- 重新同步：python3 backend/scripts/sync-migrations.py
-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·
--            保留字列名加反引号

-- ============================================================
-- powerbank · pb_core 建表脚本(v2 增量)：平台域 iam_/notify_/md_/sys_/openapi_ + 支付渠道 pay_channel
-- 对齐 db-design.md v2(2026-07-29) §1.3~§1.7 通用约定 / §二 platform 子域 / §5.3 支付
-- MySQL 8 · InnoDB · utf8mb4_0900_ai_ci
-- 约定：id 库内物理主键；<x>_no 全局业务键(UK)；跨域逻辑引用不建物理 FK，仅索引。
--       金额 DECIMAL(18,2)+currency；小数比率 DECIMAL(5,4) 0..1；百分数 DECIMAL(5,2) 0..100；
--       账期 CHAR(7) 'YYYY-MM'；时刻 CHAR(5) 'HH:mm'；仅日期 DATE；时间 DATETIME(3) UTC；
--       三语文本 xxx / xxx_en / xxx_ar 三列；枚举 VARCHAR + 列 COMMENT 写全部合法值；软删 deleted。
--       tenant_id 默认 'MAIN'（休眠口子 ADR-011）；db-design 标「（全局）」的表不带 tenant_id。
--
-- 本文件建表（14 张，均为 db-design v2 新增，现有 4 个脚本中不存在）：
--   iam_staff_perf · notify_log · notify_blacklist · md_bank · md_problem · md_market_country
--   sys_param · sys_biz_rule · sys_login_setting · sys_app_version · sys_tax_setting
--   openapi_app · pay_channel · pay_channel_scope
--
-- 跳过（已在现有脚本中建过，本文件不重复建）：
--   notify_template · dict_item · md_region  → pb_core-user-ad-workorder.sql（D13 平台支撑段）
--   iam_employee/iam_role/iam_permission/iam_role_perm/iam_employee_role/iam_data_scope
--                                            → pb_core-loc-agt-iam.sql
--   audit_log（现名 iam_audit_log）           → pb_core-loc-agt-iam.sql，见文件末「与 db-design 的差异」①
--   pay_order/pay_auth/pay_refund/pay_event_log → pb_core-trade-finance.sql
--   tenant/tenant_config/iam_dept/iam_menu    → 本次任务范围外，尚未建（后续脚本补）
-- ============================================================
SET NAMES utf8mb4;

-- ============================================================
-- 平台域 iam_ ：员工绩效  [db-design §2.2 · 菜单叶「绩效报表」]
-- ============================================================

-- 员工绩效（按账期统计，运维/客服人效）
CREATE TABLE IF NOT EXISTS iam_staff_perf (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN' COMMENT '租户(休眠口子)',
  employee_no      VARCHAR(36)  NOT NULL                COMMENT '员工业务键(逻辑引用 iam_employee)',
  employee_name    VARCHAR(64)      NULL                COMMENT '姓名快照(写入时冗余,不随源改名回溯)',
  period           CHAR(7)      NOT NULL                COMMENT '账期 YYYY-MM',
  role             VARCHAR(32)      NULL                COMMENT '统计口径角色码 OPS/CS/FINANCE/BD/...',
  handled          INT          NOT NULL DEFAULT 0      COMMENT '期内处理单量(工单+工单外受理)',
  avg_resolve_mins INT              NULL                COMMENT '平均解决时长(分钟)',
  score            DECIMAL(5,2)     NULL                COMMENT '绩效评分 0..100(百分数口径)',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_staff_perf (employee_no, period),
  KEY idx_staff_perf_tenant_period (tenant_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工绩效';

-- ============================================================
-- 平台域 notify_ ：发送记录 · 触达拉黑  [db-design §2.3]
-- 说明：notify_template 已在 pb_core-user-ad-workorder.sql 建过，此处不重复。
-- ============================================================

-- 发送记录（append，按 created_at 月分区，保留 7 年 · db-design §十）
-- 分区前提：MySQL 要求分区键必须包含在每个唯一索引里 → PK 与 UK 均带 created_at。
CREATE TABLE IF NOT EXISTS notify_log (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  log_no       VARCHAR(36)  NOT NULL                COMMENT '业务键(NTC 域外独立取号)',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN' COMMENT '租户(休眠口子)',
  channel      VARCHAR(16)  NOT NULL                COMMENT 'SMS/EMAIL/PUSH/WHATSAPP',
  template_no  VARCHAR(36)      NULL                COMMENT '通知模板(逻辑引用 notify_template)',
  target       VARCHAR(128) NOT NULL                COMMENT '接收方(**存储即脱敏**,如 +9715****1234;明文不入库)',
  scene        VARCHAR(64)      NULL                COMMENT '业务场景码',
  sent_at      DATETIME(3)      NULL                COMMENT '实际发出时刻(空=尚未发生)',
  status       VARCHAR(16)  NOT NULL DEFAULT 'SENT' COMMENT 'SENT/FAILED',
  fail_reason  VARCHAR(256)     NULL                COMMENT '失败原因(渠道返回)',
  cost         DECIMAL(18,4) NOT NULL DEFAULT 0     COMMENT '单条触达成本(4 位小数,单价级金额)',
  currency     VARCHAR(8)   NOT NULL DEFAULT 'AED'  COMMENT '成本币种',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '分区键',
  PRIMARY KEY (id, created_at),
  UNIQUE KEY uk_notify_log_no (log_no, created_at),
  KEY idx_notify_log_tenant_time (tenant_id, created_at),
  KEY idx_notify_log_target (target),
  KEY idx_notify_log_tpl (template_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知发送记录(append,按 created_at 月分区,保留 7 年)'
-- PARTITION BY RANGE COLUMNS(created_at) (
--   PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
--   PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
--   PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
--   PARTITION pmax    VALUES LESS THAN (MAXVALUE)
-- )  -- 上线时由分区维护作业按月滚动 ADD/REORGANIZE；财务/合规口径保留 7 年，不做 DROP PARTITION
;

-- 触达拉黑（全渠道；解除 = status=RELEASED 软删，保留留痕，不物理删）
CREATE TABLE IF NOT EXISTS notify_blacklist (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  block_no     VARCHAR(36)  NOT NULL                COMMENT '业务键(前缀 NBL,勿与用户黑名单 BL 混用)',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  target       VARCHAR(128) NOT NULL                COMMENT '被拉黑接收方(脱敏存储)',
  channel      VARCHAR(16)  NOT NULL DEFAULT 'ALL'  COMMENT 'SMS/EMAIL/PUSH/WHATSAPP/ALL(全渠道)',
  reason       VARCHAR(24)  NOT NULL                COMMENT 'USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL',
  blocked_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '拉黑时刻',
  blocked_by   VARCHAR(64)      NULL                COMMENT '操作人(employee_no 或 SYSTEM)',
  expire_at    DATETIME(3)      NULL                COMMENT '到期自动解除(空=永久)',
  released_at  DATETIME(3)      NULL                COMMENT '解除时刻(空=尚未解除)',
  released_by  VARCHAR(64)      NULL                COMMENT '解除人',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_blacklist_no (block_no),
  KEY idx_nbl_target (tenant_id, target, channel, status),
  KEY idx_nbl_status (tenant_id, status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达拉黑(全渠道)';

-- ============================================================
-- 平台域 md_ ：主数据字典  [db-design §2.4]
-- 说明：md_region 已在 pb_core-user-ad-workorder.sql 建过，此处不重复。
-- ============================================================

-- 银行字典（全局 · 提现收款方校验：SWIFT 前缀 + IBAN 长度）
CREATE TABLE IF NOT EXISTS md_bank (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  bank_code     VARCHAR(32)  NOT NULL                COMMENT '自然键(前缀 BK 或行业行号)',
  bank_name     VARCHAR(128) NOT NULL                COMMENT '银行名称(默认语)',
  bank_name_en  VARCHAR(128)     NULL                COMMENT '银行名称(英语)',
  bank_name_ar  VARCHAR(128)     NULL                COMMENT '银行名称(阿语)',
  country       CHAR(2)      NOT NULL DEFAULT 'AE'   COMMENT '所属国家 ISO alpha-2',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED'  COMMENT '默认结算币种',
  swift_prefix  VARCHAR(16)      NULL                COMMENT 'SWIFT/BIC 前缀(收款账户校验)',
  iban_length   INT              NULL                COMMENT 'IBAN 位数(收款账户校验)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_code (bank_code),
  KEY idx_bank_country (country, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行字典(全局)';

-- C端报障问题字典（问题 → 标准答复 → 建议处置出口）
CREATE TABLE IF NOT EXISTS md_problem (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  problem_no       VARCHAR(36)  NOT NULL                COMMENT '业务键(前缀 ISS,勿与充电宝 PB 混用)',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  category         VARCHAR(16)  NOT NULL                COMMENT 'RENT/RETURN/BILLING/DEVICE/ACCOUNT/OTHER',
  title            VARCHAR(256) NOT NULL                COMMENT '问题标题(默认语)',
  title_en         VARCHAR(256)     NULL                COMMENT '问题标题(英语)',
  title_ar         VARCHAR(256)     NULL                COMMENT '问题标题(阿语)',
  answer           TEXT             NULL                COMMENT '标准答复(默认语)',
  answer_en        TEXT             NULL                COMMENT '标准答复(英语)',
  answer_ar        TEXT             NULL                COMMENT '标准答复(阿语)',
  suggested_action VARCHAR(16)  NOT NULL DEFAULT 'SELF_SERVICE'
                                                      COMMENT 'SELF_SERVICE/TO_WORKORDER/TO_REFUND/TO_CS',
  sort_no          INT          NOT NULL DEFAULT 0      COMMENT '展示排序',
  status           VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_problem_no (problem_no),
  KEY idx_problem_cat (tenant_id, category, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端报障问题字典';

-- 多国家市场（全局 · 开城清单，币种/时区/合规口径的源头）
CREATE TABLE IF NOT EXISTS md_market_country (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  country_code  CHAR(2)      NOT NULL                COMMENT '自然键 ISO alpha-2(AE/SA/EG...)',
  name          VARCHAR(64)  NOT NULL                COMMENT '国家名(默认语)',
  name_en       VARCHAR(64)      NULL                COMMENT '国家名(英语)',
  name_ar       VARCHAR(64)      NULL                COMMENT '国家名(阿语)',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED'  COMMENT '本地币种(ISO 4217)',
  timezone      VARCHAR(64)      NULL                COMMENT 'IANA 时区,如 Asia/Dubai',
  compliance    VARCHAR(128)     NULL                COMMENT '合规口径标签,如 PDPL/CBUAE',
  city_count    INT          NOT NULL DEFAULT 0      COMMENT '已开城市数(维护列;明细以 md_region 为准)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PLANNED' COMMENT 'LIVE/PILOT/PLANNED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_market_country (country_code),
  KEY idx_market_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多国家市场(全局)';

-- ============================================================
-- 平台域 sys_ ：系统配置  [db-design §2.4]
-- ============================================================

-- 系统参数（键值型，运营可改）
CREATE TABLE IF NOT EXISTS sys_param (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  param_key     VARCHAR(64)  NOT NULL                COMMENT '自然键,如 order.max_duration_min',
  label         VARCHAR(128)     NULL                COMMENT '参数中文名(运营端展示)',
  value         VARCHAR(512)     NULL                COMMENT '参数值(字符串存储,由消费方解析)',
  group_name    VARCHAR(64)      NULL                COMMENT '分组(运营端分栏)',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- db-design 写「param_key UK」；本表带 tenant_id(非全局表)，按 §1.4 隔离键补 tenant_id 前缀
  UNIQUE KEY uk_sys_param (tenant_id, param_key),
  KEY idx_sys_param_group (tenant_id, group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数';

-- 业务规则（三分区单例；WITHDRAW 分区是提现手续费口径的唯一来源）
CREATE TABLE IF NOT EXISTS sys_biz_rule (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  category      VARCHAR(16)  NOT NULL                COMMENT 'WITHDRAW/RESERVATION/BILLING(每租户每类唯一一行)',
  rule          JSON             NULL                COMMENT '规则体;WITHDRAW: feeRate/feeCap/minAmount/settleDays/dailyLimit/needApproval',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED'  COMMENT '规则内金额阈值的币种',
  updated_by    VARCHAR(64)      NULL                COMMENT '最后修改人(employee_no)',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_rule (tenant_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务规则(三分区单例)';

-- 登录设置（按国家；country='*' 为默认行）
CREATE TABLE IF NOT EXISTS sys_login_setting (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  country          VARCHAR(8)   NOT NULL                COMMENT '自然键 ISO alpha-2;* = 默认行',
  country_name     VARCHAR(64)      NULL                COMMENT '国家名(展示快照)',
  otp_enabled      TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '短信验证码登录开关',
  password_enabled TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '密码登录开关',
  apple_enabled    TINYINT(1)   NOT NULL DEFAULT 0      COMMENT 'Apple 登录开关',
  google_enabled   TINYINT(1)   NOT NULL DEFAULT 0      COMMENT 'Google 登录开关',
  otp_expire_sec   INT          NOT NULL DEFAULT 300    COMMENT '验证码有效期(秒)',
  otp_daily_limit  INT          NOT NULL DEFAULT 10     COMMENT '单号码每日验证码上限',
  force_real_name  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否强制实名(合规要求国家)',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- db-design 写「country UK」；本表带 tenant_id(非全局表)，按 §1.4 补 tenant_id 前缀
  UNIQUE KEY uk_login_setting_country (tenant_id, country)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录设置(按国家)';

-- C端应用版本（灰度百分比 + 强更 + 回滚）
CREATE TABLE IF NOT EXISTS sys_app_version (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  version_id      VARCHAR(64)  NOT NULL                COMMENT '自然键,复合 PLATFORM-versionNo,如 IOS-1.4.2',
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  version_no      VARCHAR(32)  NOT NULL                COMMENT '语义版本号,如 1.4.2',
  platform        VARCHAR(16)  NOT NULL                COMMENT 'IOS/ANDROID/H5',
  build_no        INT              NULL                COMMENT '构建号(单调递增)',
  release_note    TEXT             NULL                COMMENT '更新说明(默认语)',
  release_note_en TEXT             NULL                COMMENT '更新说明(英语)',
  release_note_ar TEXT             NULL                COMMENT '更新说明(阿语)',
  force_update    TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否强制更新',
  min_supported   VARCHAR(32)      NULL                COMMENT '最低可用版本(低于此版强更)',
  rollout_percent DECIMAL(5,2) NOT NULL DEFAULT 0      COMMENT '灰度百分比 0..100(百分数口径)',
  download_url    VARCHAR(512)     NULL                COMMENT '下载地址/商店链接',
  status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RELEASED/ROLLBACK',
  released_at     DATETIME(3)      NULL                COMMENT '发布时刻(空=尚未发生)',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_version_id (version_id),
  UNIQUE KEY uk_app_platform_version (platform, version_no),
  KEY idx_app_version_status (tenant_id, platform, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端应用版本';

-- 税率与发票（按国家；rate_percent 为百分数口径 0..100）
CREATE TABLE IF NOT EXISTS sys_tax_setting (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id         VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  country           VARCHAR(8)   NOT NULL                COMMENT '自然键 ISO alpha-2;* = 默认行',
  country_name      VARCHAR(64)      NULL                COMMENT '国家名(展示快照)',
  tax_name          VARCHAR(64)      NULL                COMMENT '税种名,如 VAT',
  rate_percent      DECIMAL(5,2) NOT NULL DEFAULT 0      COMMENT '税率 0..100(百分数口径,非 0..1)',
  trn               VARCHAR(64)      NULL                COMMENT '税号 Tax Registration Number',
  invoice_title     VARCHAR(256)     NULL                COMMENT '开票抬头(运营主体名)',
  included_in_price TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '价格是否含税(1=含税价,0=价外税)',
  effective_from    DATE             NULL                COMMENT '生效日(仅日期语义)',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version           BIGINT       NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- db-design 写「country UK」；本表带 tenant_id(非全局表)，按 §1.4 补 tenant_id 前缀
  UNIQUE KEY uk_tax_setting_country (tenant_id, country)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='税率与发票设置';

-- ============================================================
-- 平台域 openapi_ ：开放平台  [db-design §2.4]
-- ============================================================

-- 开放平台应用（app_secret 只落哈希，明文仅在创建时一次性返回，不可再读）
CREATE TABLE IF NOT EXISTS openapi_app (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  app_no          VARCHAR(36)  NOT NULL                COMMENT '业务键(前缀 APP)',
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name            VARCHAR(128) NOT NULL                COMMENT '应用名称',
  app_key         VARCHAR(64)  NOT NULL                COMMENT '调用方标识(公开)',
  app_secret_hash VARCHAR(128)     NULL                COMMENT '密钥哈希;**明文落 KMS/vault,不入库**',
  scopes          JSON             NULL                COMMENT '授权范围(权限码数组)',
  rate_limit      INT          NOT NULL DEFAULT 0      COMMENT '限流(次/分钟,0=不限)',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_openapi_app_no (app_no),
  UNIQUE KEY uk_openapi_app_key (app_key),
  KEY idx_openapi_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放平台应用';

-- ============================================================
-- 支付渠道 pay_  [db-design §5.3]
-- 注：表前缀归 trade(pay_)，API 落 /api/platform/payment-channels —— 全文唯一「表子域 ≠ API 前缀」。
-- 注：powerbank 不落渠道/PSP 明文密钥（ADR-005）。本表只存掩码列，明文在 KMS/vault。
-- ============================================================

-- 支付渠道配置
CREATE TABLE IF NOT EXISTS pay_channel (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  channel_code     VARCHAR(32)  NOT NULL                COMMENT '自然键(前缀 CH 或渠道自然码,如 STRIPE/TABBY)',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  channel_name     VARCHAR(128) NOT NULL                COMMENT '渠道名称(默认语)',
  channel_name_en  VARCHAR(128)     NULL                COMMENT '渠道名称(英语)',
  channel_name_ar  VARCHAR(128)     NULL                COMMENT '渠道名称(阿语)',
  mode             VARCHAR(16)  NOT NULL DEFAULT 'DELEGATED'
                                                       COMMENT 'DELEGATED(委托 nearpay 执行)/DIRECT(平台直连)',
  api_base         VARCHAR(256)     NULL                COMMENT '渠道 API 基址',
  merchant_id      VARCHAR(128)     NULL                COMMENT '商户号(非密钥,可入库)',
  api_key_masked   VARCHAR(64)      NULL                COMMENT '密钥掩码,如 sk_live_****3f9a;**明文落 KMS/vault,不入库**',
  api_secret_masked VARCHAR(64)     NULL                COMMENT '签名密钥掩码;**明文落 KMS/vault,不入库**',
  status           VARCHAR(16)  NOT NULL DEFAULT 'DISABLED' COMMENT 'ENABLED/DISABLED',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_channel_code (channel_code),
  KEY idx_pay_channel_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付渠道配置';

-- 支付渠道适用范围（拆前端 countries/currencies/capabilities 三个 CSV 串 · db-design §1.7）
CREATE TABLE IF NOT EXISTS pay_channel_scope (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  channel_code  VARCHAR(32)  NOT NULL                COMMENT '渠道(逻辑引用 pay_channel.channel_code)',
  scope_type    VARCHAR(16)  NOT NULL                COMMENT 'COUNTRY/CURRENCY/CAPABILITY',
  scope_value   VARCHAR(64)  NOT NULL                COMMENT 'COUNTRY=ISO alpha-2;CURRENCY=ISO 4217;CAPABILITY=PAY/REFUND/AUTH/CAPTURE/PAYOUT',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_channel_scope (channel_code, scope_type, scope_value),
  KEY idx_pay_scope_lookup (scope_type, scope_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付渠道适用范围';

-- ============================================================
-- 与 db-design.md v2 的差异说明（建表时发现的规格自相矛盾，待回写文档拍板）
-- ============================================================
-- ① 表名：db-design §2.2 写 `audit_log`，现有脚本 pb_core-loc-agt-iam.sql 已建 `iam_audit_log`
--    （且列名为 target 而非 target_type/target_no）。本文件不动它，需二选一后统一。
-- ② 全局标记：§1.4 说「`md_` 前缀一律全局表、不注入 tenant_id」，但 §2.4 只给 md_bank /
--    md_market_country 标了「（全局）」，md_problem 没标。本文件按 §2.4 逐表标记执行 ——
--    md_bank / md_market_country 无 tenant_id，md_problem 有。
-- ③ 单列 UK vs 隔离键：§2.4 写 sys_param.param_key / sys_login_setting.country /
--    sys_tax_setting.country 为单列 UK，但三表都不是全局表、都带 tenant_id。
--    本文件一律建成 (tenant_id, 自然键) 复合 UK，否则多租户开启即撞唯一键。
-- ④ 金额精度：§1.5 规定金额一律 DECIMAL(18,2)，但 §2.3 明写 notify_log.cost DECIMAL(18,4)。
--    本文件按 §2.3 取 (18,4)（单条触达是单价级金额，2 位会被抹平），属已知例外。
-- ⑤ 计数列：§1.4 明写「计数列不是列，是聚合」，但 §2.4 的 md_market_country.city_count
--    与既有 md_region.city_count 又把它列成关键列。本文件保留为**人工维护列**并注明
--    「明细以 md_region 为准」，避免与聚合口径打架。
-- ⑥ notify_blacklist 解除留痕：§2.3 正文要求「解除 = status=RELEASED + 保留记录」，
--    但关键列里没有 released_at / released_by（对照 usr_blacklist 是有的）。本文件补上两列。
-- ⑦ 业务键前缀：§1.4.1 未给 notify_log 分配前缀（NTC 已被公告 mkt_notice 占用）。
--    本文件 log_no 留 VARCHAR(36) 不约束前缀，待注册表补一个（建议 NL）。
-- ⑧ pay_channel 密钥：§5.3 只写了 api_key_masked 一列，但 DIRECT 模式的直连渠道通常需要
--    「调用密钥 + 验签密钥」两把。本文件补 api_secret_masked（同样只存掩码）。


-- ============================================================
-- 补：4 张 db-design 有、v1 DDL 与 v2 三份脚本均漏建的表
-- （2026-07-29 覆盖核对时发现）
-- ============================================================

-- 租户（🔒 休眠口子 ADR-011；MVP 恒 MAIN，无管理面，不暴露 API）
CREATE TABLE IF NOT EXISTS tenant (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_no     VARCHAR(36)  NOT NULL                COMMENT '业务键 T*，业务表 tenant_id 存的就是它',
  name          VARCHAR(128) NOT NULL                COMMENT '运营主体名',
  brand_name    VARCHAR(128)     NULL                COMMENT '品牌名(C端展示)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/SUSPENDED',
  plan          VARCHAR(32)      NULL                COMMENT '套餐',
  quota         JSON             NULL                COMMENT '配额(设备数/订单量/坐席)',
  contact       VARCHAR(64)      NULL                COMMENT '联系方式(掩码;明文落 pb_pii)',
  expire_at     DATETIME(3)      NULL                COMMENT '到期时间',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_no (tenant_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户(休眠口子·全局表)';

INSERT INTO tenant (tenant_no, name, brand_name, status, plan)
  VALUES ('MAIN', '主运营方', 'ShareHub', 'ENABLED', 'SELF')
  ON DUPLICATE KEY UPDATE tenant_no = tenant_no;

-- 租户级配置（支付/计费/品牌/启用供应商）
CREATE TABLE IF NOT EXISTS tenant_config (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_no     VARCHAR(36)  NOT NULL,
  category      VARCHAR(16)  NOT NULL                COMMENT 'PAY/BILLING/BRAND/VENDOR',
  config_key    VARCHAR(64)  NOT NULL,
  config_value  JSON             NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tcfg (tenant_no, category, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户配置(休眠口子)';

-- 组织架构（部门/团队/区域）
CREATE TABLE IF NOT EXISTS iam_dept (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dept_no       VARCHAR(36)  NOT NULL                COMMENT '业务键 D*',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  parent_no     VARCHAR(36)      NULL                COMMENT '上级部门(自引用,逻辑)',
  name          VARCHAR(64)  NOT NULL,
  name_en       VARCHAR(64)      NULL,
  name_ar       VARCHAR(64)      NULL,
  leader_no     VARCHAR(36)      NULL                COMMENT '负责人(iam_employee.employee_no)',
  leader_name   VARCHAR(64)      NULL                COMMENT '负责人名快照(冗余)',
  path          VARCHAR(512)     NULL                COMMENT '祖先路径 /D1/D3/ 便于子树查询',
  sort          INT          NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_no (dept_no),
  KEY idx_dept_parent (tenant_id, parent_no, sort),
  KEY idx_dept_path (tenant_id, path(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织架构';
-- 注：member_count 不落列（db-design §1.4「计数列不是列」），由 iam_employee 聚合。

-- 菜单树（动态导航，与 ops-web/lib/nav.ts 的 98 叶 1:1）
-- ⚠️ backend/powerbank-app/src/main/resources/schema.sql 已自建同名表；两处需保持一致，
--    以本文件为准（补 name_en 与 phase）。
CREATE TABLE IF NOT EXISTS iam_menu (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  menu_no       VARCHAR(36)  NOT NULL,
  parent_no     VARCHAR(36)      NULL,
  name          VARCHAR(64)  NOT NULL,
  name_en       VARCHAR(64)      NULL,
  name_ar       VARCHAR(64)      NULL,
  type          VARCHAR(8)   NOT NULL DEFAULT 'MENU' COMMENT 'DOMAIN(L1)/MODULE(L2)/MENU(L3)/DEEPLINK',
  path          VARCHAR(128)     NULL                COMMENT '路由(可含 ?tab=/?view=)',
  icon          VARCHAR(32)      NULL,
  group_name    VARCHAR(32)      NULL                COMMENT 'L3 分组小标题(如「资产台账」)',
  sort          INT          NOT NULL DEFAULT 0,
  perm          VARCHAR(64)      NULL                COMMENT '权限码(空=跟随父模块)',
  phase         TINYINT      NOT NULL DEFAULT 1      COMMENT '对外交付批次 1/2/3(分期屏蔽)',
  visible       TINYINT(1)   NOT NULL DEFAULT 1,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_menu_no (menu_no),
  KEY idx_menu_parent (parent_no, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单树(动态导航 SSOT)';
