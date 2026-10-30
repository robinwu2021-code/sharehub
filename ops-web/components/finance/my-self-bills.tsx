"use client";

// 商家门户「我的自开票」（C10 后续，2026-10-29）：平台代本商家开出的分成税务发票。
// 放在「提现记录」下面而不是另开菜单 —— 一张自开票对应一笔到账的提现，商家对账时两样要并排看。
import { useMutation, useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { usePaging } from "@/lib/hooks/use-paging";
import { money, fmtTime } from "@/lib/utils";
import type { SelfBill } from "@/lib/types";

export function MySelfBills() {
  const paging = usePaging();
  const q = useQuery({
    queryKey: ["my-self-bills", paging.page, paging.size],
    queryFn: () => api.listMySelfBills({ page: paging.page, size: paging.size }),
    placeholderData: keepPreviousData,
  });
  const pdf = useMutation({ mutationFn: (no: string) => api.downloadMySelfBillPdf(no) });
  const cols: Column<SelfBill>[] = [
    { header: "单据号", cell: (b) => <span className="txt-strong tabular-nums">{b.billNo}</span> },
    { header: "对应提现", cell: (b) => <span className="tabular-nums text-muted-foreground">{b.withdrawNo}</span> },
    { header: "含税总额", className: "text-right", cell: (b) => <span className="tabular-nums">{money(b.grossAmount, b.currency)}</span> },
    { header: "其中税额", className: "text-right", cell: (b) => <span className="tabular-nums text-muted-foreground">{money(b.taxAmount, b.currency)}（{b.taxRatePercent}%）</span> },
    { header: "开出时间", cell: (b) => <span className="tabular-nums text-muted-foreground">{fmtTime(b.issuedAt)}</span> },
    { header: "操作", cell: (b) => <Button size="sm" variant="outline" disabled={pdf.isPending} onClick={() => pdf.mutate(b.billNo)}>下载 PDF</Button> },
  ];
  return (
    <section className="mt-8">
      <h2 className="txt-title mb-3">我的自开票</h2>
      <PagedTable rowKey={(b: SelfBill) => b.billNo} columns={cols} query={q} paging={paging}
        empty="暂无自开票——平台开启自开票、且商家资料里填了 VAT 税号后，每笔分成到账会自动出一张，可在这里下载" />
    </section>
  );
}
