"use client";

// 运营管理 › 基础管理 › 品牌管理（链条方案 B1）
//
// 品牌是运营方对 C 端呈现的**经营身份**：名称、Logo、客服电话、协议。
// 两条边界（领域模型 §五，2026-09-23 定）：
//  ① **归运营方，代理商不得拥有** —— 代理商用运营方的品牌，C 端无感知，所以这里没有「归属代理」；
//  ② **呈现层不是隔离层** —— 设备网络与用户账户全平台共享，异地归还跨品牌照常。
//     做成隔离层等于把 ADR-026 刚砍掉的租户换个名字建回来。
//
// 站点侧是一列 brandNo（一站一品牌硬约束），在站点表单里选，不在这里挂站点。
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import type { Brand } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { PagedTable } from "@/components/ui/paged-table";
import { usePaging } from "@/lib/hooks/use-paging";
import { Toolbar } from "@/components/ui/toolbar";
import { type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";

const BRAND_STATUS: StatusMap<Brand["status"]> = {
  ENABLED: { label: "启用", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};

/**
 * @form POST /api/platform/brands
 * @form POST /api/platform/brands/{brandNo}
 */
const FIELDS: FieldDef[] = [
  { key: "name", label: "品牌名称", required: true, maxLength: 64, section: "基本信息", placeholder: "ShareHub",
    help: "C 端 App 上显示的名字；同名品牌会让站点表单的下拉出现两个一样的选项，故不允许重名" },
  { key: "nameEn", label: "品牌名称（English）", maxLength: 96, section: "基本信息" },
  { key: "nameAr", label: "品牌名称（العربية）", maxLength: 96, section: "基本信息" },
  { key: "status", label: "状态", type: "select", required: true, section: "基本信息",
    options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }],
    help: "停用后不能再被新站点选择；已经挂着它的站点不受影响" },
  { key: "logoUrl", label: "Logo 地址", maxLength: 256, section: "C 端呈现", placeholder: "https://…" },
  { key: "supportPhone", label: "客服电话", maxLength: 32, section: "C 端呈现", placeholder: "+9714…",
    help: "C 端「联系客服」拨这个号——按品牌分，不是全平台一个号" },
  { key: "marketCode", label: "归属市场", maxLength: 16, section: "C 端呈现", placeholder: "AE",
    help: "⚠️ 本期只登记不校验：区域→市场→币种那条链还没做，填了也推不出别的东西" },
];

export default function BrandsPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav } = useI18n();
  const { confirm, dialog } = useConfirm();
  const canWrite = allow("system:brand:update");

  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Partial<Brand> | null>(null);
  const [editing, setEditing] = useState<Brand | undefined>();

  const q = useQuery({
    queryKey: ["op", "brands", paging.page, paging.size, keyword, status, showArchived],
    queryFn: () => api.listBrands({ page: paging.page, size: paging.size, keyword, status, showArchived }),
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "brands"] });
  const save = useMutation({
    mutationFn: (v: Partial<Brand>) => api.saveBrand(v as Partial<Brand> & { brandNo?: string }),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveBrand(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveBrand(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const openNew = () => { setEditing(undefined); setForm({ status: "ENABLED", nameEn: "", nameAr: "", logoUrl: "", supportPhone: "" }); };
  const openEdit = (b: Brand) => { setEditing(b); setForm({ ...b }); };

  const onExport = () => exportCsv<Brand>("品牌", [
    { header: "品牌号", value: (b) => b.brandNo },
    { header: "名称", value: (b) => b.name },
    { header: "名称（EN）", value: (b) => b.nameEn },
    { header: "客服电话", value: (b) => b.supportPhone },
    { header: "归属市场", value: (b) => b.marketCode ?? "" },
    { header: "状态", value: (b) => (b.status === "ENABLED" ? "启用" : "停用") },
  ], q.data?.list ?? []);

  const cols: Column<Brand>[] = [
    { header: "品牌", cell: (b) => (
      <div className="min-w-0">
        <div className="truncate">{b.name}</div>
        <div className="truncate txt-caption text-muted-foreground">{b.brandNo}{b.nameEn ? ` · ${b.nameEn}` : ""}</div>
      </div>
    ) },
    { header: "客服电话", className: "whitespace-nowrap", cell: (b) => b.supportPhone || <span className="text-muted-foreground">未设</span> },
    { header: "归属市场", className: "whitespace-nowrap", cell: (b) => b.marketCode || <span className="text-muted-foreground">不限</span> },
    { header: "状态", className: "whitespace-nowrap", cell: (b) => <StatusBadge map={BRAND_STATUS} value={b.status} /> },
    {
      header: "操作",
      cell: (b) => (
        <div className="flex w-max items-center gap-2">
          <ArchiveActions
            archived={!!b.archivedAt}
            canWrite={canWrite}
            onArchive={async () => { if (await confirm(archiveConfirm("品牌", b.name))) archive.mutate(b.brandNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("品牌", b.name))) unarchive.mutate(b.brandNo); }}
            actions={<Button size="sm" variant="outline" onClick={() => openEdit(b)}><Pencil className="size-4" /> 编辑</Button>}
          />
        </div>
      ),
    },
  ];

  const filtered = !!(keyword || status);

  return (
    <div>
      <PageTitle title={tNav("品牌管理")} desc="运营方对 C 端呈现的经营身份；站点在「站点管理」里选用哪个品牌" />
      {!canWrite && <ReadOnlyNotice what="品牌维护" perm="system:brand:update" note="不能新增、编辑或归档" className="mb-3" />}
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索品牌名 / 编号"
        onExport={q.data?.list?.length ? onExport : undefined}
        onAdd={openNew}
        addLabel="新增品牌"
        canAdd={canWrite}
      >
        <FilterSelect aria-label="状态" value={status} onChange={(v) => { setStatus(v); paging.reset(); }} options={BRAND_STATUS} allLabel="全部状态" />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
      </Toolbar>
      <PagedTable
        query={q}
        paging={paging}
        rowKey={(b: Brand) => b.brandNo}
        columns={cols}
        rowClassName={archivedRowClass}
        empty={filtered
          ? "没有符合筛选条件的品牌。"
          : "还没有品牌。站点必须挂一个品牌才能建，所以这里至少要有一条。"}
      />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增品牌"
        titleEdit={`编辑品牌 ${editing?.brandNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<Brand>)}
        onSubmit={() => form && save.mutate(form)}
        submitting={save.isPending}
      />
      {dialog}
    </div>
  );
}
