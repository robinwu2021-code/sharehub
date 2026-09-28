"use client";

// 流程实例（后端 O2-10）：每个实例的状态；点进去看完整流转。顶部可按业务对象对账。
// 每个筛选控件对应 instances/query 的一个参数（processCode · statusCategory[] · open · subjectRef）。
import { NeedPerm } from "@/components/flow/need-perm";
import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { who } from "@/lib/flow/present";
import { useProcessOptions } from "@/lib/flow/options";
import type { InstanceRow, StatusCategory } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { FilterChips } from "@/components/ui/filter-chips";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { TicketStatusBadge } from "@/components/flow/badges";

const CATS: StatusCategory[] = ["NEW", "IN_PROGRESS", "WAITING", "DONE", "CANCELLED"];
const MIN = 60_000;

function FlowInstances() {
  const { t, tc, fmt } = useFlowT("instances");
  const router = useRouter();
  const processOptions = useProcessOptions().map((p) => ({ value: p.code, label: p.name }));
  const paging = usePaging();
  const [processCode, setProcessCode] = useState("");
  const [cat, setCat] = useState("");
  const [open, setOpen] = useState("");
  const [ref, setRef] = useState("");
  const [lookup, setLookup] = useState({ type: "", ref: "" });

  const filter = useMemo(() => ({
    ...(processCode ? { processCode } : {}),
    ...(cat ? { statusCategory: [cat as StatusCategory] } : {}),
    ...(open ? { open: open === "yes" } : {}),
    ...(ref.trim() ? { subjectRef: ref.trim() } : {}),
  }), [processCode, cat, open, ref]);
  const query = useQuery({
    queryKey: [...FLOW_KEY, "instances", filter, paging.page, paging.size],
    queryFn: () => flow.instances({ ...filter, page: paging.page, size: paging.size }),
  });
  const onFilter = (fn: () => void) => { fn(); paging.reset(); };
  const now = Date.now();

  const subjectHref = (type: string, r: string) => `/flow/instances/subject/?type=${encodeURIComponent(type)}&ref=${encodeURIComponent(r)}`;
  const columns: Column<InstanceRow>[] = [
    { header: t("col.no"), pk: true, cell: (r) => <Link href={`/flow/instances/detail/?no=${r.instanceNo}`} className="text-primary underline-offset-2 hover:underline">{r.instanceNo}</Link> },
    { header: t("col.process"), cell: (r) => r.process.name },
    {
      header: t("col.subject"),
      cell: (r) => r.subject ? (
        <span className="inline-flex flex-col">
          <span>{r.subject.display.title}</span>
          <Link href={subjectHref(r.subject.type, r.subject.ref)} className="txt-caption text-muted-foreground underline-offset-2 hover:underline">
            {fmt.join(`${r.subject.type}:${r.subject.ref}`, t("reconcile"))}
          </Link>
        </span>
      ) : tc("none"),
    },
    { header: t("col.status"), cell: (r) => <TicketStatusBadge t={r} /> },
    { header: t("col.createdBy"), cell: (r) => (r.createdBy ? who(r.createdBy) : tc("system")) },
    { header: t("col.createdAt"), cell: (r) => <span className="tabular-nums">{fmt.short(r.createdAtMs)}</span> },
    {
      header: t("col.closed"),
      cell: (r) => r.closedAtMs
        ? <span className="tabular-nums text-muted-foreground">{fmt.join(fmt.short(r.closedAtMs), t("took", { d: fmt.dur((r.closedAtMs - r.createdAtMs) / MIN) }))}</span>
        : <span className="tabular-nums">{t("running", { d: fmt.dur((now - r.createdAtMs) / MIN) })}</span>,
    },
  ];

  return (
    <div>
      <PageTitle title={tc("page.instances")} desc={t("desc")} />

      <Card className="mb-4 p-4">
        <form className="flex flex-wrap items-center gap-2" onSubmit={(e) => {
          e.preventDefault();
          if (lookup.type.trim() && lookup.ref.trim()) router.push(subjectHref(lookup.type.trim(), lookup.ref.trim()));
        }}>
          <span className="txt-strong me-2">{t("subject.lookup")}</span>
          <Input className="w-44" placeholder={t("subject.typePlaceholder")} value={lookup.type} aria-label={t("subject.typePlaceholder")}
            onChange={(e) => setLookup((l) => ({ ...l, type: e.target.value }))} />
          <Input className="w-52" placeholder={t("subject.refPlaceholder")} value={lookup.ref} aria-label={t("subject.refPlaceholder")}
            onChange={(e) => setLookup((l) => ({ ...l, ref: e.target.value }))} />
          <Button size="sm" variant="secondary" type="submit" disabled={!lookup.type.trim() || !lookup.ref.trim()}>{t("subject.go")}</Button>
        </form>
      </Card>

      <Toolbar search={ref} onSearch={(v) => onFilter(() => setRef(v))} searchPlaceholder={t("searchPlaceholder")}>
        <FilterSelect aria-label={t("filter.process")} value={processCode} onChange={(v) => onFilter(() => setProcessCode(v))}
          options={processOptions} allLabel={t("filter.processAll")} />
        <FilterSelect aria-label={t("filter.open")} value={open} onChange={(v) => onFilter(() => setOpen(v))}
          options={[{ value: "yes", label: t("filter.openYes") }, { value: "no", label: t("filter.openNo") }]} allLabel={t("filter.openAll")} />
        <FilterChips aria-label={t("filter.category")} value={cat} onChange={(v) => onFilter(() => setCat(v))}
          options={CATS.map((c) => ({ value: c, label: tc(`category.${c}`) }))} allLabel={t("filter.openAll")} />
      </Toolbar>
      <PagedTable query={query} paging={paging} columns={columns} rowKey={(r) => r.instanceNo}
        empty={processCode || cat || open || ref ? t("empty.filtered") : t("empty.none")} />
    </div>
  );
}

export default function FlowInstancesPage() {
  return <NeedPerm code="flow:approval:read" page="instances"><FlowInstances /></NeedPerm>;
}
