"use client";

// 商家分成自开票（V183，运营方原始需求 C10）。提现确认到账时由平台代商家（供方）开出税务发票，
// 只对档案里有 VAT 税号的商家出；开关是系统参数 finance.share.self_billing（缺省关）。
// 运营动作只有两个：下载 PDF、对一笔已到账的提现补出（开关当时关着 / 商家后补了税号 / 自动出单失败）。
import * as React from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import type { SelfBill } from "@/lib/types";

export function SelfBillPanel() {
  const qc = useQueryClient();
  const allow = useCan();
  const canIssue = allow("finance:invoice:issue");
  const paging = usePaging();
  const [keyword, setKeyword] = React.useState("");
  const [withdrawNo, setWithdrawNo] = React.useState("");
  const q = useQuery({
    queryKey: ["self-bills", paging.page, paging.size, keyword],
    queryFn: () => api.listSelfBills({ page: paging.page, size: paging.size, keyword: keyword || undefined }),
    placeholderData: keepPreviousData,
  });
  const issue = useMutation({
    mutationFn: (no: string) => api.issueSelfBill(no),
    onSuccess: (b) => {
      notify.success(`自开票 ${b.billNo} 已出（提现单 ${b.withdrawNo}）`);
      setWithdrawNo("");
      qc.invalidateQueries({ queryKey: ["self-bills"] });
    },
  });
  const pdf = useMutation({ mutationFn: (no: string) => api.downloadSelfBillPdf(no) });

  const cols: Column<SelfBill>[] = [
    { header: "单据号", cell: (b) => <span className="txt-strong tabular-nums">{b.billNo}</span> },
    { header: "商家（供方）", cell: (b) => <span>{b.supplierName ?? b.agentNo}<span className="ms-1 text-muted-foreground tabular-nums">{b.supplierTrn}</span></span> },
    { header: "提现单", cell: (b) => <span className="tabular-nums text-muted-foreground">{b.withdrawNo}</span> },
    { header: "含税总额", cell: (b) => <span className="tabular-nums">{money(b.grossAmount, b.currency)}</span> },
    { header: "税额", cell: (b) => <span className="tabular-nums text-muted-foreground">{money(b.taxAmount, b.currency)}（{b.taxRatePercent}%）</span> },
    { header: "开出时间", cell: (b) => <span className="tabular-nums text-muted-foreground">{fmtTime(b.issuedAt)}</span> },
    { header: "操作", cell: (b) => <Button size="sm" variant="outline" disabled={pdf.isPending} onClick={() => pdf.mutate(b.billNo)}>PDF</Button> },
  ];

  return (
    <>
      <Notice>
        商家分成到账时，平台代商家开出税务发票（自开票）。只对商家档案里填了 VAT 税号的商家出；付款额视为含税，按平台开票主体那一国的税率倒推税额。
        开关见系统参数「发票」组 finance.share.self_billing。
      </Notice>
      {!canIssue && <ReadOnlyNotice what="自开票补出" perm="finance:invoice:issue" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索单据号 / 提现单 / 商家">
        {canIssue && (
          <>
            <Input className="w-56" value={withdrawNo} onChange={(e) => setWithdrawNo(e.target.value)} placeholder="已到账的提现单号" aria-label="补出自开票的提现单号" />
            <Button variant="outline" disabled={!withdrawNo.trim() || issue.isPending} onClick={() => issue.mutate(withdrawNo.trim())}>补出</Button>
          </>
        )}
      </Toolbar>
      <PagedTable
        rowKey={(b: SelfBill) => b.billNo}
        columns={cols}
        query={q}
        paging={paging}
        empty="暂无自开票单据——开关关着、或到账的商家都没有 VAT 税号时不会出单；商家补录税号后可在上方按提现单号补出"
      />
    </>
  );
}
