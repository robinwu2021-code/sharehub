"use client";

// 充值订单（OM05-07「充值订单与套餐」的订单半边）。
//
// 2026-10-28 从「财务管理 › 用户账」搬到「用户管理 › 资产」（docs/requirements/运营端菜单清单-按运营顺序.md）：
// 充值订单与充值套餐是同一件事的两面（卖什么 / 卖出去了什么），放在同一个页签里切换着看。
// 内容原样搬出（筛选 / 列 / 导出），行为不变；财务页不再留第二份。
// 此前它借用财务页共用的分页查询，搬出来后自带查询与分页。
import { useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { DateInput } from "@/components/ui/date-input";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Badge } from "@/components/ui/badge";
import { money, fmtTime } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import type { RechargeOrder } from "@/lib/types";

const RECHARGE_STATUS: StatusMap<RechargeOrder["status"]> = {
  PENDING: { label: "待支付", tone: "warning" },
  PAID: { label: "已支付", tone: "success" },
  FAILED: { label: "支付失败", tone: "danger" },
  REFUNDED: { label: "已退款", tone: "muted" },
};

export function RechargeOrdersPanel() {
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [rcStatus, setRcStatus] = useState("");
  const [rcFrom, setRcFrom] = useState("");
  const [rcTo, setRcTo] = useState("");

  const q = useQuery({
    queryKey: ["recharge-orders", paging.page, paging.size, keyword, rcStatus, rcFrom, rcTo],
    queryFn: () => api.listRechargeOrders({
      page: paging.page, size: paging.size, keyword,
      status: rcStatus || undefined, from: rcFrom || undefined, to: rcTo || undefined,
    }),
    placeholderData: keepPreviousData,
  });

  const cols: Column<RechargeOrder>[] = [
    { header: "充值单号", cell: (r) => <span className="txt-strong tabular-nums">{r.rechargeNo}</span> },
    { header: "用户", cell: (r) => <span>{r.nickname} <span className="text-muted-foreground tabular-nums">{r.userNo}</span></span> },
    { header: "套餐", cell: (r) => r.packageNo ? <span className="tabular-nums">{r.packageNo}</span> : <Badge tone="outline">自定义金额</Badge> },
    { header: "实付", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.payAmount, r.currency)}</span> },
    { header: "赠送", className: "text-right", cell: (r) => <span className="tabular-nums text-muted-foreground">{money(r.giftAmount, r.currency)}</span> },
    { header: "到账", className: "text-right", cell: (r) => <span className="txt-strong tabular-nums">{money(r.creditAmount, r.currency)}</span> },
    // 渠道码与「销售管理 › 支付渠道」同一套 channelCode
    { header: "支付渠道", cell: (r) => <Badge tone="outline">{r.channelCode}</Badge> },
    { header: "状态", cell: (r) => <StatusBadge map={RECHARGE_STATUS} value={r.status} /> },
    { header: "支付时间", cell: (r) => <span className="text-muted-foreground">{r.paidAt ? fmtTime(r.paidAt) : "-"}</span> },
    { header: "网关流水号", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.pspTxnNo ?? "-"}</span> },
  ];

  return (
    <>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索充值单号/用户/网关流水号"
        onExport={() => exportCsv<RechargeOrder>("充值订单", [
          { header: "充值单号", value: (r) => r.rechargeNo },
          { header: "用户号", value: (r) => r.userNo },
          { header: "昵称", value: (r) => r.nickname },
          { header: "套餐", value: (r) => r.packageNo ?? "自定义金额" },
          { header: "实付", value: (r) => r.payAmount },
          { header: "赠送", value: (r) => r.giftAmount },
          { header: "到账", value: (r) => r.creditAmount },
          { header: "币种", value: (r) => r.currency },
          { header: "支付渠道", value: (r) => r.channelCode },
          { header: "状态", value: (r) => RECHARGE_STATUS[r.status].label },
          { header: "下单时间", value: (r) => r.createdAt },
          { header: "支付时间", value: (r) => r.paidAt },
          { header: "网关流水号", value: (r) => r.pspTxnNo },
        ], q.data?.list ?? [])}
      >
        <FilterSelect value={rcStatus} onChange={(v) => { setRcStatus(v); paging.reset(); }} allLabel="全部状态" options={RECHARGE_STATUS} />
        {/* 日期范围按下单时间：待支付/失败单没有支付时间，用支付时间会把它们全筛掉 */}
        <DateInput className="w-40" aria-label="下单时间起" value={rcFrom} onChange={(e) => { setRcFrom(e.target.value); paging.reset(); }} />
        <span className="text-muted-foreground">~</span>
        <DateInput className="w-40" aria-label="下单时间止" value={rcTo} onChange={(e) => { setRcTo(e.target.value); paging.reset(); }} />
      </Toolbar>
      <DataTable
        rowKey={(r: RechargeOrder) => r.rechargeNo}
        columns={cols}
        rows={q.data?.list}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无充值订单 —— 该筛选条件下没有记录，或用户尚未使用钱包充值"
      />
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
    </>
  );
}
