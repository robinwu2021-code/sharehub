// 统一查询参数类型。全部列表接口的 q 参数只能从这里取，禁止在域文件里就地 `PageQ & {...}`。
// 合并规则：结构完全等价的类型收敛到一个「形状名」，旧名以 alias 保留（类型等价，不破坏任何引用）。
// 分类：① 通用形状（PageQ / StatusQ / StatusTypeQ）② 域特有形状（各 <Domain>Q）。

/** 分页三件套 + 关键词。索引签名保留：http-client 的 qs() 直接序列化整个 q。 */
export interface PageQ { page?: number; size?: number; keyword?: string; [k: string]: unknown; }

// ——— 通用形状（多域共用，已合并）———

/** 单 status 过滤。原 OrderQ / StatusQ 结构相同，已合并。 */
export type StatusQ = PageQ & { status?: string };
/** 订单列表。等价于 StatusQ，保留旧名。 */
export type OrderQ = StatusQ;

/** status + type 双筛。原 WoQ / ReservationQ 结构相同，已合并。 */
export type StatusTypeQ = PageQ & { status?: string; type?: string };
/** 工单列表。等价于 StatusTypeQ，保留旧名。 */
export type WoQ = StatusTypeQ;
/** 预约订单列表。等价于 StatusTypeQ，保留旧名。 */
export type ReservationQ = StatusTypeQ;

/**
 * G1 软删除：可归档主数据的列表参数。`showArchived` 打开才把已归档行带出来，
 * 默认（不传 / false）一律过滤掉——「归档了还在列表里」是软删除最常见的漏实现。
 */
export type ArchiveQ = PageQ & { showArchived?: boolean };

// ——— 域特有形状 ———

/** 设备：在线状态 + 业务状态双筛。 */
export type CabinetQ = PageQ & { onlineStatus?: string; status?: string; showArchived?: boolean };
/** 告警记录：级别 + 处理状态。 */
export type AlarmQ = PageQ & { level?: string; status?: string };
/** 设备日志：stream 双流筛选 + 日期范围（YYYY-MM-DD，含端点）。 */
export type DeviceLogQ = PageQ & { stream?: string; from?: string; to?: string };
/** 分润统计：一张表两种主体，dimension 是维度切换器参数（VENUE / AGENT）。 */
export type ShareSummaryQ = PageQ & {
  dimension?: string; // VENUE / AGENT
  period?: string; // 2026-07
  sortKey?: string; // shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
/** 充值订单：状态 + 日期范围。 */
export type RechargeQ = PageQ & { status?: string; from?: string; to?: string };
/** 免费订单：按减免原因筛选。 */
export type FreeOrderQ = PageQ & { reason?: string };
/** 充值套餐：上下架状态 + 归档开关。 */
export type PackageQ = PageQ & { status?: string; showArchived?: boolean };
/** 免费白名单：状态（含 REVOKED 软撤销）+ 原因。 */
export type WhitelistQ = PageQ & { status?: string; reason?: string };
/** 通知发送记录：渠道/状态筛选 + 受控排序（sort=sentAt|cost）。 */
export type NotifyLogQ = PageQ & { channel?: string; status?: string; sort?: string; dir?: string };
/** 通知黑名单：渠道 + 拉黑原因。 */
export type NotifyBlacklistQ = PageQ & { channel?: string; reason?: string };
/** App 版本：平台筛选。 */
export type AppVersionQ = PageQ & { platform?: string };
/** 银行字典：国家 + 币种。 */
export type BankQ = PageQ & { country?: string; currency?: string; showArchived?: boolean };
/** 常见问题：分类 + 上下架状态。 */
export type ProblemQ = PageQ & { category?: string; status?: string; showArchived?: boolean };
