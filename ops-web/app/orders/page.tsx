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
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type {
  RentOrder, OrderException, DepositRecord,
  OrderComplaint, RefundRecord, ComplaintIssueType, ComplaintResolution,
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
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "list");
  const { confirm, dialog } = useConfirm();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);

  // —— 订单列表 tab 状态 ——
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [detail, setDetail] = useState<RentOrder | null>(null);
  const [msg, setMsg] = useState("");

  const listQ = useQuery({
    queryKey: ["orders", page, keyword, status],
    queryFn: () => api.listOrders({ page, size: SIZE, keyword, status: status || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  const intervene = useMutation({
    mutationFn: (v: { no: string; action: string }) => api.interveneOrder(v.no, v.action),
    onSuccess: (_r, v) => {
      setMsg(`已${v.action === "refund" ? "退款" : v.action === "force_return" ? "强制归还" : v.action === "refund_apply" ? "提交退款申请（见「退款记录」待审批）" : "补偿"}：${v.no}`);
      qc.invalidateQueries({ queryKey: ["orders"] });
      // 申请退款会落一条退款申请，刷新审批队列
      if (v.action === "refund_apply") qc.invalidateQueries({ queryKey: ["refunds"] });
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

  // —— 押金与欠费 tab 状态（P2）——
  const [depPage, setDepPage] = useState(1);
  const [depKeyword, setDepKeyword] = useState("");
  const depQ = useQuery({
    queryKey: ["deposit-records", depPage, depKeyword],
    queryFn: () => api.listDepositRecords({ page: depPage, size: SIZE, keyword: depKeyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "deposit",
  });

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
          {msg && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm">{msg}</div>}
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索订单号 / 用户"
          >
            <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="IN_USE">使用中</option>
              <option value="SETTLED">已结算</option>
              <option value="RETURNED">已归还</option>
              <option value="EXCEPTION">异常</option>
              <option value="CLOSED">已关闭</option>
            </Select>
          </Toolbar>
          <DataTable rowKey={(o: RentOrder) => o.orderNo} columns={listCols} rows={listQ.data?.list} loading={listQ.isLoading} />
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
          />
          <DataTable rowKey={(e: OrderException) => e.orderNo} columns={excCols} rows={excQ.data?.list} loading={excQ.isLoading} />
          {excQ.data && <Pagination page={excPage} size={SIZE} total={excQ.data.total} onPage={setExcPage} />}
        </>
      )}

      {tab === "complaints" && (
        <>
          <Toolbar
            search={cplKeyword}
            onSearch={(v) => { setCplKeyword(v); setCplPage(1); }}
            searchPlaceholder="搜索投诉号 / 订单号 / 用户 / 工单号"
          >
            <Select value={cplStatus} onChange={(e) => { setCplStatus(e.target.value); setCplPage(1); }}>
              <option value="">全部状态</option>
              <option value="PENDING">待处理</option>
              <option value="PROCESSING">处理中</option>
              <option value="RESOLVED">已解决</option>
              <option value="REJECTED">已驳回</option>
            </Select>
          </Toolbar>
          <DataTable rowKey={(c: OrderComplaint) => c.complaintNo} columns={cplCols} rows={cplQ.data?.list} loading={cplQ.isLoading} />
          {cplQ.data && <Pagination page={cplPage} size={SIZE} total={cplQ.data.total} onPage={setCplPage} />}
        </>
      )}

      {tab === "refunds" && (
        <>
          <Toolbar
            search={rfdKeyword}
            onSearch={(v) => { setRfdKeyword(v); setRfdPage(1); }}
            searchPlaceholder="搜索退款单号 / 订单号 / 用户 / PSP 流水号"
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
          <DataTable rowKey={(r: RefundRecord) => r.refundNo} columns={rfdCols} rows={rfdQ.data?.list} loading={rfdQ.isLoading} />
          {rfdQ.data && <Pagination page={rfdPage} size={SIZE} total={rfdQ.data.total} onPage={setRfdPage} />}
        </>
      )}

      {tab === "deposit" && (
        <>
          <Toolbar
            search={depKeyword}
            onSearch={(v) => { setDepKeyword(v); setDepPage(1); }}
            searchPlaceholder="搜索押金单号 / 订单号 / 用户"
          />
          <DataTable rowKey={(d: DepositRecord) => d.depositNo} columns={depCols} rows={depQ.data?.list} loading={depQ.isLoading} />
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
          detail && (
            <>
              {allow("order:intervene:execute") && <Button variant="outline" disabled={intervene.isPending} onClick={() => intervene.mutate({ no: detail.orderNo, action: "force_return" })}>强制归还</Button>}
              {allow("order:intervene:execute") && <Button variant="outline" disabled={intervene.isPending} onClick={() => intervene.mutate({ no: detail.orderNo, action: "compensate" })}>补偿</Button>}
              {allow("order:refund:apply") && <Button variant="outline" disabled={intervene.isPending} onClick={() => intervene.mutate({ no: detail.orderNo, action: "refund_apply" })}>申请退款</Button>}
              {allow("order:refund:audit") && <Button variant="destructive" disabled={intervene.isPending} onClick={() => intervene.mutate({ no: detail.orderNo, action: "refund" })}>退款审批</Button>}
              {!allow("order:intervene:execute") && !allow("order:refund:apply") && !allow("order:refund:audit") && <span className="text-sm text-muted-foreground">无干预权限</span>}
            </>
          )
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
