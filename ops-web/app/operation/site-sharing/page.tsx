"use client";

// 运营管理 › 场站管理 › 站点分成（清单 OM-S5，对标简电「站场分成」）
// 按站点看：这个站点的收入分给谁、各占多少、平台留多少。
//
// ⚠️ **这一页目前是只读的聚合视图**，原因见清单 D2：
// 分润规则表按「分成方」配比例、没有站点维度；站点级比例只存在进场合同里。
// 在「以哪一份为准」定案之前，这里如实展示两个来源各自给出的值，并标出缺配置与异常，
// 编辑仍在原入口（财务 › 分润规则 / 站点与点位 › 进场合同）。定案后再开本页的编辑。
import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { SiteSharingRow, SitePayee } from "@/lib/types";
import { useI18n } from "@/lib/i18n";
import { PageTitle } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { SummaryCard } from "@/components/operation/summary-card";

const STATE: StatusMap<SiteSharingRow["state"]> = {
  OK: { label: "已配置", tone: "success" },
  MISSING: { label: "缺配置", tone: "warning" },
  INVALID: { label: "异常", tone: "danger" },
};
const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

function payeeLine(p: SitePayee) {
  return `${p.payeeName} ${pct(p.rate)}（${p.source === "CONTRACT" ? `合同 ${p.sourceNo}` : `规则 ${p.sourceNo}`}）`;
}

export default function SiteSharingPage() {
  const { tNav } = useI18n();
  const [keyword, setKeyword] = useState("");
  const [state, setState] = useState("");

  const q = useQuery({
    queryKey: ["op", "site-sharing-list", keyword, state],
    queryFn: () => api.listSiteSharing({ page: 1, size: 200, keyword, state }),
  });
  const statsQ = useQuery({ queryKey: ["op", "site-sharing-stats"], queryFn: () => api.getSiteSharingStats() });
  const stats = statsQ.data;

  const cols: Column<SiteSharingRow>[] = [
    { header: "站点", cell: (r) => (
      <Link href={`/operation/sites?no=${r.siteNo}`} className="min-w-0 hover:underline">
        <div className="truncate">{r.siteName}</div>
        <div className="truncate txt-caption text-muted-foreground">{r.siteNo} · {r.venueName}</div>
      </Link>
    ) },
    { header: "场地方", cell: (r) => {
      const v = r.payees.find((p) => p.payeeType === "VENUE");
      return v ? <span className="txt-caption">{payeeLine(v)}</span> : <span className="text-muted-foreground">—</span>;
    } },
    { header: "代理商", cell: (r) => {
      const a = r.payees.find((p) => p.payeeType === "AGENT");
      return a ? <span className="txt-caption">{payeeLine(a)}</span> : <span className="text-muted-foreground">平台直营</span>;
    } },
    { header: "平台留存", className: "whitespace-nowrap text-right", cell: (r) => (
      <span className={`tabular-nums ${r.platformRate < 0 ? "text-destructive" : ""}`}>{pct(r.platformRate)}</span>
    ) },
    { header: "合同到期", className: "whitespace-nowrap", cell: (r) => (
      <span className="txt-caption text-muted-foreground">{r.contractEndAt ? r.contractEndAt.slice(0, 10) : "无合同"}</span>
    ) },
    { header: "配置状态", className: "whitespace-nowrap", cell: (r) => (
      <div>
        <StatusBadge map={STATE} value={r.state} />
        {r.stateDetail && <div className="mt-1 max-w-56 txt-caption text-muted-foreground">{r.stateDetail}</div>}
      </div>
    ) },
    { header: "去配置", className: "whitespace-nowrap", cell: (r) => (
      <div className="flex w-max gap-2 txt-caption">
        <Link href="/finance?tab=rules" className="text-primary hover:underline">分润规则</Link>
        <Link href="/locations?tab=contracts" className="text-primary hover:underline">进场合同</Link>
      </div>
    ) },
  ];

  return (
    <div>
      <PageTitle title={tNav("站点分成")} desc="按站点看收入分给谁、各占多少、平台留多少" />
      <Notice>
        本页只读：分润规则按分成方配置、不含站点维度，站点级比例只存在进场合同里（运营管理清单 D2 待定案）。
        在「以哪一份为准」定下来之前，这里如实展示两个来源各自的值并标出问题；修改请到
        <Link href="/finance?tab=rules" className="mx-1 text-primary hover:underline">财务 › 分润规则</Link>
        或
        <Link href="/locations?tab=contracts" className="mx-1 text-primary hover:underline">站点与点位 › 进场合同</Link>。
      </Notice>

      {stats && (
        <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <SummaryCard label="站点总数" value={stats.total} />
          <SummaryCard label="已配置" value={stats.ok} />
          <SummaryCard label="缺配置" value={stats.missing} sub={stats.missing ? "收入全部留在平台" : undefined} />
          <SummaryCard label="异常" value={stats.invalid} sub={stats.invalid ? "比例超 100% 或合同过期仍营业" : undefined} />
        </div>
      )}

      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="搜索站点名 / 编号 / 场地方">
        <FilterSelect aria-label="配置状态" value={state} onChange={setState} options={STATE} allLabel="全部状态" />
      </Toolbar>
      <DataTable
        rowKey={(r: SiteSharingRow) => r.siteNo}
        columns={cols}
        rows={q.isLoading ? undefined : q.data?.list}
        loading={q.isLoading}
        empty={keyword || state ? "没有符合条件的站点。" : "还没有站点，因此没有分成配置。"}
      />
    </div>
  );
}
