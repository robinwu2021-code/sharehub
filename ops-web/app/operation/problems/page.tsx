"use client";

// 运营管理 › 基础管理 › 问题管理（清单 OM-B4，对标简电「问题管理」）
// C 端报障 / 投诉时可选的问题类型，带三语标准答复和建议处置。
//
// 与旧入口（系统设置 › 问题管理）读写同一份数据，旧页面不动。
// 按分类分组展示（C 端也按分类分组），组内按排序号；上移 / 下移只在同分类内交换（lib/operation-rules#moveProblem）。
//
// 与清单的差异：「近 30 日被选次数」暂不做——前后端都没有 C 端选择问题类型的统计数据。
import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Eye, Pencil, Power } from "lucide-react";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import type { ProblemEntry } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { moveProblem } from "@/lib/rules/operation-rules";
import { PageTitle, ErrorState } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { SectionHeader } from "@/components/ui/section-header";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { exportCsv } from "@/lib/export-csv";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { LangPreview, pickLang, FallbackHint } from "@/components/operation/lang-preview";

const CATEGORY: Record<ProblemEntry["category"], string> = {
  RENT: "借出", RETURN: "归还", BILLING: "计费与退款", DEVICE: "设备故障", ACCOUNT: "账号", OTHER: "其他",
};
const CATEGORY_ORDER = Object.keys(CATEGORY) as ProblemEntry["category"][];
const CATEGORY_OPTIONS = CATEGORY_ORDER.map((k) => ({ value: k, label: CATEGORY[k] }));

const ACTION: StatusMap<ProblemEntry["suggestedAction"]> = {
  SELF_SERVICE: { label: "自助解答", tone: "muted" },
  TO_CS: { label: "转人工客服", tone: "outline" },
  TO_WORKORDER: { label: "自动开工单", tone: "warning" },
  TO_REFUND: { label: "发起退款申请", tone: "danger" },
};
const PROBLEM_STATUS: StatusMap<ProblemEntry["status"]> = {
  ENABLED: { label: "启用", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};

const FIELDS: FieldDef[] = [
  { key: "category", label: "分类", type: "select", required: true, section: "基本信息", options: CATEGORY_OPTIONS, help: "C 端按分类分组展示" },
  { key: "suggestedAction", label: "建议处置", type: "select", required: true, section: "基本信息",
    options: (Object.keys(ACTION) as ProblemEntry["suggestedAction"][]).map((k) => ({ value: k, label: ACTION[k].label })),
    help: "用户选了这个问题之后系统下一步怎么做。注意：「自动开工单」后端目前是占位实现，见页面顶部说明" },
  { key: "sortNo", label: "排序号", type: "number", required: true, min: 1, section: "基本信息", help: "同分类内数字小的排前面；列表里也可以直接上移 / 下移" },
  { key: "title", label: "标题（中文）", required: true, maxLength: 40, section: "标题（用户选择时看到的）", placeholder: "扫码后充电宝没弹出" },
  { key: "titleEn", label: "标题（English）", maxLength: 80, section: "标题（用户选择时看到的）", help: "不填时英文界面显示中文" },
  { key: "titleAr", label: "标题（العربية）", maxLength: 80, section: "标题（用户选择时看到的）", help: "不填时阿语界面显示中文" },
  { key: "answer", label: "标准答复（中文）", type: "textarea", rows: 3, required: true, maxLength: 300, section: "标准答复（选择后展示给用户）" },
  { key: "answerEn", label: "标准答复（English）", type: "textarea", rows: 3, maxLength: 400, section: "标准答复（选择后展示给用户）" },
  { key: "answerAr", label: "标准答复（العربية）", type: "textarea", rows: 3, maxLength: 400, section: "标准答复（选择后展示给用户）" },
];

export default function ProblemsPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav, locale } = useI18n();
  const { confirm, dialog } = useConfirm();
  const canWrite = allow("system:problem:update");

  const [keyword, setKeyword] = useState("");
  const [category, setCategory] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Partial<ProblemEntry> | null>(null);
  const [editing, setEditing] = useState<ProblemEntry | undefined>();
  const [preview, setPreview] = useState<ProblemEntry | null>(null);

  // 问题类型是几十条量级的字典，一次取全、按分类分组展示，不分页
  const q = useQuery({
    queryKey: ["op", "problems", keyword, category, status, showArchived],
    queryFn: () => api.listProblems({ page: 1, size: UNPAGED_SIZE, keyword, category, status, showArchived }),
  });
  const rows = useMemo(() => q.data?.list ?? [], [q.data]);
  const groups = useMemo(() => CATEGORY_ORDER
    .map((c) => ({ category: c, rows: rows.filter((r) => r.category === c).sort((a, b) => a.sortNo - b.sortNo) }))
    .filter((g) => g.rows.length > 0), [rows]);
  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "problems"] });

  const save = useMutation({
    mutationFn: (v: Partial<ProblemEntry>) => api.saveProblem(v),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  // 上移 / 下移：两条依次保存；中途失败时刷新列表，以服务端为准
  const move = useMutation({
    mutationFn: async (pair: ProblemEntry[]) => { for (const p of pair) await api.saveProblem({ problemNo: p.problemNo, sortNo: p.sortNo }); },
    onSettled: refresh,
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveProblem(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveProblem(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const openNew = () => {
    const nextSort = Math.max(0, ...rows.filter((r) => r.category === "RENT").map((r) => r.sortNo)) + 1;
    setEditing(undefined);
    setForm({ category: "RENT", suggestedAction: "TO_CS", sortNo: nextSort, status: "ENABLED" });
  };
  const openEdit = (p: ProblemEntry) => { setEditing(p); setForm({ ...p }); };
  const submit = () => { if (form) save.mutate(form); };

  const doMove = (p: ProblemEntry, dir: "up" | "down") => {
    const pair = moveProblem(rows, p.problemNo, dir);
    if (pair.length) move.mutate(pair);
  };

  const toggleStatus = async (p: ProblemEntry) => {
    const disabling = p.status === "ENABLED";
    const ok = await confirm({
      title: `${disabling ? "停用" : "启用"}问题「${p.title}」`,
      desc: disabling ? "停用后用户在 C 端报障时不再看到这个选项；已经提交的报障记录不受影响。" : "启用后用户在 C 端报障时可以选择这个问题。",
      danger: disabling,
      confirmText: disabling ? "停用" : "启用",
    });
    if (ok) save.mutate({ problemNo: p.problemNo, status: disabling ? "DISABLED" : "ENABLED" });
  };

  const titleOf = (p: ProblemEntry) => pickLang({ zh: p.title, en: p.titleEn, ar: p.titleAr }, locale).text;

  const colsFor = (groupRows: ProblemEntry[]): Column<ProblemEntry>[] => [
    { header: "排序", className: "w-16 whitespace-nowrap", cell: (p) => p.sortNo },
    { header: "问题", cell: (p) => (
      <div className="min-w-0">
        <div className="truncate">{titleOf(p)}</div>
        <div className="truncate txt-caption text-muted-foreground">{p.problemNo}</div>
      </div>
    ) },
    { header: "建议处置", className: "whitespace-nowrap", cell: (p) => <StatusBadge map={ACTION} value={p.suggestedAction} /> },
    { header: "状态", className: "whitespace-nowrap", cell: (p) => <StatusBadge map={PROBLEM_STATUS} value={p.status} /> },
    {
      header: "操作",
      cell: (p) => {
        const live = groupRows.filter((r) => !r.archivedAt);
        const idx = live.findIndex((r) => r.problemNo === p.problemNo);
        return (
          <div className="flex w-max items-center gap-2">
            <Button size="sm" variant="outline" onClick={() => setPreview(p)}><Eye className="size-4" /> 预览</Button>
            <ArchiveActions
              archived={!!p.archivedAt}
              canWrite={canWrite}
              onArchive={async () => { if (await confirm(archiveConfirm("问题", p.title))) archive.mutate(p.problemNo); }}
              onUnarchive={async () => { if (await confirm(unarchiveConfirm("问题", p.title))) unarchive.mutate(p.problemNo); }}
              actions={
                <>
                  <Button size="sm" variant="outline" aria-label="上移" title="上移" disabled={idx <= 0 || move.isPending} onClick={() => doMove(p, "up")}><ArrowUp className="size-4" /></Button>
                  <Button size="sm" variant="outline" aria-label="下移" title="下移" disabled={idx < 0 || idx >= live.length - 1 || move.isPending} onClick={() => doMove(p, "down")}><ArrowDown className="size-4" /></Button>
                  <Button size="sm" variant="outline" onClick={() => openEdit(p)}><Pencil className="size-4" /> 编辑</Button>
                  <Button size="sm" variant="outline" onClick={() => toggleStatus(p)}><Power className="size-4" /> {p.status === "ENABLED" ? "停用" : "启用"}</Button>
                </>
              }
            />
          </div>
        );
      },
    },
  ];

  const filtered = !!(keyword || category || status);

  // 导出列与表格可见列一致（TDD §10.2）。并入本页前，系统设置那份是有导出的，不能丢。
  const onExport = () => exportCsv<ProblemEntry>("问题管理", [
    { header: "问题号", value: (p) => p.problemNo },
    { header: "分类", value: (p) => CATEGORY[p.category] },
    { header: "标题（中）", value: (p) => p.title },
    { header: "建议处置", value: (p) => ACTION[p.suggestedAction].label },
    { header: "排序", value: (p) => p.sortNo },
    { header: "状态", value: (p) => (p.status === "ENABLED" ? "启用" : "停用") },
    ...(showArchived ? [{ header: "归档时间", value: (p: ProblemEntry) => p.archivedAt ?? "" }] : []),
  ], rows);

  return (
    <div>
      <PageTitle title={tNav("问题管理")} desc="C 端报障和投诉时可选的问题类型，含标准答复和建议处置" />
      <Notice>
        「自动开工单」这个处置目前后端是占位实现：用户选了这类问题后只会生成编号，不会真正创建工单。
        在后端补齐前，建议把需要派人处理的问题设为「转人工客服」。
      </Notice>
      {!canWrite && <ReadOnlyNotice what="问题类型维护" perm="system:problem:update" note="不能新增、编辑、调整顺序、停用或归档" className="mb-3" />}
      <Toolbar
        search={keyword}
        onSearch={setKeyword}
        searchPlaceholder="搜索标题 / 问题号"
        onExport={rows.length ? onExport : undefined}
        onAdd={openNew}
        addLabel="新增问题"
        canAdd={canWrite}
      >
        <FilterSelect aria-label="分类" value={category} onChange={setCategory} options={CATEGORY_OPTIONS} allLabel="全部分类" />
        <FilterSelect aria-label="状态" value={status} onChange={setStatus} options={PROBLEM_STATUS} allLabel="全部状态" />
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
      </Toolbar>

      {q.isError && <ErrorState error={q.error} onRetry={q.refetch} />}
      {!q.isLoading && !q.isError && groups.length === 0 && (
        <DataTable
          rowKey={(p: ProblemEntry) => p.problemNo}
          columns={colsFor([])}
          rows={[]}
          empty={filtered
            ? "没有符合筛选条件的问题。试试清空筛选，或打开「显示已归档」。"
            : "还没有问题类型。用户在 C 端报障时要从这里选择问题，没有选项就只能直接联系客服，请先新增常见问题。"}
        />
      )}
      {q.isLoading && <DataTable rowKey={(p: ProblemEntry) => p.problemNo} columns={colsFor([])} rows={undefined} loading />}
      {groups.map((g) => (
        <section key={g.category} className="mb-6">
          <SectionHeader title={CATEGORY[g.category]} summary={`${g.rows.length} 条`} />
          <DataTable
            rowKey={(p: ProblemEntry) => p.problemNo}
            columns={colsFor(g.rows)}
            rows={g.rows}
            rowClassName={archivedRowClass}
          />
        </section>
      ))}

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增问题"
        titleEdit={`编辑问题 ${editing?.problemNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<ProblemEntry>)}
        onSubmit={submit}
        submitting={save.isPending}
      />

      <Drawer open={!!preview} onOpenChange={(o) => !o && setPreview(null)} title="C 端预览" desc="用户报障时选择问题、选择后看到答复的样子">
        {preview && (
          <LangPreview render={(lang) => {
            const title = pickLang({ zh: preview.title, en: preview.titleEn, ar: preview.titleAr }, lang);
            const answer = pickLang({ zh: preview.answer, en: preview.answerEn, ar: preview.answerAr }, lang);
            return (
              <div className="space-y-3">
                <div className="rounded-field bg-card px-3 py-2.5 txt-body">
                  {title.text}
                  <FallbackHint show={title.fallback} />
                </div>
                <div className="rounded-field bg-card px-3 py-2.5 txt-body text-muted-foreground whitespace-pre-line">
                  {answer.text}
                  <FallbackHint show={answer.fallback} />
                </div>
              </div>
            );
          }} />
        )}
      </Drawer>
      {dialog}
    </div>
  );
}
