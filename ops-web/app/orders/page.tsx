"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { RECENT_LIMIT } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination, StatCard } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Input, Select } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Timeline } from "@/components/ui/timeline";
import { Notice } from "@/components/ui/notice";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { OrderStatusBadge } from "@/components/status";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { ORDER_INTERVENTIONS, interveneActions, depositActions, exceptionHandleActions, EXCEPTION_HANDLINGS } from "@/lib/types";
import type {
  RentOrder, OrderException, OrderExceptionStatus, ExceptionHandleAction, DepositRecord,
  OrderComplaint, RefundRecord, ComplaintIssueType, ComplaintResolution,
  ComplaintCreatePayload, RefundApplyPayload,
  OrderInterventionAction, DepositAction, DunChannel,
  Reservation, FreeOrder, WhitelistReason,
} from "@/lib/types";

// 顺序对齐 lib/nav.ts 的深链顺序（交易流水 → 售后 → 特殊单据）
// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
const TAB_KEYS = ["list", "reservations", "exceptions", "complaints", "refunds", "free", "deposit"] as const;

// —— 预约订单（规格 §3）：竞品是电车预约充电桩，充电宝映射为「预约取宝 / 预约还位」——
const RES_TYPE: StatusMap<Reservation["type"]> = {
  BORROW: { label: "预约取宝", tone: "default" },
  RETURN: { label: "预约还位", tone: "warning" }, // 还位是占用空仓，与取宝挤兑的是相反资源，故换色
};
const RES_STATUS: StatusMap<Reservation["status"]> = {
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
/** 订单列表的状态筛选项。徽标文案走 i18n（OrderStatusBadge），此处是筛选口径，故显式列出。
 *  已创建/弹出中必须在列——远程弹出干预会把订单落到「弹出中」，筛不出来就找不回。 */
const ORDER_STATUS_FILTER = [
  { value: "CREATED", label: "已创建" }, { value: "DISPENSING", label: "弹出中" },
  { value: "IN_USE", label: "使用中" }, { value: "SETTLED", label: "已结算" },
  { value: "RETURNED", label: "已归还" }, { value: "EXCEPTION", label: "异常" },
  { value: "CLOSED", label: "已关闭" },
];

const REASON_LABEL: Record<WhitelistReason, string> = {
  INTERNAL_TEST: "内测",
  VIP: "VIP",
  BD_DEMO: "BD 演示",
  MERCHANT_SELF: "商户自用",
};

const REASON_OPTIONS = Object.entries(REASON_LABEL).map(([value, label]) => ({ value, label }));

const ISSUE_LABEL: Record<ComplaintIssueType, string> = {
  BILLING_DISPUTE: "计费争议",
  NOT_EJECTED: "未弹出",
  NOT_RETURNED: "未归还",
  DEVICE_FAULT: "设备故障",
  OTHER: "其他",
};

const CPL_STATUS: StatusMap<OrderComplaint["status"]> = {
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

const RFD_STATUS: StatusMap<RefundRecord["status"]> = {
  // 键序 = 退款筛选下拉的顺序（申请 → 通过 → 出款 → 驳回 → 失败）
  PENDING: { label: "待审批", tone: "warning" },
  APPROVED: { label: "已通过", tone: "default" },
  EXECUTED: { label: "已退款", tone: "success" },
  REJECTED: { label: "已驳回", tone: "muted" },
  FAILED: { label: "退款失败", tone: "danger" },
};

const DEP_STATUS: StatusMap<DepositRecord["status"]> = {
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

// —— 异常订单处置（S2：原先只有只读列表，order:exception:handle 定义了没人用）——
const EXC_STATUS: StatusMap<OrderExceptionStatus> = {
  PENDING: { label: "待处置", tone: "warning" },
  HANDLING: { label: "处置中", tone: "default" },
  HANDLED: { label: "已处置", tone: "success" },
};
const EXC_ACTION_LABEL: Record<ExceptionHandleAction, string> = {
  work_order: "转工单", refund: "发起退款", close: "直接关闭",
};
/** 每种处置到底会发生什么 —— 抽屉里写清楚，避免「点了不知道改了什么」。 */
const EXC_ACTION_DESC: Record<ExceptionHandleAction, string> = {
  work_order: "按异常类型开一条工单派给运维（设备类→故障单，计费类→投诉单），异常单转「处置中」，工单闭环后再回来关闭",
  refund: "落一条待审批的退款申请进「退款记录」，异常单转「处置中」；审批通过才真正出款",
  close: "无需下游动作，直接按结论结案：异常单转「已处置」（终态，不可再处置）",
};

function OrdersInner() {
  const sp = useSearchParams();
  const qKeyword = sp.get("keyword");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n(); // 导出订单状态用同一套 i18n 文案，避免与表格徽标不一致
  const { confirm, dialog } = useConfirm();

  // —— 订单列表 tab 状态 ——
  const paging = usePaging();
  const tabs = useNavTabs("/orders", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, paging.reset);
  // 关键词支持 ?keyword= 深链（经营看板「查订单」跳过来时预填单号）；
  // effect 兜住「已在本页时再点一次深链」——初始值只在挂载时生效，参数变化要跟着走。
  const [keyword, setKeyword] = useState(qKeyword ?? "");
  useEffect(() => { if (qKeyword != null) { setKeyword(qKeyword); paging.reset(); } }, [qKeyword]);
  const [status, setStatus] = useState("");
  const [detail, setDetail] = useState<RentOrder | null>(null);
  // 干预确认抽屉：原因必填（沿用退款审批口径），补偿另需金额
  const [iv, setIv] = useState<{ order: RentOrder; action: OrderInterventionAction } | null>(null);
  const [ivReason, setIvReason] = useState("");
  const [ivAmount, setIvAmount] = useState("");

  const listQ = useQuery({
    queryKey: ["orders", paging.page, paging.size, keyword, status],
    queryFn: () => api.listOrders({ page: paging.page, size: paging.size, keyword, status: status || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  // 干预历史（审计时间线）：只查当前详情订单的记录
  const ivHistoryQ = useQuery({
    queryKey: ["order-interventions", detail?.orderNo],
    queryFn: () => api.listOrderInterventions({ orderNo: detail!.orderNo, size: RECENT_LIMIT }),
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
  const excPaging = usePaging();
  const [excKeyword, setExcKeyword] = useState("");
  const [excStatus, setExcStatus] = useState("");
  const excQ = useQuery({
    queryKey: ["order-exceptions", excPaging.page, excPaging.size, excKeyword, excStatus],
    queryFn: () => api.listOrderExceptions({ page: excPaging.page, size: excPaging.size, keyword: excKeyword, status: excStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "exceptions",
  });
  const canHandleExc = allow("order:exception:handle");
  // 处置抽屉：三种方式共用一张表单，处置结论一律必填
  const [excAct, setExcAct] = useState<{ row: OrderException; action: ExceptionHandleAction } | null>(null);
  const [excResult, setExcResult] = useState("");
  const handleExc = useMutation({
    mutationFn: (v: { no: string; action: ExceptionHandleAction; result: string }) =>
      api.handleOrderException(v.no, v.action, { result: v.result }),
    onSuccess: (r, v) => {
      notify.success(
        v.action === "work_order" ? `已转工单 ${r.workOrderNo}`
          : v.action === "refund" ? `已发起退款申请 ${r.refundNo}（待审批）`
            : `异常单 ${r.orderNo} 已关闭`,
      );
      qc.invalidateQueries({ queryKey: ["order-exceptions"] });
      // 转工单会真的落一条工单、发起退款会落一条退款申请，两处列表同步刷新
      if (v.action === "work_order") qc.invalidateQueries({ queryKey: ["workorders"] });
      if (v.action === "refund") qc.invalidateQueries({ queryKey: ["refunds"] });
      setExcAct(null);
    },
  });

  // —— 投诉订单 tab 状态（B1）——
  const cplPaging = usePaging();
  const [cplKeyword, setCplKeyword] = useState("");
  const [cplStatus, setCplStatus] = useState("");
  const [cplDetail, setCplDetail] = useState<OrderComplaint | null>(null);
  const [cplResolution, setCplResolution] = useState<ComplaintResolution>("REFUND");
  const [cplNote, setCplNote] = useState("");
  const cplQ = useQuery({
    queryKey: ["complaints", cplPaging.page, cplPaging.size, cplKeyword, cplStatus],
    queryFn: () => api.listOrderComplaints({ page: cplPaging.page, size: cplPaging.size, keyword: cplKeyword, status: cplStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "complaints",
  });
  // 代客登记投诉：电话/线下投诉原先根本进不了队列，客服只能处理「从别处冒出来」的投诉。
  // 权限沿用 order:exception:handle —— 后端 createComplaint 用的就是这个码。
  const canCreateCpl = allow("order:exception:handle");
  const [cplNew, setCplNew] = useState<{ orderNo: string; userNo: string; issueType: ComplaintIssueType; description: string; screenshotUrl: string } | null>(null);
  const createCpl = useMutation({
    mutationFn: (v: ComplaintCreatePayload) => api.createOrderComplaint(v),
    onSuccess: (r) => {
      notify.success(`投诉已登记 ${r.complaintNo}（待处理）`);
      qc.invalidateQueries({ queryKey: ["complaints"] });
      setCplNew(null);
    },
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
  const rfdPaging = usePaging();
  const [rfdKeyword, setRfdKeyword] = useState("");
  const [rfdStatus, setRfdStatus] = useState("");
  const [rfdDetail, setRfdDetail] = useState<RefundRecord | null>(null);
  const [rfdApprove, setRfdApprove] = useState("1");
  const [rfdReject, setRfdReject] = useState("");
  const rfdQ = useQuery({
    queryKey: ["refunds", rfdPaging.page, rfdPaging.size, rfdKeyword, rfdStatus],
    queryFn: () => api.listRefundRecords({ page: rfdPaging.page, size: rfdPaging.size, keyword: rfdKeyword, status: rfdStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "refunds",
  });
  const auditRfd = useMutation({
    mutationFn: (v: { no: string; approve: boolean; rejectReason?: string }) => api.auditRefund(v.no, v.approve, v.rejectReason),
    onSuccess: (_r, v) => { notify.success(v.approve ? "退款已通过并执行" : "退款已驳回"); qc.invalidateQueries({ queryKey: ["refunds"] }); setRfdDetail(null); },
  });
  const canAuditRefund = allow("order:refund:audit");
  // 直接在队列里开退款单（原先只能绕订单详情做一次「申请退款」干预，金额还改不了）。
  // 幂等键在表单打开时生成一次并全程沿用：双击提交 / 网络重试用的是同一把键，服务端据此
  // 返回已有单而不是再退一笔 —— 没有键的重复提交就是真的退两次钱（口径同推送重发）。
  const canApplyRefund = allow("order:refund:apply");
  const [rfdNew, setRfdNew] = useState<{ orderNo: string; userNo: string; amount: string; reason: string; idempotencyKey: string } | null>(null);
  const createRfd = useMutation({
    mutationFn: (v: RefundApplyPayload) => api.createRefund(v),
    onSuccess: (r) => {
      notify.success(`退款申请已提交 ${r.refundNo} · ${money(r.amount, r.currency)}（待审批）`);
      qc.invalidateQueries({ queryKey: ["refunds"] });
      setRfdNew(null);
    },
  });
  const openRfdNew = () => setRfdNew({
    orderNo: "", userNo: "", amount: "", reason: "",
    idempotencyKey: `RF-MANUAL-${Date.now()}`,
  });
  const rfdNewOk = !!rfdNew && !!rfdNew.orderNo.trim() && !!rfdNew.reason.trim() && Number(rfdNew.amount) > 0;
  /** 退款是资金操作：提交前二次确认，文案写明退给哪一单、退多少。 */
  const submitRfdNew = async () => {
    if (!rfdNew || !rfdNewOk) return;
    const ok = await confirm({
      title: `新建退款申请 · 订单 ${rfdNew.orderNo.trim()}`,
      desc: `将提交一笔 ${Number(rfdNew.amount)} 的退款申请进审批队列（审批通过才真正出款）。本次携带幂等键 ${rfdNew.idempotencyKey}，重复提交不会重复退款。`,
      danger: true,
      confirmText: "确认提交",
      cancelText: "再想想",
    });
    if (!ok) return;
    createRfd.mutate({
      orderNo: rfdNew.orderNo.trim(),
      userNo: rfdNew.userNo.trim(),
      amount: Number(rfdNew.amount),
      reason: rfdNew.reason.trim(),
      idempotencyKey: rfdNew.idempotencyKey,
    });
  };

  // —— 押金与欠费 tab 状态（P2 → S1 补处置动作）——
  const depPaging = usePaging();
  const [depKeyword, setDepKeyword] = useState("");
  const [depStatus, setDepStatus] = useState("");
  const depQ = useQuery({
    queryKey: ["deposit-records", depPaging.page, depPaging.size, depKeyword, depStatus],
    queryFn: () => api.listDepositRecords({ page: depPaging.page, size: depPaging.size, keyword: depKeyword, status: depStatus || undefined }),
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
      // 买断不可逆（押金不再退还，规范 §12.6）：手输押金单号才解锁。
      // 解冻虽也不可撤销，但结果是「钱回到用户」，误操作代价远小于买断，故不加强确认。
      requireText: action === "buyout" ? row.depositNo : undefined,
    });
    if (!ok) return;
    if (action === "release") releaseDep.mutate({ no: row.depositNo, reason: depReason });
    else buyoutDep.mutate({ no: row.depositNo, amount: Number(depAmount), reason: depReason });
  };

  // —— 预约订单 tab（B4）——
  const resPaging = usePaging();
  const [resKeyword, setResKeyword] = useState("");
  const [resStatus, setResStatus] = useState("");
  const [resType, setResType] = useState("");
  const resQ = useQuery({
    queryKey: ["reservations", resPaging.page, resPaging.size, resKeyword, resStatus, resType],
    queryFn: () => api.listReservations({ page: resPaging.page, size: resPaging.size, keyword: resKeyword, status: resStatus || undefined, type: resType || undefined }),
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
  const freePaging = usePaging();
  const [freeKeyword, setFreeKeyword] = useState("");
  const [freeReason, setFreeReason] = useState("");
  const freeQ = useQuery({
    queryKey: ["free-orders", freePaging.page, freePaging.size, freeKeyword, freeReason],
    queryFn: () => api.listFreeOrders({ page: freePaging.page, size: freePaging.size, keyword: freeKeyword, reason: freeReason || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "free",
  });
  const freeStatsQ = useQuery({
    queryKey: ["free-order-stats"],
    queryFn: () => api.getFreeOrderStats(),
    enabled: tab === "free",
  });

  // 业务号列一律 txt-strong（规范 §12.3 主键列加强，扫描时有锚点）；
  // 金额/时长/计数列一律 text-right + tabular-nums（§12.4）——钱表里一半左一半右是真实的阅读缺陷。
  const resCols: Column<Reservation>[] = [
    { header: "预约号", cell: (r) => <span className="txt-strong tabular-nums">{r.reservationNo}</span> },
    { header: "用户", cell: (r) => <span className="text-muted-foreground">{r.userNo}</span> },
    { header: "类型", cell: (r) => <StatusBadge map={RES_TYPE} value={r.type} /> },
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
    { header: "占位费", className: "text-right", cell: (r) => <span className="tabular-nums">{r.holdFee > 0 ? money(r.holdFee, r.currency) : "-"}</span> },
    {
      header: "状态",
      cell: (r) => (
        <div className="flex items-center gap-1">
          <StatusBadge map={RES_STATUS} value={r.status} />
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
    { header: "订单号", cell: (f) => <span className="txt-strong tabular-nums">{f.orderNo}</span> },
    { header: "用户", cell: (f) => <span className="text-muted-foreground tabular-nums">{f.userNo}</span> },
    { header: "昵称", cell: (f) => f.nickname },
    { header: "免费来源", cell: (f) => <Badge tone="outline">{REASON_LABEL[f.whitelistReason]}</Badge> },
    { header: "减免金额", className: "text-right", cell: (f) => <span className="tabular-nums">{money(f.waivedAmount, f.currency)}</span> },
    { header: "站点", cell: (f) => f.siteName },
    { header: "机柜", cell: (f) => <span className="tabular-nums">{f.cabinetNo}</span> },
    { header: "借出", cell: (f) => <span className="text-muted-foreground">{fmtTime(f.startedAt)}</span> },
    { header: "归还", cell: (f) => <span className="text-muted-foreground">{fmtTime(f.endedAt)}</span> },
    { header: "时长", className: "text-right", cell: (f) => <span className="tabular-nums">{f.duration} 分</span> },
  ];

  const listCols: Column<RentOrder>[] = [
    { header: "订单号", cell: (o) => <span className="txt-strong tabular-nums">{o.orderNo}</span> },
    { header: "用户", cell: (o) => <span className="text-muted-foreground tabular-nums">{o.cUserNo}</span> },
    { header: "借出柜机", cell: (o) => <span className="tabular-nums">{o.cabinetNo}</span> },
    { header: "点位", cell: (o) => <span className="text-muted-foreground">{o.locationName}</span> },
    { header: "时长", className: "text-right", cell: (o) => <span className="tabular-nums">{o.durationMin != null ? `${o.durationMin} 分` : "-"}</span> },
    { header: "费用", className: "text-right", cell: (o) => <span className="tabular-nums">{money(o.feeAmount, o.currency)}</span> },
    { header: "状态", cell: (o) => <OrderStatusBadge s={o.status} /> },
    { header: "操作", cell: (o) => <Button size="sm" variant="outline" onClick={() => setDetail(o)}>详情</Button> },
  ];

  const depCols: Column<DepositRecord>[] = [
    { header: "押金单号", cell: (d) => <span className="txt-strong tabular-nums">{d.depositNo}</span> },
    { header: "订单号", cell: (d) => <span className="text-muted-foreground tabular-nums">{d.orderNo}</span> },
    { header: "用户", cell: (d) => <span className="text-muted-foreground tabular-nums">{d.userNo}</span> },
    { header: "押金", className: "text-right", cell: (d) => <span className="tabular-nums">{money(d.amount, d.currency)}</span> },
    // 欠费与押金同为金额列，右对齐才能上下位数对齐（原先两列一左一右）
    { header: "欠费", className: "text-right", cell: (d) => <span className="tabular-nums">{d.arrearsAmount > 0 ? money(d.arrearsAmount, d.currency) : "-"}</span> },
    { header: "状态", cell: (d) => <StatusBadge map={DEP_STATUS} value={d.status} /> },
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
    { header: "投诉号", cell: (c) => <span className="txt-strong tabular-nums">{c.complaintNo}</span> },
    { header: "订单号", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.orderNo}</span> },
    { header: "用户", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.userNo}</span> },
    { header: "问题类型", cell: (c) => <Badge tone="outline">{ISSUE_LABEL[c.issueType]}</Badge> },
    { header: "用户描述", cell: (c) => <span className="text-muted-foreground">{c.description}</span> },
    {
      header: "截图",
      cell: (c) => c.screenshotUrl
        ? <a className="text-primary underline" href={c.screenshotUrl} target="_blank" rel="noreferrer">查看</a>
        : <span className="text-muted-foreground">-</span>,
    },
    { header: "提交时间", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.submittedAt)}</span> },
    { header: "状态", cell: (c) => <StatusBadge map={CPL_STATUS} value={c.status} /> },
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
    { header: "退款单号", cell: (r) => <span className="txt-strong tabular-nums">{r.refundNo}</span> },
    { header: "订单号", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.orderNo}</span> },
    { header: "用户", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.userNo}</span> },
    { header: "退款金额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "原因", cell: (r) => <span className="text-muted-foreground">{r.reason}</span> },
    { header: "申请人 / 时间", cell: (r) => <>{r.applicantName} <span className="text-muted-foreground">· {fmtTime(r.appliedAt)}</span></> },
    { header: "状态", cell: (r) => <StatusBadge map={RFD_STATUS} value={r.status} /> },
    { header: "审批人", cell: (r) => r.auditorName ?? <span className="text-muted-foreground">-</span> },
    // 幂等键 + PSP 流水号 = 资金操作可追溯的底线（防重复退款 / 对得上支付侧流水）
    { header: "幂等键", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.idempotencyKey}</span> },
    { header: "PSP 流水号", cell: (r) => r.pspTxnNo ? <span className="tabular-nums">{r.pspTxnNo}</span> : <span className="text-muted-foreground">-</span> },
    {
      header: "操作",
      cell: (r) => canAuditRefund && r.status === "PENDING"
        ? <Button size="sm" variant="outline" onClick={() => { setRfdDetail(r); setRfdApprove("1"); setRfdReject(""); }}>审批</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const excCols: Column<OrderException>[] = [
    { header: "订单号", cell: (e) => <span className="txt-strong tabular-nums">{e.orderNo}</span> },
    { header: "异常类型", cell: (e) => <Badge tone="outline">{EXC_TYPE_LABEL[e.type]}</Badge> },
    { header: "柜机", cell: (e) => <span className="tabular-nums">{e.cabinetNo}</span> },
    { header: "用户", cell: (e) => <span className="text-muted-foreground tabular-nums">{e.userNo}</span> },
    { header: "涉及金额", className: "text-right", cell: (e) => <span className="tabular-nums">{money(e.amount, e.currency)}</span> },
    { header: "状态", cell: (e) => <StatusBadge map={EXC_STATUS} value={e.status} /> },
    { header: "发生时间", cell: (e) => <span className="text-muted-foreground">{fmtTime(e.createdAt)}</span> },
    // 处置留痕上列表：不用点进去就知道谁在什么时候按什么方式处置过、下游单号是多少
    {
      header: "处置留痕",
      cell: (e) => e.handledAt
        ? (
          <div className="text-xs text-muted-foreground">
            <div>{e.handleAction ? EXC_ACTION_LABEL[e.handleAction] : "-"} · {e.handledBy} · {fmtTime(e.handledAt)}</div>
            <div className="text-foreground">{e.handleResult}</div>
          </div>
        )
        : <span className="text-muted-foreground">未处置</span>,
    },
    {
      header: "下游单据",
      cell: (e) => (e.workOrderNo || e.refundNo)
        ? (
          <div className="flex flex-col gap-0.5 text-xs tabular-nums">
            {e.workOrderNo && <span>工单 {e.workOrderNo}</span>}
            {e.refundNo && <span>退款 {e.refundNo}</span>}
          </div>
        )
        : <span className="text-muted-foreground">-</span>,
    },
    {
      header: "操作",
      // 可执行动作由状态机决定：HANDLED 是终态，不再出按钮；已转过的工单/已发起的退款也不重复出
      cell: (e) => {
        if (!canHandleExc) return <span className="text-muted-foreground">-</span>;
        const acts = exceptionHandleActions(e.status)
          .filter((a) => !(a === "work_order" && e.workOrderNo) && !(a === "refund" && e.refundNo));
        return acts.length
          ? (
            <div className="flex gap-1.5">
              {acts.map((a) => (
                <Button
                  key={a}
                  size="sm"
                  variant={a === "close" ? "outline" : "default"}
                  disabled={handleExc.isPending}
                  onClick={() => { setExcAct({ row: e, action: a }); setExcResult(""); }}
                >{EXC_ACTION_LABEL[a]}</Button>
              ))}
            </div>
          )
          : <span className="text-muted-foreground">-</span>;
      },
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "list" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
            <FilterSelect value={status} onChange={(v) => { setStatus(v); paging.reset(); }} allLabel="全部状态" options={ORDER_STATUS_FILTER} />
          </Toolbar>
          <DataTable
            rowKey={(o: RentOrder) => o.orderNo}
            columns={listCols}
            rows={listQ.data?.list}
            loading={listQ.isLoading} error={listQ.error} onRetry={listQ.refetch}
            empty="暂无订单——当前筛选条件下没有记录，清空搜索/状态筛选或等待用户借出充电宝"
          />
          {listQ.data && <Pagination page={paging.page} size={paging.size} total={listQ.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
        </>
      )}

      {tab === "reservations" && (
        <>
          <Toolbar
            search={resKeyword}
            onSearch={(v) => { setResKeyword(v); resPaging.reset(); }}
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
            <FilterSelect value={resType} onChange={(v) => { setResType(v); resPaging.reset(); }} allLabel="全部类型" options={RES_TYPE} />
            <FilterSelect value={resStatus} onChange={(v) => { setResStatus(v); resPaging.reset(); }} allLabel="全部状态" options={RES_STATUS} />
          </Toolbar>
          {!canCancelRes && <ReadOnlyNotice what="预约取消" perm="order:order:update" />}
          <DataTable
            rowKey={(r: Reservation) => r.reservationNo}
            columns={resCols}
            rows={resQ.data?.list}
            loading={resQ.isLoading} error={resQ.error} onRetry={resQ.refetch}
            // 即将超时的预约整行提示（B0 补丁 rowClassName）
            rowClassName={(r) => (isExpiringSoon(r) ? "bg-[color-mix(in_srgb,var(--destructive)_7%,transparent)]" : undefined)}
            empty="暂无预约记录——热门点位高峰期才会产生预约，或占位规则尚未在「业务规则」中开启"
          />
          {resQ.data && <Pagination page={resPaging.page} size={resPaging.size} total={resQ.data.total} onPage={resPaging.setPage} onSize={resPaging.setSize} />}
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
            onSearch={(v) => { setFreeKeyword(v); freePaging.reset(); }}
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
            <FilterSelect value={freeReason} onChange={(v) => { setFreeReason(v); freePaging.reset(); }} allLabel="全部来源" options={REASON_OPTIONS} />
          </Toolbar>
          <DataTable
            rowKey={(f: FreeOrder) => f.orderNo}
            columns={freeCols}
            rows={freeQ.data?.list}
            loading={freeQ.isLoading} error={freeQ.error} onRetry={freeQ.refetch}
            empty="暂无免费订单——尚无白名单用户下单，白名单在「用户 · 免费用户白名单」维护"
          />
          {freeQ.data && <Pagination page={freePaging.page} size={freePaging.size} total={freeQ.data.total} onPage={freePaging.setPage} onSize={freePaging.setSize} />}
        </>
      )}

      {tab === "exceptions" && (
        <>
          <Toolbar
            search={excKeyword}
            onSearch={(v) => { setExcKeyword(v); excPaging.reset(); }}
            searchPlaceholder="搜索订单号 / 柜机 / 用户 / 工单号 / 退款号"
            onExport={() => exportCsv<OrderException>("异常订单", [
              { header: "订单号", value: (e) => e.orderNo },
              { header: "异常类型", value: (e) => EXC_TYPE_LABEL[e.type] },
              { header: "柜机", value: (e) => e.cabinetNo },
              { header: "用户", value: (e) => e.userNo },
              { header: "涉及金额", value: (e) => e.amount },
              { header: "币种", value: (e) => e.currency },
              { header: "状态", value: (e) => EXC_STATUS[e.status].label },
              { header: "发生时间", value: (e) => e.createdAt },
              { header: "处置方式", value: (e) => (e.handleAction ? EXC_ACTION_LABEL[e.handleAction] : "") },
              { header: "处置结论", value: (e) => e.handleResult },
              { header: "处置人", value: (e) => e.handledBy },
              { header: "处置时间", value: (e) => e.handledAt },
              { header: "关联工单", value: (e) => e.workOrderNo },
              { header: "关联退款", value: (e) => e.refundNo },
            ], excQ.data?.list ?? [])}
          >
            <FilterSelect value={excStatus} onChange={(v) => { setExcStatus(v); excPaging.reset(); }} allLabel="全部状态" options={EXC_STATUS} />
          </Toolbar>
          {!canHandleExc && <ReadOnlyNotice what="异常订单处置" perm="order:exception:handle" />}
          <DataTable
            rowKey={(e: OrderException) => e.orderNo}
            columns={excCols}
            rows={excQ.data?.list}
            loading={excQ.isLoading} error={excQ.error} onRetry={excQ.refetch}
            empty="暂无异常订单——未弹出/未归还/重复扣款等异常会自动汇入此处，也可放宽搜索条件再查"
          />
          {excQ.data && <Pagination page={excPaging.page} size={excPaging.size} total={excQ.data.total} onPage={excPaging.setPage} onSize={excPaging.setSize} />}
        </>
      )}

      {tab === "complaints" && (
        <>
          <Toolbar
            search={cplKeyword}
            onSearch={(v) => { setCplKeyword(v); cplPaging.reset(); }}
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
            onAdd={() => setCplNew({ orderNo: "", userNo: "", issueType: "BILLING_DISPUTE", description: "", screenshotUrl: "" })}
            addLabel="新建投诉"
            canAdd={canCreateCpl}
          >
            <FilterSelect value={cplStatus} onChange={(v) => { setCplStatus(v); cplPaging.reset(); }} allLabel="全部状态" options={CPL_STATUS} />
          </Toolbar>
          <DataTable
            rowKey={(c: OrderComplaint) => c.complaintNo}
            columns={cplCols}
            rows={cplQ.data?.list}
            loading={cplQ.isLoading} error={cplQ.error} onRetry={cplQ.refetch}
            empty="暂无投诉——C 端用户在订单内提交投诉后会进入此队列，处理结果可回写并转工单"
          />
          {cplQ.data && <Pagination page={cplPaging.page} size={cplPaging.size} total={cplQ.data.total} onPage={cplPaging.setPage} onSize={cplPaging.setSize} />}
        </>
      )}

      {tab === "refunds" && (
        <>
          <Toolbar
            search={rfdKeyword}
            onSearch={(v) => { setRfdKeyword(v); rfdPaging.reset(); }}
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
              { header: "PSP 流水号", value: (r) => r.pspTxnNo },
            ], rfdQ.data?.list ?? [])}
            onAdd={openRfdNew}
            addLabel="新建退款"
            canAdd={canApplyRefund}
          >
            <FilterSelect value={rfdStatus} onChange={(v) => { setRfdStatus(v); rfdPaging.reset(); }} allLabel="全部状态" options={RFD_STATUS} />
          </Toolbar>
          {/* 申请与审批是两个码：客服能开单不能出款，财务能出款不能开单，两种缺权要分别说清 */}
          {!canAuditRefund && !canApplyRefund && <ReadOnlyNotice what="退款申请 / 退款审批" perm={["order:refund:apply", "order:refund:audit"]} />}
          {/* 半降级（能开单不能出款）不是纯只读，故用 Notice 自述而非 ReadOnlyNotice */}
          {!canAuditRefund && canApplyRefund && <Notice>当前角色仅可新建退款申请；审批出款需财务权限（order:refund:audit）</Notice>}
          <DataTable
            rowKey={(r: RefundRecord) => r.refundNo}
            columns={rfdCols}
            rows={rfdQ.data?.list}
            loading={rfdQ.isLoading} error={rfdQ.error} onRetry={rfdQ.refetch}
            empty="暂无退款申请——客服在订单详情点「申请退款」后在此审批，审批通过才会真正出款"
          />
          {rfdQ.data && <Pagination page={rfdPaging.page} size={rfdPaging.size} total={rfdQ.data.total} onPage={rfdPaging.setPage} onSize={rfdPaging.setSize} />}
        </>
      )}

      {tab === "deposit" && (
        <>
          <Toolbar
            search={depKeyword}
            onSearch={(v) => { setDepKeyword(v); depPaging.reset(); }}
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
            <FilterSelect value={depStatus} onChange={(v) => { setDepStatus(v); depPaging.reset(); }} allLabel="全部状态" options={DEP_STATUS} />
          </Toolbar>
          {!canDeposit && <ReadOnlyNotice what="押金处置 / 欠费催缴" perm={["order:deposit:manage", "order:arrears:dun"]} />}
          {canDeposit && !canDepositManage && <Notice>当前角色仅可催缴欠费；解冻/买断需财务权限（order:deposit:manage）</Notice>}
          <DataTable
            rowKey={(d: DepositRecord) => d.depositNo}
            columns={depCols}
            rows={depQ.data?.list}
            loading={depQ.isLoading} error={depQ.error} onRetry={depQ.refetch}
            empty="暂无押金记录——免押策略下不产生冻结记录，或该筛选条件下没有押金/欠费单"
          />
          {depQ.data && <Pagination page={depPaging.page} size={depPaging.size} total={depQ.data.total} onPage={depPaging.setPage} onSize={depPaging.setSize} />}
        </>
      )}

      {/* 代客登记投诉抽屉：客服接到电话/线下投诉时手工入队。
          投诉号/提交时间/状态由服务端决定（新登记一律「待处理」），故表单里没有这几项。 */}
      <Drawer
        open={!!cplNew}
        onOpenChange={(o) => !o && setCplNew(null)}
        title="新建投诉"
        desc="客服代客登记电话 / 线下投诉；C 端用户自助提交的投诉会自动进入本队列"
        footer={
          cplNew && (
            <Button
              disabled={createCpl.isPending || !cplNew.orderNo.trim() || !cplNew.description.trim()}
              onClick={() => createCpl.mutate({
                orderNo: cplNew.orderNo.trim(),
                userNo: cplNew.userNo.trim(),
                issueType: cplNew.issueType,
                description: cplNew.description.trim(),
                screenshotUrl: cplNew.screenshotUrl.trim() || undefined,
              })}
            >提交登记</Button>
          )
        }
      >
        {cplNew && (
          <>
            <Field label="关联订单号（必填）">
              <Input value={cplNew.orderNo} placeholder="如 ORD500001，须为真实存在的订单" onChange={(e) => setCplNew({ ...cplNew, orderNo: e.target.value })} />
            </Field>
            {/* 留空即取该订单的下单人：客服现场往往只问到订单号 */}
            <Field label="用户号（可空，默认取订单下单人）">
              <Input value={cplNew.userNo} placeholder="如 U3001" onChange={(e) => setCplNew({ ...cplNew, userNo: e.target.value })} />
            </Field>
            <Field label="问题类型">
              <Select className="w-full" value={cplNew.issueType} onChange={(e) => setCplNew({ ...cplNew, issueType: e.target.value as ComplaintIssueType })}>
                {(Object.keys(ISSUE_LABEL) as ComplaintIssueType[]).map((k) => (
                  <option key={k} value={k}>{ISSUE_LABEL[k]}</option>
                ))}
              </Select>
            </Field>
            <Field label="用户描述（必填）">
              <Input value={cplNew.description} placeholder="按用户原话记录问题，随投诉永久留痕" onChange={(e) => setCplNew({ ...cplNew, description: e.target.value })} />
            </Field>
            <Field label="投诉截图（可空）">
              <Input value={cplNew.screenshotUrl} placeholder="用户提供的截图链接；电话投诉通常没有" onChange={(e) => setCplNew({ ...cplNew, screenshotUrl: e.target.value })} />
            </Field>
            <Field label="登记后状态">待处理（处理人 / 处理结果由后续「处理」动作回写）</Field>
          </>
        )}
      </Drawer>

      {/* 新建退款申请抽屉：落 PENDING 进本队列，审批通过才出款。
          幂等键随抽屉生成并同屏展示 —— 重复提交（双击 / 重试）服务端返回已有单，不重复出款。 */}
      <Drawer
        open={!!rfdNew}
        onOpenChange={(o) => !o && setRfdNew(null)}
        title="新建退款申请"
        desc="资金操作：只提交申请，审批通过后才由 nearpay 执行退款"
        footer={
          rfdNew && (
            <Button variant="destructive" disabled={createRfd.isPending || !rfdNewOk} onClick={submitRfdNew}>
              下一步：确认提交
            </Button>
          )
        }
      >
        {rfdNew && (
          <>
            <Field label="关联订单号（必填）">
              <Input value={rfdNew.orderNo} placeholder="如 ORD500001，须为真实存在的订单" onChange={(e) => setRfdNew({ ...rfdNew, orderNo: e.target.value })} />
            </Field>
            <Field label="用户号（可空，默认取订单下单人）">
              <Input value={rfdNew.userNo} placeholder="如 U3001" onChange={(e) => setRfdNew({ ...rfdNew, userNo: e.target.value })} />
            </Field>
            {/* 币种不出表单：退款与原收款必须同币种，服务端一律跟随订单 */}
            <Field label="退款金额（必填，> 0）">
              <Input type="number" min="0" step="0.5" value={rfdNew.amount} placeholder="退回给用户的金额" onChange={(e) => setRfdNew({ ...rfdNew, amount: e.target.value })} />
            </Field>
            <Field label="退款原因（必填）">
              <Input value={rfdNew.reason} placeholder="写清为什么退，审批人据此判断" onChange={(e) => setRfdNew({ ...rfdNew, reason: e.target.value })} />
            </Field>
            <Field label="幂等键">
              <span className="tabular-nums">{rfdNew.idempotencyKey}</span>
            </Field>
            <Field label="提交后状态">
              待审批（申请人 / 申请时间 / 退款单号由服务端回填）；本次提交携带上面这把幂等键，重复提交服务端返回已有单——没有键的重复提交就是真的退两笔钱。
            </Field>
          </>
        )}
      </Drawer>

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
            <Field label="状态"><StatusBadge map={CPL_STATUS} value={cplDetail.status} /></Field>
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
            <Field label="状态"><StatusBadge map={RFD_STATUS} value={rfdDetail.status} /></Field>
            <Field label="关联订单">{rfdDetail.orderNo}</Field>
            <Field label="用户">{rfdDetail.userNo}</Field>
            <Field label="退款金额">{money(rfdDetail.amount, rfdDetail.currency)}</Field>
            <Field label="退款原因">{rfdDetail.reason}</Field>
            <Field label="申请人 / 时间">{rfdDetail.applicantName} · {fmtTime(rfdDetail.appliedAt)}</Field>
            <Field label="幂等键">{rfdDetail.idempotencyKey}</Field>
            <Field label="PSP 流水号">{rfdDetail.pspTxnNo ?? "未执行"}</Field>
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
              // 「没动作」有两种原因，必须分开说：状态机不允许 vs 缺权限。
              // 后者交给 ReadOnlyNotice —— 句式与各列表页上方那条一致，且权限码由它统一列出（§13）
              return allow("order:intervene:execute") || allow("order:refund:apply") ? (
                <span className="text-sm text-muted-foreground">
                  当前状态「{t(`orderStatus.${detail.status}`)}」没有可执行的干预动作
                </span>
              ) : (
                <ReadOnlyNotice className="mb-0" what="订单干预" perm={["order:intervene:execute", "order:refund:apply"]} />
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
              <Timeline
                loading={ivHistoryQ.isLoading}
                empty="无干预记录——该单未被人工处置过"
                items={(ivHistoryQ.data?.list ?? []).map((x) => ({
                  key: x.interventionNo,
                  badge: { label: IV_LABEL[x.action], tone: "outline" as const },
                  meta: `${x.interventionNo} · ${fmtTime(x.createdAt)} · ${x.operatorName}`,
                  change: `${t(`orderStatus.${x.beforeStatus}`)} → ${t(`orderStatus.${x.afterStatus}`)}${x.amount != null ? ` · ${money(x.amount, x.currency)}` : ""}`,
                  text: x.reason,
                }))}
              />
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
            <Field label="状态"><StatusBadge map={DEP_STATUS} value={depAct.row.status} /></Field>
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

      {/* 异常单处置抽屉：转工单 / 发起退款 / 直接关闭共用；处置结论必填，关闭是终态故走二次确认 */}
      <Drawer
        open={!!excAct}
        onOpenChange={(o) => !o && setExcAct(null)}
        title={excAct ? `${EXC_ACTION_LABEL[excAct.action]} · 异常单 ${excAct.row.orderNo}` : ""}
        desc="处置按状态机执行：转工单/发起退款转「处置中」，直接关闭是终态；结论随异常单永久留痕"
        footer={
          excAct && (
            <Button
              variant={excAct.action === "close" ? "destructive" : "default"}
              disabled={handleExc.isPending || !excResult.trim()}
              onClick={async () => {
                if (excAct.action === "close") {
                  const ok = await confirm({
                    title: `关闭异常单 ${excAct.row.orderNo}`,
                    desc: "「已处置」是终态：关闭后该异常单不能再转工单、发起退款或重新打开。",
                    danger: true,
                    confirmText: "确认关闭",
                    cancelText: "再想想",
                  });
                  if (!ok) return;
                }
                handleExc.mutate({ no: excAct.row.orderNo, action: excAct.action, result: excResult });
              }}
            >{excAct.action === "close" ? "下一步：确认关闭" : `确认${EXC_ACTION_LABEL[excAct.action]}`}</Button>
          )
        }
      >
        {excAct && (
          <>
            <Field label="异常单 / 用户">{excAct.row.orderNo} · {excAct.row.userNo}</Field>
            <Field label="异常类型"><Badge tone="outline">{EXC_TYPE_LABEL[excAct.row.type]}</Badge></Field>
            <Field label="柜机 / 涉及金额">{excAct.row.cabinetNo} · {money(excAct.row.amount, excAct.row.currency)}</Field>
            <Field label="当前状态"><StatusBadge map={EXC_STATUS} value={excAct.row.status} /></Field>
            <Field label="处置口径">{EXC_ACTION_DESC[excAct.action]}</Field>
            <Field label="状态变化">
              {EXC_STATUS[excAct.row.status].label} → {EXC_STATUS[EXCEPTION_HANDLINGS[excAct.action].to].label}
            </Field>
            {(excAct.row.workOrderNo || excAct.row.refundNo) && (
              <Field label="已有下游单据">
                {[excAct.row.workOrderNo && `工单 ${excAct.row.workOrderNo}`, excAct.row.refundNo && `退款 ${excAct.row.refundNo}`]
                  .filter(Boolean).join(" · ")}
              </Field>
            )}
            {excAct.row.handledAt && (
              <Field label="上次处置">
                {excAct.row.handleAction ? EXC_ACTION_LABEL[excAct.row.handleAction] : "-"} · {excAct.row.handledBy} · {fmtTime(excAct.row.handledAt)}
                <div className="text-sm">{excAct.row.handleResult}</div>
              </Field>
            )}
            <Field label="处置结论（必填）">
              <Input value={excResult} placeholder="写清查明的原因与处置理由，将随异常单永久留痕" onChange={(e) => setExcResult(e.target.value)} />
            </Field>
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
