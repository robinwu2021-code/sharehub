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
/** 固件版本库：固件类型 + 供应商 + 发布状态三筛（投放列表用 PageQ 就够，版本库要按类型/厂商找包）。 */
export type OtaReleaseQ = PageQ & { fwType?: string; vendorCode?: string; status?: string };
/** 代理划拨记录：按代理 / 资产类型 / 动作筛（审计流水）。 */
export type AssignmentRecordQ = PageQ & { agentNo?: string; assetType?: string; action?: string };
/**
 * 可划拨资产池：`assetType` 分机柜/站点；
 * `agentNo` = 只列该代理名下的（回收用）；`excludeAgentNo` = 排除该代理已有的（划拨用）。
 */
export type AssignableAssetQ = PageQ & { assetType?: string; agentNo?: string; excludeAgentNo?: string };
/** 分润规则：dimension 是「双向视图」的视角参数（按场地方看 / 按代理商看），同 ShareSummaryQ 口径。 */
export type ShareRuleQ = PageQ & { dimension?: string };
/** 结算单：状态 + 对象类型 + 周期。 */
export type SettlementQ = PageQ & { status?: string; payeeType?: string; period?: string };
/** 分润明细：可按维度/对象/周期收敛（结算单详情就是「某对象某周期」的那批明细）。 */
export type ShareRecordQ = PageQ & { dimension?: string; payeeNo?: string; period?: string };
/** 分润统计：一张表两种主体，dimension 是维度切换器参数（VENUE / AGENT）。 */
export type ShareSummaryQ = PageQ & {
  dimension?: string; // VENUE / AGENT
  period?: string; // 2026-07
  sortKey?: string; // shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
/** 对账：跑批结果（MATCHED/DIFF）+ 差错处置进度（OPEN/HANDLING/RESOLVED/IGNORED）双筛。 */
export type ReconQ = PageQ & { status?: string; handleStatus?: string };
/** 发票：状态筛选（DRAFT/ISSUED/VOID）。 */
export type InvoiceQ = PageQ & { status?: string };
/** 充值订单：状态 + 日期范围。 */
export type RechargeQ = PageQ & { status?: string; from?: string; to?: string };
/** 免费订单：按减免原因筛选。 */
export type FreeOrderQ = PageQ & { reason?: string };
/** 充值套餐：上下架状态 + 归档开关。 */
export type PackageQ = PageQ & { status?: string; showArchived?: boolean };
/** 钱包流水：按流水类型筛（RECHARGE/SPEND/REFUND/BONUS）；用户号走路径参数，不进 q。 */
export type WalletTxnQ = PageQ & { type?: string };
/** 次卡：按用户 / 卡类型 / 状态筛（用户详情抽屉按 userNo 精确取该用户的卡）。 */
export type MemberCardQ = PageQ & { userNo?: string; cardType?: string; status?: string };
/** 免费白名单：状态（含 REVOKED 软撤销）+ 原因。 */
export type WhitelistQ = PageQ & { status?: string; reason?: string };
/** 通知发送记录：渠道/状态筛选 + 受控排序（sort=sentAt|cost）。 */
export type NotifyLogQ = PageQ & { channel?: string; status?: string; sort?: string; dir?: string };
/** 通知黑名单：渠道 + 拉黑原因。 */
export type NotifyBlacklistQ = PageQ & { channel?: string; reason?: string };
/** App 版本：平台筛选。 */
export type AppVersionQ = PageQ & { platform?: string };
/** 银行字典：国家 + 币种。 */
export type BankQ = PageQ & { country?: string; currency?: string; status?: string; showArchived?: boolean };
/** 优惠券发放记录：按券号 / 发放对象类型筛（S2 发放留痕的审计流水）。 */
export type CouponIssueQ = PageQ & { couponNo?: string; targetType?: string };
/** 营销活动：状态筛（加了「暂停」后要能单独捞 PAUSED）。等价于 StatusQ，保留域名。 */
export type CampaignQ = StatusQ;
/**
 * 报表：周期是报表的第一入参（`ReportPeriod`，缺省 LAST_30D）。
 * 不做成 `period?: ReportPeriod` 是因为它从 URL/查询参数来，可能是任意字符串；
 * 合法性由 mock/后端归一化（`normalizePeriod`），不靠前端类型断言。
 */
export type ReportQ = PageQ & { period?: string };
/** 报表趋势：`kind` 决定汇总口径（DEVICE / LOCATION / FINANCE）。 */
export type ReportTrendQ = ReportQ & { kind?: string };
/** 自定义报表：维度 + 自选指标（`metrics` 为 csv，如 `GMV,ORDERS`）。 */
export type ReportCustomQ = ReportQ & { dim?: string; metrics?: string };

/** 常见问题：分类 + 上下架状态。 */
export type ProblemQ = PageQ & { category?: string; status?: string; showArchived?: boolean };

/** 入驻申请检索。`from`/`to` 卡 `submittedAt`（ISO 串）。 */
export interface ApplyQ extends PageQ {
  status?: string;
  from?: string;
  to?: string;
}

/** 收款账户检索。**不按掩码搜** —— 同号段掩码可能相同，会捞出不相干的账户。 */
export interface PayoutAccountQ extends PageQ {
  payeeType?: string;
  payeeNo?: string;
}
