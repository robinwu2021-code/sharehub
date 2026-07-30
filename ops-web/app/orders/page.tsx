"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination, StatCard } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Input, Select } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { OrderStatusBadge } from "@/components/status";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { ORDER_INTERVENTIONS, interveneActions, depositActions } from "@/lib/types";
import type {
  RentOrder, OrderException, DepositRecord,
  OrderComplaint, RefundRecord, ComplaintIssueType, ComplaintResolution,
  OrderInterventionAction, DepositAction, DunChannel,
  Reservation, FreeOrder, WhitelistReason,
} from "@/lib/types";

const SIZE = 10;
// 顺序对齐 lib/nav.ts 的深链顺序（交易流水 → 售后 → 特殊单据）
const TABS = [
  { key: "list", label: "订单列表" },
  { key: "reservations", label: "预约订单", phase: 2 as const },
  { key: "exceptions", label: "异常订单" },
  { key: "complaints", label: "投诉订单" },
  { key: "refunds", label: "退款记录" },
  { key: "free", label: "免费订单", phase: 2 as const },
  { key: "deposit", label: "押金与欠费", phase: 2 as const },
];

// —— 预约订单（规格 §3）：竞品是电车预约充电桩，充电宝映射为「预约取宝 / 预约还位」——
const RES_TYPE: Record<Reservation["type"], { label: string; tone: "default" | "warning" }> = {
  BORROW: { label: "预约取宝", tone: "default" },
  RETURN: { label: "预约还位", tone: "warning" }, // 还位是占用空仓，与取宝挤兑的是相反资源，故换色
};
const RES_STATUS: Record<Reservation["status"], { label: string; tone: "warning" | "success" | "muted" | "danger" }> = {
  PENDING: { label: "待履约", tone: "warning" },
  FULFILLED: { label: "已履约", tone: "success" },
  EXPIRED: { label: "已过期", tone: "danger" },
  CANCELLED: { label: "已取消", tone: "muted" },
};
/** 即将超时：待履约且距预约时段结束 < 20 分钟。整行高亮见 DataTable 的 rowClassName。 */
const RES_SOON_MS = 20 * 60_000;
const isExpiringSoon = (r: Reservation) => {
  if (r.status !== "PENDING") return false;
  const left = new Date(r.reservedTo).getTime() - Date.now();
  return left > 0 && left < RES_SOON_MS;
};

// —— 免费订单（规格 §4）：来源 = 白名单用途，两页口径一致 ——
const REASON_LABEL: Record<WhitelistReason, string> = {
  INTERNAL_TEST: "内测",
  VIP: "VIP",
  BD_DEMO: "BD 演示",
  MERCHANT_SELF: "商户自用",
};

const ISSUE_LABEL: Record<ComplaintIssueType, string> = {
  BILLING_DISPUTE: "计费争议",
  NOT_EJECTED: "未弹出",
  NOT_RETURNED: "未归还",
  DEVICE_FAULT: "设备故障",
  OTHER: "其他",
};

const CPL_STATUS: Record<OrderComplaint["status"], { label: string; tone: "warning" | "default" | "success" | "muted" }> = {
  PENDING: { label: "待处理", tone: "warning" },
  PROCESSING: { label: "处理中", tone: "default" },
  RESOLVED: { label: "已解决", tone: "success" },
  REJECTED: { label: "已驳回", tone: "muted" },
};

const RESOLUTION_LABEL: Record<ComplaintResolution, string> = {
  REFUND: "退款",
  COMPENSATE: "补偿",
  REJECT: "驳回",
  EXPLAINED: "已解释",
};

const RFD_STATUS: Record<RefundRecord["status"], { label: string; tone: "warning" | "default" | "success" | "muted" | "danger" }> = {
  PENDING: { label: "待审批", tone: "warning" },
  APPROVED: { label: "已通过", tone: "default" },
  REJECTED: { label: "已驳回", tone: "muted" },
  EXECUTED: { label: "已退款", tone: "success" },
  FAILED: { label: "退款失败", tone: "danger" },
};

const DEP_STATUS: Record<DepositRecord["status"], { label: string; tone: "success" | "muted" | "outline" | "warning" }> = {
  HELD: { label: "已冻结", tone: "outline" },
  RELEASED: { label: "已解冻", tone: "success" },
  BOUGHT_OUT: { label: "已买断", tone: "muted" },
  ARREARS: { label: "欠费", tone: "warning" },
};

// —— 订单人工干预（S1：原先四个动作是伪实现，点了状态不变）——
const IV_LABEL: Record<OrderInterventionAction, string> = {
  eject: "远程弹出", force_return: "强制归还", waive: "免单", compensate: "补偿", refund_apply: "申请退款",
};
/** 每个动作到底会改什么 —— 抽屉里写清楚，避免「点了不知道发生了什么」。 */
const IV_DESC: Record<OrderInterventionAction, string> = {
  eject: "向柜机补发一次弹仓指令：记一次弹出，订单转「弹出中」，柜机上报后才进入使用中",
  force_return: "按当前时间结单：写入归还时间、时长与费用，订单转「已结算」",
  waive: "本单应收金额置 0，减免金额单独记账（成本管控可查）",
  compensate: "按填写金额补至用户余额（mock 口径为余额补偿，不发券）",
  refund_apply: "只提交退款申请，进「退款记录」待审批；审批通过才真正出款",
};
/** 干预权限：四个处置动作用 order:intervene:execute，申请退款用 order:refund:apply。 */
const IV_PERM: Record<OrderInterventionAction, string> = {
  eject: "order:intervene:execute", force_return: "order:intervene:execute",
  waive: "order:intervene:execute", compensate: "order:intervene:execute",
  refund_apply: "order:refund:apply",
};

// —— 押金处置（S1：押金页原先纯只读）——
const DEP_ACTION_LABEL: Record<DepositAction, string> = { release: "解冻", buyout: "买断", dun: "催缴" };
const DUN_CHANNEL_LABEL: Record<DunChannel, string> = { SMS: "短信", PUSH: "App 推送", PHONE: "电话" };

const EXC_TYPE_LABEL: Record<OrderException["type"], string> = {
  NOT_EJECTED: "未弹出",
  NOT_RETURNED: "未归还",
  OVERTIME_BUYOUT: "超时买断",
  DOUBLE_CHARGE: "重复扣款",
};

function OrdersInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n(); // 导出订单状态用同一套 i18n 文案，避免与表格徽标不一致
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "list");
  const { confirm, dialog } = useConfirm();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);

  // —— 订单列表 tab 状态 ——
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [detail, setDetail] = useState<RentOrder | null>(null);
  // 干预确认抽屉：原因必填（沿用退款审批口径），补偿另需金额
  const [iv, setIv] = useState<{ order: RentOrder; action: OrderInterventionAction } | null>(null);
  const [ivReason, setIvReason] = useState("");
  const [ivAmount, setIvAmount] = useState("");

  const listQ = useQuery({
    queryKey: ["orders", page, keyword, status],
    queryFn: () => api.listOrders({ page, size: SIZE, keyword, status: status || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  // 干预历史（审计时间线）：只查当前详情订单的记录
  const ivHistoryQ = useQuery({
    queryKey: ["order-interventions", detail?.orderNo],
    queryFn: () => api.listOrderInterventions({ orderNo: detail!.orderNo, size: 50 }),
    enabled: !!detail,
  });

  const intervene = useMutation({
    mutationFn: (v: { no: string; action: OrderInterventionAction; reason: string; amount?: number }) =>
      api.interveneOrder(v.no, v.action, { reason: v.reason, amount: v.amount }),
    onSuccess: (r, v) => {
      notify.success(`${IV_LABEL[v.action]}已执行 · ${r.intervention.interventionNo} · 状态 ${t(`orderStatus.${r.intervention.beforeStatus}`)} → ${t(`orderStatus.${r.intervention.afterStatus}`)}`);
      qc.invalidateQueries({ queryKey: ["orders"] });
      qc.invalidateQueries({ queryKey: ["order-interventions"] });
      // 申请退款会落一条退款申请，刷新审批队列
      if (v.action === "refund_apply") qc.invalidateQueries({ queryKey: ["refunds"] });
      setDetail(r.order); // 详情抽屉立刻显示落库后的新状态与金额
      setIv(null);
    },
  });

  // —— 异常订单 tab 状态 ——
  const [excPage, setExcPage] = useState(1);
  const [excKeyword, setExcKeyword] = useState("");
  const excQ = useQuery({
    queryKey: ["order-exceptions", excPage, excKeyword],
    queryFn: () => api.listOrderExceptions({ page: excPage, size: SIZE, keyword: excKeyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "exceptions",
  });

  // —— 投诉订单 tab 状态（B1）——
  const [cplPage, setCplPage] = useState(1);
  const [cplKeyword, setCplKeyword] = useState("");
  const [cplStatus, setCplStatus] = useState("");
  const [cplDetail, setCplDetail] = useState<OrderComplaint | null>(null);
  const [cplResolution, setCplResolution] = useState<ComplaintResolution>("REFUND");
  const [cplNote, setCplNote] = useState("");
  const cplQ = useQuery({
    queryKey: ["complaints", cplPage, cplKeyword, cplStatus],
    queryFn: () => api.listOrderComplaints({ page: cplPage, size: SIZE, keyword: cplKeyword, status: cplStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "complaints",
  });
  const handleCpl = useMutation({
    mutationFn: (v: { no: string; resolution: ComplaintResolution; note: string }) => api.handleOrderComplaint(v.no, v.resolution, v.note),
    onSuccess: () => { notify.success("投诉已处理"); qc.invalidateQueries({ queryKey: ["complaints"] }); setCplDetail(null); },
  });
  // 投诉转工单：投诉-订单-工单三者串通（竞品投诉与工单不通，这是我们的差异点）
  const raiseCpl = useMutation({
    mutationFn: (no: string) => api.raiseComplaintWorkOrder(no),
    onSuccess: (r) => { notify.success(`已转工单 ${r.workOrderNo}`); qc.invalidateQueries({ queryKey: ["complaints"] }); setCplDetail(r); },
  });

  // —— 退款记录 tab 状态（B2：独立审批队列）——
  const [rfdPage, setRfdPage] = useState(1);
  const [rfdKeyword, setRfdKeyword] = useState("");
  const [rfdStatus, setRfdStatus] = useState("");
  const [rfdDetail, setRfdDetail] = useState<RefundRecord | null>(null);
  const [rfdApprove, setRfdApprove] = useState("1");
  const [rfdReject, setRfdReject] = useState("");
  const rfdQ = useQuery({
    queryKey: ["refunds", rfdPage, rfdKeyword, rfdStatus],
    queryFn: () => api.listRefundRecords({ page: rfdPage, size: SIZE, keyword: rfdKeyword, status: rfdStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "refunds",
  });
  const auditRfd = useMutation({
    mutationFn: (v: { no: string; approve: boolean; rejectReason?: string }) => api.auditRefund(v.no, v.approve, v.rejectReason),
    onSuccess: (_r, v) => { notify.success(v.approve ? "退款已通过并执行" : "退款已驳回"); qc.invalidateQueries({ queryKey: ["refunds"] }); setRfdDetail(null); },
  });
  const canAuditRefund = allow("order:refund:audit");

  // —— 押金与欠费 tab 状态（P2 → S1 补处置动作）——
  const [depPage, setDepPage] = useState(1);
  const [depKeyword, setDepKeyword] = useState("");
  const [depStatus, setDepStatus] = useState("");
  const depQ = useQuery({
    queryKey: ["deposit-records", depPage, depKeyword, depStatus],
    queryFn: () => api.listDepositRecords({ page: depPage, size: SIZE, keyword: depKeyword, status: depStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "deposit",
  });
  // 押金处置拆两个码：解冻/买断动的是用户的钱（财务），催缴只留痕（客服日常）。
  // 合成一个码会让客服为了催缴顺带拿到买断权——见 功能权限清单 §4 注。
  const canDepositManage = allow("order:deposit:manage");
  const canDun = allow("order:arrears:dun");
  const canDeposit = canDepositManage || canDun;
  // 押金处置抽屉：解冻/买断/催缴共用（买断填金额、催缴选渠道，全都要填原因）
  const [depAct, setDepAct] = useState<{ row: DepositRecord; action: DepositAction } | null>(null);
  const [depReason, setDepReason] = useState("");
  const [depAmount, setDepAmount] = useState("");
  const [dunChannel, setDunChannel] = useState<DunChannel>("SMS");
  const openDepAct = (row: DepositRecord, action: DepositAction) => {
    setDepAct({ row, action });
    setDepReason("");
    setDepAmount(String(row.amount)); // 买断金额缺省为押金额（上限也是它）
    setDunChannel("SMS");
  };
  const onDepDone = (label: string) => {
    notify.success(`${label}已执行`);
    qc.invalidateQueries({ queryKey: ["deposit-records"] });
    setDepAct(null);
  };
  const releaseDep = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.releaseDeposit(v.no, v.reason),
    onSuccess: () => onDepDone("押金解冻"),
  });
  const buyoutDep = useMutation({
    mutationFn: (v: { no: string; amount: number; reason: string }) => api.buyoutDeposit(v.no, { amount: v.amount, reason: v.reason }),
    onSuccess: () => onDepDone("押金买断"),
  });
  const dunDep = useMutation({
    mutationFn: (v: { no: string; channel: DunChannel; note: string }) => api.dunArrears(v.no, { channel: v.channel, note: v.note }),
    onSuccess: () => onDepDone("欠费催缴"),
  });
  const depBusy = releaseDep.isPending || buyoutDep.isPending || dunDep.isPending;
  /** 解冻/买断是金额类操作，提交前走二次确认；催缴只是触达，无需确认。 */
  const submitDepAct = async () => {
    if (!depAct) return;
    const { row, action } = depAct;
    if (action === "dun") {
      dunDep.mutate({ no: row.depositNo, channel: dunChannel, note: depReason });
      return;
    }
    const ok = await confirm({
      title: `${DEP_ACTION_LABEL[action]}押金 ${row.depositNo}`,
      desc: action === "release"
        ? `将解冻 ${money(row.amount, row.currency)} 并退回用户原支付方式，解冻后不可撤销。`
        : `将按 ${money(Number(depAmount) || 0, row.currency)} 买断（押金额 ${money(row.amount, row.currency)}），买断后押金不再退还。`,
      danger: true,
      confirmText: `确认${DEP_ACTION_LABEL[action]}`,
      cancelText: "再想想",
    });
    if (!ok) return;
    if (action === "release") releaseDep.mutate({ no: row.depositNo, reason: depReason });
    else buyoutDep.mutate({ no: row.depositNo, amount: Number(depAmount), reason: depReason });
  };

  // —— 预约订单 tab（B4）——
  const [resPage, setResPage] = useState(1);
  const [resKeyword, setResKeyword] = useState("");
  const [resStatus, setResStatus] = useState("");
  const [resType, setResType] = useState("");
  const resQ = useQuery({
    queryKey: ["reservations", resPage, resKeyword, resStatus, resType],
    queryFn: () => api.listReservations({ page: resPage, size: SIZE, keyword: resKeyword, status: resStatus || undefined, type: resType || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "reservations",
  });
  const canCancelRes = allow("order:order:update");
  const cancelRes = useMutation({
    mutationFn: (no: string) => api.cancelReservation(no),
    onSuccess: () => { notify.success("预约已取消"); qc.invalidateQueries({ queryKey: ["reservations"] }); },
  });
  const askCancelRes = async (r: Reservation) => {
    const ok = await confirm({
      title: `取消预约 ${r.reservationNo}`,
      desc: `${RES_TYPE[r.type].label} · ${r.siteName} · ${fmtTime(r.reservedFrom)} 起。取消后释放占位，用户需重新预约。`,
      danger: true,
      confirmText: "确认取消",
      cancelText: "再想想",
    });
    if (ok) cancelRes.mutate(r.reservationNo);
  };

  // —— 免费订单 tab（B4）：页头统计走全量口径（成本管控，不能只统计当页）——
  const [freePage, setFreePage] = useState(1);
  const [freeKeyword, setFreeKeyword] = useState("");
  const [freeReason, setFreeReason] = useState("");
  const freeQ = useQuery({
    queryKey: ["free-orders", freePage, freeKeyword, freeReason],
    queryFn: () => api.listFreeOrders({ page: freePage, size: SIZE, keyword: freeKeyword, reason: freeReason || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "free",
  });
  const freeStatsQ = useQuery({
    queryKey: ["free-order-stats"],
    queryFn: () => api.getFreeOrderStats(),
    enabled: tab === "free",
  });

  const resCols: Column<Reservation>[] = [
    { header: "预约号", cell: (r) => <span className="font-medium tabular-nums">{r.reservationNo}</span> },
    { header: "用户", cell: (r) => <span className="text-muted-foreground">{r.userNo}</span> },
    { header: "类型", cell: (r) => <Badge tone={RES_TYPE[r.type].tone}>{RES_TYPE[r.type].label}</Badge> },
    { header: "目标站点", cell: (r) => r.siteName },
    // 空机柜号 = 站点级预约（到店任选一台），不是数据缺失，故显式写清
    { header: "指定机柜", cell: (r) => r.cabinetNo ?? <span className="text-muted-foreground">站点级</span> },
    {
      header: "预约时段",
      cell: (r) => (
        <span className={isExpiringSoon(r) ? "font-medium text-[var(--destructive)]" : "text-muted-foreground"}>
          {fmtTime(r.reservedFrom)} ~ {fmtTime(r.reservedTo)}
        </span>
      ),
    },
    { header: "占位费", cell: (r) => <span className="tabular-nums">{r.holdFee > 0 ? money(r.holdFee, r.currency) : "-"}</span> },
    {
      header: "状态",
      cell: (r) => (
        <div className="flex items-center gap-1">
          <Badge tone={RES_STATUS[r.status].tone}>{RES_STATUS[r.status].label}</Badge>
          {isExpiringSoon(r) && <Badge tone="danger">即将超时</Badge>}
        </div>
      ),
    },
    { header: "关联订单", cell: (r) => r.orderNo ? <span className="tabular-nums">{r.orderNo}</span> : <span className="text-muted-foreground">-</span> },
    {
      header: "操作",
      // 仅 PENDING 可取消：已履约/过期/取消的预约再点没有语义
      cell: (r) => canCancelRes && r.status === "PENDING"
        ? <Button size="sm" variant="outline" disabled={cancelRes.isPending} onClick={() => askCancelRes(r)}>取消预约</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const freeCols: Column<FreeOrder>[] = [
    { header: "订单号", cell: (f) => <span className="font-medium tabular-nums">{f.orderNo}</span> },
    { header: "用户", cell: (f) => <span className="text-muted-foreground">{f.userNo}</span> },
    { header: "昵称", cell: (f) => f.nickname },
    { header: "免费来源", cell: (f) => <Badge tone="outline">{REASON_LABEL[f.whitelistReason]}</Badge> },
    { header: "减免金额", cell: (f) => <span className="tabular-nums">{money(f.waivedAmount, f.currency)}</span> },
    { header: "站点", cell: (f) => f.siteName },
    { header: "机柜", cell: (f) => <span className="tabular-nums">{f.cabinetNo}</span> },
    { header: "借出", cell: (f) => <span className="text-muted-foreground">{fmtTime(f.startedAt)}</span> },
    { header: "归还", cell: (f) => <span className="text-muted-foreground">{fmtTime(f.endedAt)}</span> },
    { header: "时长", cell: (f) => <span className="tabular-nums">{f.duration} 分</span> },
  ];

  const listCols: Column<RentOrder>[] = [
    { header: "订单号", cell: (o) => <span className="font-medium">{o.orderNo}</span> },
    { header: "用户", cell: (o) => <span className="text-muted-foreground">{o.cUserNo}</span> },
    { header: "借出柜机", cell: (o) => o.cabinetNo },
    { header: "点位", cell: (o) => <span className="text-muted-foreground">{o.locationName}</span> },
    { header: "时长", cell: (o) => <span className="tabular-nums">{o.durationMin != null ? `${o.durationMin} 分` : "-"}</span> },
    { header: "费用", cell: (o) => <span className="tabular-nums">{money(o.feeAmount, o.currency)}</span> },
    { header: "状态", cell: (o) => <OrderStatusBadge s={o.status} /> },
    { header: "操作", cell: (o) => <Button size="sm" variant="outline" onClick={() => setDetail(o)}>详情</Button> },
  ];

  const depCols: Column<DepositRecord>[] = [
    { header: "押金单号", cell: (d) => <span className="font-medium">{d.depositNo}</span> },
    { header: "订单号", cell: (d) => <span className="text-muted-foreground">{d.orderNo}</span> },
    { header: "用户", cell: (d) => <span className="text-muted-foreground">{d.userNo}</span> },
    { header: "押金", cell: (d) => <span className="tabular-nums">{money(d.amount, d.currency)}</span> },
    { header: "欠费", cell: (d) => <span className="tabular-nums">{d.arrearsAmount > 0 ? money(d.arrearsAmount, d.currency) : "-"}</span> },
    { header: "状态", cell: (d) => <Badge tone={DEP_STATUS[d.status].tone}>{DEP_STATUS[d.status].label}</Badge> },
    { header: "时间", cell: (d) => <span className="text-muted-foreground">{fmtTime(d.createdAt)}</span> },
    // 处置留痕上列表：谁在什么时候解冻/买断/催缴过，不用点进去才知道
    {
      header: "处置留痕",
      cell: (d) => {
        if (d.status === "RELEASED") return <span className="text-muted-foreground">解冻 {d.releasedAt ? fmtTime(d.releasedAt) : "-"}</span>;
        if (d.status === "BOUGHT_OUT") return <span className="tabular-nums">买断 {d.buyoutAmount != null ? money(d.buyoutAmount, d.currency) : "-"}</span>;
        if (d.status === "ARREARS") {
          return d.dunCount
            ? <span className="text-muted-foreground">已催缴 {d.dunCount} 次 · {d.lastDunAt ? fmtTime(d.lastDunAt) : "-"}</span>
            : <span className="text-muted-foreground">未催缴</span>;
        }
        return <span className="text-muted-foreground">-</span>;
      },
    },
    {
      header: "操作",
      // 可执行动作由押金状态机决定（已解冻/已买断是终态，不再出按钮）
      cell: (d) => {
        const acts = depositActions(d.status)
          .filter((a) => (a === "dun" ? canDun : canDepositManage));
        return acts.length
          ? (
            <div className="flex gap-1.5">
              {acts.map((a) => (
                <Button key={a} size="sm" variant="outline" disabled={depBusy} onClick={() => openDepAct(d, a)}>
                  {DEP_ACTION_LABEL[a]}
                </Button>
              ))}
            </div>
          )
          : <span className="text-muted-foreground">-</span>;
      },
    },
  ];

  const cplCols: Column<OrderComplaint>[] = [
    { header: "投诉号", cell: (c) => <span className="font-medium">{c.complaintNo}</span> },
    { header: "订单号", cell: (c) => <span className="text-muted-foreground">{c.orderNo}</span> },
    { header: "用户", cell: (c) => <span className="text-muted-foreground">{c.userNo}</span> },
    { header: "问题类型", cell: (c) => <Badge tone="outline">{ISSUE_LABEL[c.issueType]}</Badge> },
    { header: "用户描述", cell: (c) => <span className="text-muted-foreground">{c.description}</span> },
    {
      header: "截图",
      cell: (c) => c.screenshotUrl
        ? <a className="text-primary underline" href={c.screenshotUrl} target="_blank" rel="noreferrer">查看</a>
        : <span className="text-muted-foreground">-</span>,
    },
    { header: "提交时间", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.submittedAt)}</span> },
    { header: "状态", cell: (c) => <Badge tone={CPL_STATUS[c.status].tone}>{CPL_STATUS[c.status].label}</Badge> },
    { header: "处理人", cell: (c) => c.handlerName ?? <span className="text-muted-foreground">-</span> },
    { header: "处理结果", cell: (c) => c.resolution ? RESOLUTION_LABEL[c.resolution] : <span className="text-muted-foreground">-</span> },
    // 关联工单直接上列表：一眼看出投诉有没有落到运维手上
    { header: "关联工单", cell: (c) => c.workOrderNo ? <span className="tabular-nums">{c.workOrderNo}</span> : <span className="text-muted-foreground">-</span> },
    {
      header: "操作",
      cell: (c) => <Button size="sm" variant="outline" onClick={() => { setCplDetail(c); setCplResolution(c.resolution ?? "REFUND"); setCplNote(c.resolutionNote); }}>处理</Button>,
    },
  ];

  const rfdCols: Column<RefundRecord>[] = [
    { header: "退款单号", cell: (r) => <span className="font-medium">{r.refundNo}</span> },
    { header: "订单号", cell: (r) => <span className="text-muted-foreground">{r.orderNo}</span> },
    { header: "用户", cell: (r) => <span className="text-muted-foreground">{r.userNo}</span> },
    { header: "退款金额", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "原因", cell: (r) => <span className="text-muted-foreground">{r.reason}</span> },
    { header: "申请人 / 时间", cell: (r) => <>{r.applicantName} <span className="text-muted-foreground">· {fmtTime(r.appliedAt)}</span></> },
    { header: "状态", cell: (r) => <Badge tone={RFD_STATUS[r.status].tone}>{RFD_STATUS[r.status].label}</Badge> },
    { header: "审批人", cell: (r) => r.auditorName ?? <span className="text-muted-foreground">-</span> },
    // 幂等键 + PSP 流水号 = 资金操作可追溯的底线（防重复退款 / 对得上支付侧流水）
    { header: "幂等键", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.idempotencyKey}</span> },
    { header: "PSP 流水号", cell: (r) => r.psgTxnNo ? <span className="tabular-nums">{r.psgTxnNo}</span> : <span className="text-muted-foreground">-</span> },
    {
      header: "操作",
      cell: (r) => canAuditRefund && r.status === "PENDING"
        ? <Button size="sm" variant="outline" onClick={() => { setRfdDetail(r); setRfdApprove("1"); setRfdReject(""); }}>审批</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const excCols: Column<OrderException>[] = [
    { header: "订单号", cell: (e) => <span className="font-medium">{e.orderNo}</span> },
    { header: "异常类型", cell: (e) => <Badge tone="outline">{EXC_TYPE_LABEL[e.type]}</Badge> },
    { header: "柜机", cell: (e) => e.cabinetNo },
    { header: "用户", cell: (e) => <span className="text-muted-foreground">{e.userNo}</span> },
    { header: "涉及金额", cell: (e) => <span className="tabular-nums">{money(e.amount, e.currency)}</span> },
    { header: "状态", cell: (e) => <Badge tone={e.status === "HANDLED" ? "success" : "warning"}>{e.status === "HANDLED" ? "已处理" : "待处理"}</Badge> },
    { header: "发生时间", cell: (e) => <span className="text-muted-foreground">{fmtTime(e.createdAt)}</span> },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => setTab(k)} />

      {tab === "list" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索订单号 / 用户"
            onExport={() => exportCsv<RentOrder>("订单列表", [
              { header: "订单号", value: (o) => o.orderNo },
              { header: "用户", value: (o) => o.cUserNo },
              { header: "借出柜机", value: (o) => o.cabinetNo },
              { header: "点位", value: (o) => o.locationName },
              { header: "时长(分)", value: (o) => o.durationMin },
              { header: "费用", value: (o) => o.feeAmount },
              { header: "币种", value: (o) => o.currency },
              { header: "状态", value: (o) => t(`orderStatus.${o.status}`) },
            ], listQ.data?.list ?? [])}
          >
            <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              {/* 已创建/弹出中原先不在筛选里，而远程弹出干预会把订单落到「弹出中」，必须能筛出来 */}
              <option value="CREATED">已创建</option>
              <option value="DISPENSING">弹出中</option>
              <option value="IN_USE">使用中</option>
              <option value="SETTLED">已结算</option>
              <option value="RETURNED">已归还</option>
              <option value="EXCEPTION">异常</option>
              <option value="CLOSED">已关闭</option>
            </Select>
          </Toolbar>
          <DataTable
            rowKey={(o: RentOrder) => o.orderNo}
            columns={listCols}
            rows={listQ.data?.list}
            loading={listQ.isLoading}
            empty="暂无订单——当前筛选条件下没有记录，清空搜索/状态筛选或等待用户借出充电宝"
          />
          {listQ.data && <Pagination page={page} size={SIZE} total={listQ.data.total} onPage={setPage} />}
        </>
      )}

      {tab === "reservations" && (
        <>
          <Toolbar
            search={resKeyword}
            onSearch={(v) => { setResKeyword(v); setResPage(1); }}
            searchPlaceholder="搜索预约号 / 用户 / 站点 / 机柜 / 关联订单"
            onExport={() => exportCsv<Reservation>("预约订单", [
              { header: "预约号", value: (r) => r.reservationNo },
              { header: "用户", value: (r) => r.userNo },
              { header: "类型", value: (r) => RES_TYPE[r.type].label },
              { header: "目标站点", value: (r) => r.siteName },
              { header: "指定机柜", value: (r) => r.cabinetNo ?? "站点级" },
              { header: "预约开始", value: (r) => r.reservedFrom },
              { header: "预约结束", value: (r) => r.reservedTo },
              { header: "占位费", value: (r) => r.holdFee },
              { header: "状态", value: (r) => RES_STATUS[r.status].label },
              { header: "关联订单", value: (r) => r.orderNo },
            ], resQ.data?.list ?? [])}
          >
            <Select value={resType} onChange={(e) => { setResType(e.target.value); setResPage(1); }}>
              <option value="">全部类型</option>
              <option value="BORROW">预约取宝</option>
              <option value="RETURN">预约还位</option>
            </Select>
            <Select value={resStatus} onChange={(e) => { setResStatus(e.target.value); setResPage(1); }}>
              <option value="">全部状态</option>
              <option value="PENDING">待履约</option>
              <option value="FULFILLED">已履约</option>
              <option value="EXPIRED">已过期</option>
              <option value="CANCELLED">已取消</option>
            </Select>
          </Toolbar>
          {!canCancelRes && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无预约取消权限（order:order:update）</div>}
          <DataTable
            rowKey={(r: Reservation) => r.reservationNo}
            columns={resCols}
            rows={resQ.data?.list}
            loading={resQ.isLoading}
            // 即将超时的预约整行提示（B0 补丁 rowClassName）
            rowClassName={(r) => (isExpiringSoon(r) ? "bg-[color-mix(in_srgb,var(--destructive)_7%,transparent)]" : undefined)}
            empty="暂无预约记录——热门点位高峰期才会产生预约，或占位规则尚未在「业务规则」中开启"
          />
          {resQ.data && <Pagination page={resPage} size={SIZE} total={resQ.data.total} onPage={setResPage} />}
        </>
      )}

      {tab === "free" && (
        <>
          {/* 页头统计：免费单是真实成本，先看总量再看明细 */}
          <div className="mb-4 grid gap-3 sm:grid-cols-2">
            <StatCard label="本月免费单数" value={freeStatsQ.data ? `${freeStatsQ.data.monthCount} 单` : "-"} sub="来源于免费用户白名单" />
            <StatCard
              label="累计减免金额"
              value={freeStatsQ.data ? money(freeStatsQ.data.waivedTotal, freeStatsQ.data.currency) : "-"}
              sub="全量口径，非当页合计"
              tone="down"
            />
          </div>
          <Toolbar
            search={freeKeyword}
            onSearch={(v) => { setFreeKeyword(v); setFreePage(1); }}
            searchPlaceholder="搜索订单号 / 用户 / 昵称 / 站点 / 机柜"
            onExport={() => exportCsv<FreeOrder>("免费订单", [
              { header: "订单号", value: (f) => f.orderNo },
              { header: "用户", value: (f) => f.userNo },
              { header: "昵称", value: (f) => f.nickname },
              { header: "免费来源", value: (f) => REASON_LABEL[f.whitelistReason] },
              { header: "减免金额", value: (f) => f.waivedAmount },
              { header: "币种", value: (f) => f.currency },
              { header: "站点", value: (f) => f.siteName },
              { header: "机柜", value: (f) => f.cabinetNo },
              { header: "借出时间", value: (f) => f.startedAt },
              { header: "归还时间", value: (f) => f.endedAt },
              { header: "时长(分)", value: (f) => f.duration },
            ], freeQ.data?.list ?? [])}
          >
            <Select value={freeReason} onChange={(e) => { setFreeReason(e.target.value); setFreePage(1); }}>
              <option value="">全部来源</option>
              <option value="INTERNAL_TEST">内测</option>
              <option value="VIP">VIP</option>
              <option value="BD_DEMO">BD 演示</option>
              <option value="MERCHANT_SELF">商户自用</option>
            </Select>
          </Toolbar>
          <DataTable
            rowKey={(f: FreeOrder) => f.orderNo}
            columns={freeCols}
            rows={freeQ.data?.list}
            loading={freeQ.isLoading}
            empty="暂无免费订单——尚无白名单用户下单，白名单在「用户 · 免费用户白名单」维护"
          />
          {freeQ.data && <Pagination page={freePage} size={SIZE} total={freeQ.data.total} onPage={setFreePage} />}
        </>
      )}

      {tab === "exceptions" && (
        <>
          <Toolbar
            search={excKeyword}
            onSearch={(v) => { setExcKeyword(v); setExcPage(1); }}
            searchPlaceholder="搜索订单号 / 柜机 / 用户"
            onExport={() => exportCsv<OrderException>("异常订单", [
              { header: "订单号", value: (e) => e.orderNo },
              { header: "异常类型", value: (e) => EXC_TYPE_LABEL[e.type] },
              { header: "柜机", value: (e) => e.cabinetNo },
              { header: "用户", value: (e) => e.userNo },
              { header: "涉及金额", value: (e) => e.amount },
              { header: "币种", value: (e) => e.currency },
              { header: "状态", value: (e) => (e.status === "HANDLED" ? "已处理" : "待处理") },
              { header: "发生时间", value: (e) => e.createdAt },
            ], excQ.data?.list ?? [])}
          />
          <DataTable
            rowKey={(e: OrderException) => e.orderNo}
            columns={excCols}
            rows={excQ.data?.list}
            loading={excQ.isLoading}
            empty="暂无异常订单——未弹出/未归还/重复扣款等异常会自动汇入此处，也可放宽搜索条件再查"
          />
          {excQ.data && <Pagination page={excPage} size={SIZE} total={excQ.data.total} onPage={setExcPage} />}
        </>
      )}

      {tab === "complaints" && (
        <>
          <Toolbar
            search={cplKeyword}
            onSearch={(v) => { setCplKeyword(v); setCplPage(1); }}
            searchPlaceholder="搜索投诉号 / 订单号 / 用户 / 工单号"
            onExport={() => exportCsv<OrderComplaint>("投诉订单", [
              { header: "投诉号", value: (c) => c.complaintNo },
              { header: "订单号", value: (c) => c.orderNo },
              { header: "用户", value: (c) => c.userNo },
              { header: "问题类型", value: (c) => ISSUE_LABEL[c.issueType] },
              { header: "用户描述", value: (c) => c.description },
              { header: "截图", value: (c) => c.screenshotUrl },
              { header: "提交时间", value: (c) => c.submittedAt },
              { header: "状态", value: (c) => CPL_STATUS[c.status].label },
              { header: "处理人", value: (c) => c.handlerName },
              { header: "处理结果", value: (c) => (c.resolution ? RESOLUTION_LABEL[c.resolution] : "") },
              { header: "关联工单", value: (c) => c.workOrderNo },
            ], cplQ.data?.list ?? [])}
          >
            <Select value={cplStatus} onChange={(e) => { setCplStatus(e.target.value); setCplPage(1); }}>
              <option value="">全部状态</option>
              <option value="PENDING">待处理</option>
              <option value="PROCESSING">处理中</option>
              <option value="RESOLVED">已解决</option>
              <option value="REJECTED">已驳回</option>
            </Select>
          </Toolbar>
          <DataTable
            rowKey={(c: OrderComplaint) => c.complaintNo}
            columns={cplCols}
            rows={cplQ.data?.list}
            loading={cplQ.isLoading}
            empty="暂无投诉——C 端用户在订单内提交投诉后会进入此队列，处理结果可回写并转工单"
          />
          {cplQ.data && <Pagination page={cplPage} size={SIZE} total={cplQ.data.total} onPage={setCplPage} />}
        </>
      )}

      {tab === "refunds" && (
        <>
          <Toolbar
            search={rfdKeyword}
            onSearch={(v) => { setRfdKeyword(v); setRfdPage(1); }}
            searchPlaceholder="搜索退款单号 / 订单号 / 用户 / PSP 流水号"
            onExport={() => exportCsv<RefundRecord>("退款记录", [
              { header: "退款单号", value: (r) => r.refundNo },
              { header: "订单号", value: (r) => r.orderNo },
              { header: "用户", value: (r) => r.userNo },
              { header: "退款金额", value: (r) => r.amount },
              { header: "币种", value: (r) => r.currency },
              { header: "原因", value: (r) => r.reason },
              { header: "申请人", value: (r) => r.applicantName },
              { header: "申请时间", value: (r) => r.appliedAt },
              { header: "状态", value: (r) => RFD_STATUS[r.status].label },
              { header: "审批人", value: (r) => r.auditorName },
              { header: "幂等键", value: (r) => r.idempotencyKey },
              { header: "PSP 流水号", value: (r) => r.psgTxnNo },
            ], rfdQ.data?.list ?? [])}
          >
            <Select value={rfdStatus} onChange={(e) => { setRfdStatus(e.target.value); setRfdPage(1); }}>
              <option value="">全部状态</option>
              <option value="PENDING">待审批</option>
              <option value="APPROVED">已通过</option>
              <option value="EXECUTED">已退款</option>
              <option value="REJECTED">已驳回</option>
              <option value="FAILED">退款失败</option>
            </Select>
          </Toolbar>
          {!canAuditRefund && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无退款审批权限（order:refund:audit）</div>}
          <DataTable
            rowKey={(r: RefundRecord) => r.refundNo}
            columns={rfdCols}
            rows={rfdQ.data?.list}
            loading={rfdQ.isLoading}
            empty="暂无退款申请——客服在订单详情点「申请退款」后在此审批，审批通过才会真正出款"
          />
          {rfdQ.data && <Pagination page={rfdPage} size={SIZE} total={rfdQ.data.total} onPage={setRfdPage} />}
        </>
      )}

      {tab === "deposit" && (
        <>
          <Toolbar
            search={depKeyword}
            onSearch={(v) => { setDepKeyword(v); setDepPage(1); }}
            searchPlaceholder="搜索押金单号 / 订单号 / 用户"
            onExport={() => exportCsv<DepositRecord>("押金与欠费", [
              { header: "押金单号", value: (d) => d.depositNo },
              { header: "订单号", value: (d) => d.orderNo },
              { header: "用户", value: (d) => d.userNo },
              { header: "押金", value: (d) => d.amount },
              { header: "欠费", value: (d) => d.arrearsAmount },
              { header: "币种", value: (d) => d.currency },
              { header: "状态", value: (d) => DEP_STATUS[d.status].label },
              { header: "时间", value: (d) => d.createdAt },
              { header: "解冻时间", value: (d) => d.releasedAt },
              { header: "买断金额", value: (d) => d.buyoutAmount },
              { header: "买断时间", value: (d) => d.buyoutAt },
              { header: "催缴次数", value: (d) => d.dunCount },
              { header: "最后催缴", value: (d) => d.lastDunAt },
              { header: "处置操作人", value: (d) => d.operatorName },
              { header: "处置说明", value: (d) => d.note },
            ], depQ.data?.list ?? [])}
          >
            <Select value={depStatus} onChange={(e) => { setDepStatus(e.target.value); setDepPage(1); }}>
              <option value="">全部状态</option>
              <option value="HELD">已冻结</option>
              <option value="RELEASED">已解冻</option>
              <option value="BOUGHT_OUT">已买断</option>
              <option value="ARREARS">欠费</option>
            </Select>
          </Toolbar>
          {!canDeposit && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色既无押金处置权限（order:deposit:manage）也无催缴权限（order:arrears:dun）</div>}
          {canDeposit && !canDepositManage && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">当前角色仅可催缴欠费；解冻/买断需财务权限（order:deposit:manage）</div>}
          <DataTable
            rowKey={(d: DepositRecord) => d.depositNo}
            columns={depCols}
            rows={depQ.data?.list}
            loading={depQ.isLoading}
            empty="暂无押金记录——免押策略下不产生冻结记录，或该筛选条件下没有押金/欠费单"
          />
          {depQ.data && <Pagination page={depPage} size={SIZE} total={depQ.data.total} onPage={setDepPage} />}
        </>
      )}

      {/* 投诉处理抽屉：处理结果 + 说明；并提供「转工单」把投诉落到运维 */}
      <Drawer
        open={!!cplDetail}
        onOpenChange={(o) => !o && setCplDetail(null)}
        title={`投诉 ${cplDetail?.complaintNo ?? ""}`}
        desc="处理投诉并回写结果；设备类问题可直接转工单"
        footer={
          cplDetail && (
            <>
              {!cplDetail.workOrderNo && (
                <Button variant="outline" disabled={raiseCpl.isPending} onClick={() => raiseCpl.mutate(cplDetail.complaintNo)}>转工单</Button>
              )}
              <Button
                disabled={handleCpl.isPending || (cplResolution === "REJECT" && !cplNote.trim())}
                onClick={() => handleCpl.mutate({ no: cplDetail.complaintNo, resolution: cplResolution, note: cplNote })}
              >提交处理</Button>
            </>
          )
        }
      >
        {cplDetail && (
          <>
            <Field label="状态"><Badge tone={CPL_STATUS[cplDetail.status].tone}>{CPL_STATUS[cplDetail.status].label}</Badge></Field>
            {/* 关联订单/工单同屏：投诉不再是孤立记录 */}
            <Field label="关联订单">{cplDetail.orderNo}</Field>
            <Field label="关联工单">{cplDetail.workOrderNo ?? "未转工单"}</Field>
            <Field label="用户">{cplDetail.userNo}</Field>
            <Field label="问题类型">{ISSUE_LABEL[cplDetail.issueType]}</Field>
            <Field label="用户描述">{cplDetail.description}</Field>
            <Field label="投诉截图">
              {cplDetail.screenshotUrl
                ? <a className="text-primary underline" href={cplDetail.screenshotUrl} target="_blank" rel="noreferrer">查看</a>
                : "无"}
            </Field>
            <Field label="提交时间">{fmtTime(cplDetail.submittedAt)}</Field>
            <Field label="处理人 / 时间">{cplDetail.handlerName ?? "-"} · {cplDetail.handledAt ? fmtTime(cplDetail.handledAt) : "-"}</Field>
            <Field label="处理结果">
              <Select className="w-full" value={cplResolution} onChange={(e) => setCplResolution(e.target.value as ComplaintResolution)}>
                <option value="REFUND">退款</option>
                <option value="COMPENSATE">补偿</option>
                <option value="REJECT">驳回</option>
                <option value="EXPLAINED">已解释</option>
              </Select>
            </Field>
            <Field label={cplResolution === "REJECT" ? "处理说明（驳回必填）" : "处理说明"}>
              <Input value={cplNote} placeholder="填写处理说明，回写给用户" onChange={(e) => setCplNote(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      {/* 退款审批抽屉：通过 / 驳回（驳回必填原因）；幂等键与 PSP 流水号同屏可核 */}
      <Drawer
        open={!!rfdDetail}
        onOpenChange={(o) => !o && setRfdDetail(null)}
        title={`退款审批 ${rfdDetail?.refundNo ?? ""}`}
        desc="资金操作：通过后由 nearpay 执行退款，幂等键保证不重复出款"
        footer={
          rfdDetail && canAuditRefund && (
            <Button
              disabled={auditRfd.isPending || (rfdApprove === "0" && !rfdReject.trim())}
              variant={rfdApprove === "0" ? "destructive" : "default"}
              onClick={() => auditRfd.mutate({ no: rfdDetail.refundNo, approve: rfdApprove === "1", rejectReason: rfdReject })}
            >提交审批</Button>
          )
        }
      >
        {rfdDetail && (
          <>
            <Field label="状态"><Badge tone={RFD_STATUS[rfdDetail.status].tone}>{RFD_STATUS[rfdDetail.status].label}</Badge></Field>
            <Field label="关联订单">{rfdDetail.orderNo}</Field>
            <Field label="用户">{rfdDetail.userNo}</Field>
            <Field label="退款金额">{money(rfdDetail.amount, rfdDetail.currency)}</Field>
            <Field label="退款原因">{rfdDetail.reason}</Field>
            <Field label="申请人 / 时间">{rfdDetail.applicantName} · {fmtTime(rfdDetail.appliedAt)}</Field>
            <Field label="幂等键">{rfdDetail.idempotencyKey}</Field>
            <Field label="PSP 流水号">{rfdDetail.psgTxnNo ?? "未执行"}</Field>
            <Field label="审批结果">
              <Select className="w-full" value={rfdApprove} onChange={(e) => setRfdApprove(e.target.value)}>
                <option value="1">通过（执行退款）</option>
                <option value="0">驳回</option>
              </Select>
            </Field>
            {rfdApprove === "0" && (
              <Field label="驳回原因（必填）">
                <Input value={rfdReject} placeholder="说明驳回理由，回写给申请人" onChange={(e) => setRfdReject(e.target.value)} />
              </Field>
            )}
          </>
        )}
      </Drawer>

      <Drawer
        open={!!detail}
        onOpenChange={(o) => !o && setDetail(null)}
        title={`订单 ${detail?.orderNo ?? ""}`}
        desc="订单详情与客服干预（支付执行委托 nearpay）"
        footer={
          detail && (() => {
            // 可执行动作 = 状态机允许 ∩ 当前角色有权限（与 mock/后端同一份 ORDER_INTERVENTIONS）
            // 应收已为 0 的单不出「免单」按钮（mock/后端都会拒，出按钮只会让人白点一次）
            const acts = interveneActions(detail.status)
              .filter((a) => allow(IV_PERM[a]))
              .filter((a) => a !== "waive" || detail.feeAmount > 0);
            if (!acts.length) {
              return (
                <span className="text-sm text-muted-foreground">
                  {allow("order:intervene:execute") || allow("order:refund:apply")
                    ? `当前状态「${t(`orderStatus.${detail.status}`)}」没有可执行的干预动作`
                    : "仅可查看：当前角色无订单干预权限（order:intervene:execute）"}
                </span>
              );
            }
            return (
              <>
                {acts.map((a) => (
                  <Button
                    key={a}
                    variant={a === "waive" ? "destructive" : "outline"}
                    disabled={intervene.isPending}
                    onClick={() => { setIv({ order: detail, action: a }); setIvReason(""); setIvAmount(""); }}
                  >{IV_LABEL[a]}</Button>
                ))}
              </>
            );
          })()
        }
      >
        {detail && (
          <>
            <Field label="状态"><OrderStatusBadge s={detail.status} /></Field>
            <Field label="用户">{detail.cUserNo}</Field>
            <Field label="借出柜机 / 点位">{detail.cabinetNo} · {detail.locationName}</Field>
            <Field label="归还柜机">{detail.returnCabinetNo ?? "-"}</Field>
            <Field label="充电宝">{detail.powerbankNo ?? "-"}</Field>
            <Field label="借出时间">{fmtTime(detail.rentStartAt)}</Field>
            <Field label="归还时间">{fmtTime(detail.rentEndAt)}</Field>
            <Field label="时长">{detail.durationMin != null ? `${detail.durationMin} 分钟` : "-"}</Field>
            <Field label="费用 / 押金">{money(detail.feeAmount, detail.currency)} / {money(detail.depositAmount, detail.currency)}</Field>
            {/* 干预结果落在订单上：弹了几次、免了多少、补了多少 —— 光有日志不算落库 */}
            {!!detail.ejectCount && <Field label="远程弹出">{detail.ejectCount} 次 · 最近 {detail.lastEjectAt ? fmtTime(detail.lastEjectAt) : "-"}</Field>}
            {!!detail.waivedAmount && <Field label="已免单金额">{money(detail.waivedAmount, detail.currency)}</Field>}
            {!!detail.compensateAmount && <Field label="已补偿金额">{money(detail.compensateAmount, detail.currency)}（补至用户余额）</Field>}
            <Field label="干预历史">
              {ivHistoryQ.isLoading
                ? <span className="text-muted-foreground">加载中…</span>
                : ivHistoryQ.data?.list.length
                  ? (
                    <ol className="space-y-2.5">
                      {ivHistoryQ.data.list.map((x) => (
                        <li key={x.interventionNo} className="border-l-2 border-[var(--border)] pl-3">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <Badge tone="outline">{IV_LABEL[x.action]}</Badge>
                            <span className="text-xs text-muted-foreground tabular-nums">{x.interventionNo} · {fmtTime(x.createdAt)} · {x.operatorName}</span>
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {t(`orderStatus.${x.beforeStatus}`)} → {t(`orderStatus.${x.afterStatus}`)}
                            {x.amount != null ? ` · ${money(x.amount, x.currency)}` : ""}
                          </div>
                          <div className="text-sm">{x.reason}</div>
                        </li>
                      ))}
                    </ol>
                  )
                  : <span className="text-muted-foreground">无干预记录——该单未被人工处置过</span>}
            </Field>
          </>
        )}
      </Drawer>

      {/* 干预确认抽屉：原因必填（沿用退款审批口径），补偿另填金额；动作口径写在屉里，避免点了不知道改了什么 */}
      <Drawer
        open={!!iv}
        onOpenChange={(o) => !o && setIv(null)}
        title={iv ? `${IV_LABEL[iv.action]} · 订单 ${iv.order.orderNo}` : ""}
        desc="人工干预会改订单状态/金额并落一条审计记录，原因随记录永久留痕"
        footer={
          iv && (
            <Button
              variant={iv.action === "waive" || iv.action === "compensate" ? "destructive" : "default"}
              disabled={intervene.isPending || !ivReason.trim() || (iv.action === "compensate" && !(Number(ivAmount) > 0))}
              onClick={() => intervene.mutate({
                no: iv.order.orderNo,
                action: iv.action,
                reason: ivReason,
                amount: iv.action === "compensate" ? Number(ivAmount) : undefined,
              })}
            >确认{IV_LABEL[iv.action]}</Button>
          )
        }
      >
        {iv && (
          <>
            <Field label="订单 / 用户">{iv.order.orderNo} · {iv.order.cUserNo}</Field>
            <Field label="当前状态"><OrderStatusBadge s={iv.order.status} /></Field>
            <Field label="动作口径">{IV_DESC[iv.action]}</Field>
            <Field label="状态变化">
              {ORDER_INTERVENTIONS[iv.action].to
                ? `${t(`orderStatus.${iv.order.status}`)} → ${t(`orderStatus.${ORDER_INTERVENTIONS[iv.action].to!}`)}`
                : "状态不变（只记账与留痕）"}
            </Field>
            {iv.action === "waive" && (
              <Field label="减免金额">{money(iv.order.feeAmount, iv.order.currency)}（本单应收，确认后置 0）</Field>
            )}
            {iv.action === "compensate" && (
              <Field label={`补偿金额（${iv.order.currency}，必填）`}>
                <Input type="number" min="0" step="0.5" value={ivAmount} placeholder="补至用户余额的金额" onChange={(e) => setIvAmount(e.target.value)} />
              </Field>
            )}
            {iv.action === "refund_apply" && (
              <Field label="申请退款金额">{money(iv.order.feeAmount, iv.order.currency)}（本单实收，进「退款记录」待审批）</Field>
            )}
            <Field label="干预原因（必填）">
              <Input value={ivReason} placeholder="写清为什么干预，将随干预记录永久留痕" onChange={(e) => setIvReason(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      {/* 押金处置抽屉：解冻 / 买断 / 催缴共用；解冻与买断提交前再走 useConfirm（金额类二次确认） */}
      <Drawer
        open={!!depAct}
        onOpenChange={(o) => !o && setDepAct(null)}
        title={depAct ? `${DEP_ACTION_LABEL[depAct.action]}押金 ${depAct.row.depositNo}` : ""}
        desc="押金处置按状态机执行：已解冻/已买断是终态，欠费催缴只留痕不改状态"
        footer={
          depAct && (
            <Button
              variant={depAct.action === "buyout" ? "destructive" : "default"}
              disabled={
                depBusy
                || (depAct.action !== "dun" && !depReason.trim())
                || (depAct.action === "buyout" && !(Number(depAmount) > 0 && Number(depAmount) <= depAct.row.amount))
              }
              onClick={submitDepAct}
            >{depAct.action === "dun" ? "确认催缴" : `下一步：确认${DEP_ACTION_LABEL[depAct.action]}`}</Button>
          )
        }
      >
        {depAct && (
          <>
            <Field label="押金单 / 订单">{depAct.row.depositNo} · {depAct.row.orderNo}</Field>
            <Field label="用户">{depAct.row.userNo}</Field>
            <Field label="状态"><Badge tone={DEP_STATUS[depAct.row.status].tone}>{DEP_STATUS[depAct.row.status].label}</Badge></Field>
            <Field label="押金 / 欠费">
              {money(depAct.row.amount, depAct.row.currency)} / {depAct.row.arrearsAmount > 0 ? money(depAct.row.arrearsAmount, depAct.row.currency) : "无欠费"}
            </Field>
            {depAct.action === "release" && (
              <>
                <Field label="处置口径">解冻后押金退回用户原支付方式，状态转「已解冻」，不可撤销</Field>
                <Field label="解冻原因（必填）">
                  <Input value={depReason} placeholder="如：订单已结清，充电宝已归还" onChange={(e) => setDepReason(e.target.value)} />
                </Field>
              </>
            )}
            {depAct.action === "buyout" && (
              <>
                <Field label="处置口径">买断后押金不再退还，状态转「已买断」；买断金额不得超过押金额</Field>
                <Field label={`买断金额（${depAct.row.currency}，必填，≤ ${depAct.row.amount}）`}>
                  <Input type="number" min="0" step="1" value={depAmount} onChange={(e) => setDepAmount(e.target.value)} />
                </Field>
                <Field label="买断原因（必填）">
                  <Input value={depReason} placeholder="如：超时未归还，按买断处理" onChange={(e) => setDepReason(e.target.value)} />
                </Field>
              </>
            )}
            {depAct.action === "dun" && (
              <>
                <Field label="处置口径">催缴只记一次触达（次数 +1、记最后催缴时间），押金状态保持「欠费」</Field>
                <Field label="已催缴">{depAct.row.dunCount ? `${depAct.row.dunCount} 次 · 最近 ${depAct.row.lastDunAt ? fmtTime(depAct.row.lastDunAt) : "-"}` : "尚未催缴"}</Field>
                <Field label="催缴渠道（必选）">
                  <Select className="w-full" value={dunChannel} onChange={(e) => setDunChannel(e.target.value as DunChannel)}>
                    {(Object.keys(DUN_CHANNEL_LABEL) as DunChannel[]).map((c) => (
                      <option key={c} value={c}>{DUN_CHANNEL_LABEL[c]}</option>
                    ))}
                  </Select>
                </Field>
                <Field label="催缴备注">
                  <Input value={depReason} placeholder="可选：本次催缴的说明" onChange={(e) => setDepReason(e.target.value)} />
                </Field>
              </>
            )}
          </>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}

export default function OrdersPage() {
  return <Suspense fallback={null}><OrdersInner /></Suspense>;
}
