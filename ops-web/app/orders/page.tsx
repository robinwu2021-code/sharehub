"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Select } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { OrderStatusBadge } from "@/components/status";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import type { RentOrder, OrderException, DepositRecord } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "list", label: "订单列表" },
  { key: "exceptions", label: "异常订单" },
  { key: "deposit", label: "押金与欠费", phase: 2 as const },
];

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
    onSuccess: (_r, v) => { setMsg(`已${v.action === "refund" ? "退款" : v.action === "force_return" ? "强制归还" : "补偿"}：${v.no}`); qc.invalidateQueries({ queryKey: ["orders"] }); },
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

  // —— 押金与欠费 tab 状态（P2）——
  const [depPage, setDepPage] = useState(1);
  const [depKeyword, setDepKeyword] = useState("");
  const depQ = useQuery({
    queryKey: ["deposit-records", depPage, depKeyword],
    queryFn: () => api.listDepositRecords({ page: depPage, size: SIZE, keyword: depKeyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "deposit",
  });

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
    </div>
  );
}

export default function OrdersPage() {
  return <Suspense fallback={null}><OrdersInner /></Suspense>;
}
