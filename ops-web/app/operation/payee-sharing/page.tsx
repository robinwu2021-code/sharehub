"use client";

// 运营管理 › 场站管理 › 分成方分成（清单 OM-S6，对标简电「商户分成」）
// 按分成方看：这家场地方 / 代理商拿了哪些站点、比例区间、近 30 日分了多少钱。
// 与「站点分成」是同一份数据的另一个方向，不另存（同样只读，原因见 D2）。
import { useState } from "react";
import Link from "next/link";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayeeSharingRow } from "@/lib/types";
import { useI18n } from "@/lib/i18n";
import { useCan } from "@/lib/hooks/use-can";
import { money } from "@/lib/utils";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { type Column } from "@/components/ui/data-table";
import { PagedTable } from "@/components/ui/paged-table";
import { usePaging } from "@/lib/hooks/use-paging";
import { Notice } from "@/components/ui/notice";

const TABS = [
  { key: "", label: "全部" },
  { key: "VENUE", label: "场地方" },
  { key: "AGENT", label: "代理商" },
];
const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

export default function PayeeSharingPage() {
  const { tNav } = useI18n();
  const allow = useCan();
  const canSeeAmount = allow("finance:share_record:read");
  const paging = usePaging();
  const [payeeType, setPayeeType] = useState("");
  const [keyword, setKeyword] = useState("");

  const q = useQuery({
    queryKey: ["op", "payee-sharing", paging.page, paging.size, payeeType, keyword],
    queryFn: () => api.listPayeeSharing({ page: paging.page, size: paging.size, payeeType, keyword }),
    placeholderData: keepPreviousData,
  });
  const rows = q.data?.list ?? [];
  const noAmount = rows.length > 0 && rows.every((r) => r.amount30d === 0);

  const cols: Column<PayeeSharingRow>[] = [
    { header: "分成方", cell: (r) => (
      <div className="min-w-0">
        <div className="truncate">{r.payeeName}</div>
        <div className="truncate txt-caption text-muted-foreground">{r.payeeType === "VENUE" ? "场地方" : "代理商"}</div>
      </div>
    ) },
    { header: "分成站点", className: "whitespace-nowrap text-right", cell: (r) => `${r.siteCount} 个` },
    { header: "比例区间", className: "whitespace-nowrap text-right", cell: (r) => (
      <span className="tabular-nums">{r.minRate === r.maxRate ? pct(r.minRate) : `${pct(r.minRate)} ～ ${pct(r.maxRate)}`}</span>
    ) },
    {
      header: "近 30 日分成",
      className: "whitespace-nowrap text-right",
      cell: (r) => canSeeAmount
        ? <span className="tabular-nums">{money(r.amount30d, r.currency)}</span>
        : <span className="text-muted-foreground">无权查看</span>,
    },
    { header: "明细", className: "whitespace-nowrap", cell: (r) => (
      <Link href="/finance?tab=records" className="txt-caption text-primary hover:underline">分润明细</Link>
    ) },
  ];

  return (
    <div>
      <PageTitle title={tNav("分成方分成")} desc="按场地方 / 代理商看分成站点、比例与金额" />
      <Notice>
        与「站点分成」是同一份数据的两个方向，同样只读（运营管理清单 D2 待定案）。
        展开一行可以看这家分到了哪些站点、各多少比例。
      </Notice>
      {noAmount && canSeeAmount && (
        <Notice>
          近 30 日分成金额都是 0：分润明细目前只有演示数据，没有从订单生成分润的真实链路
          （已记入后端待办）。链路补齐后这一列会自动有值。
        </Notice>
      )}
      <Tabs tabs={TABS} value={payeeType} onChange={(v) => { setPayeeType(v); paging.reset(); }} />
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索分成方名称" />
      <PagedTable
        query={q}
        paging={paging}
        rowKey={(r: PayeeSharingRow) => `${r.payeeType}:${r.payeeName}`}
        columns={cols}
        expandable={(r) => (
          <div>
            <div className="mb-2 txt-caption text-muted-foreground">{r.payeeName} 分成的站点（比例从高到低）</div>
            <table className="w-full txt-body">
              <tbody>
                {r.sites.map((s) => (
                  <tr key={s.siteNo}>
                    <td className="py-1">
                      <Link href={`/operation/sites?no=${s.siteNo}`} className="hover:underline">{s.siteName}</Link>
                      <span className="ms-2 txt-caption text-muted-foreground">{s.siteNo}</span>
                    </td>
                    <td className="py-1 text-end tabular-nums">{pct(s.rate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        empty={keyword || payeeType ? "没有符合条件的分成方。" : "还没有任何分成方。站点要先签进场合同或配分润规则，才会出现在这里。"}
      />
    </div>
  );
}
