"use client";

// 运营管理 › 公告管理（清单 OM-N1，对标简电「公告管理」）
// C 端首页公告条的内容来源：三语、生效期、置顶。
//
// 与旧入口（营销管理 › 公告管理）读写同一份数据，旧页面不动。
// 规则（状态机、置顶上限 3 条、结束晚于开始）见 lib/operation-rules，表单与 mock 共用。
// 「待生效 / 已过期」是派生状态，只在前端按时间算，不入库。
// 时间：库存 UTC，界面按市场时区显示与输入（lib/market-time）。
//
// 与清单的差异：「适用区域」暂不做——前端公告类型没有区域字段，后端接口也未返回。
import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Eye, Pencil, Pin, Send, CircleOff } from "lucide-react";
import { api } from "@/lib/api";
import type { Notice } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { noticeView, validateNotice, MAX_PINNED, type NoticeView } from "@/lib/operation-rules";
import {
  MARKET_TZ, formatMarketTime, formatOffset, tzOffsetMinutes, marketLocalToUtcIso, marketNowLocal,
} from "@/lib/market-time";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { LangPreview, pickLang, FallbackHint } from "@/components/operation/lang-preview";

const TYPE: StatusMap<Notice["type"]> = {
  SYSTEM: { label: "系统", tone: "outline" },
  PROMO: { label: "活动", tone: "success" },
  MAINTENANCE: { label: "维护", tone: "warning" },
};
const VIEW: StatusMap<NoticeView> = {
  LIVE: { label: "生效中", tone: "success" },
  SCHEDULED: { label: "待生效", tone: "outline" },
  DRAFT: { label: "草稿", tone: "muted" },
  EXPIRED: { label: "已过期", tone: "muted" },
  OFFLINE: { label: "已下线", tone: "muted" },
};
const VIEW_TABS: { key: string; label: string; views: NoticeView[] }[] = [
  { key: "active", label: "生效中 / 待生效", views: ["LIVE", "SCHEDULED"] },
  { key: "draft", label: "草稿", views: ["DRAFT"] },
  { key: "ended", label: "已下线 / 已过期", views: ["OFFLINE", "EXPIRED"] },
  { key: "all", label: "全部", views: ["LIVE", "SCHEDULED", "DRAFT", "OFFLINE", "EXPIRED"] },
];

const zone = formatOffset(tzOffsetMinutes(new Date(), MARKET_TZ));

const FIELDS: FieldDef[] = [
  { key: "type", label: "类型", type: "select", required: true, section: "基本信息",
    options: (Object.keys(TYPE) as Notice["type"][]).map((k) => ({ value: k, label: TYPE[k].label })),
    help: "维护类在 C 端用醒目样式展示" },
  { key: "pinned", label: "置顶", type: "switch", section: "基本信息", help: `同时置顶且未过期的公告最多 ${MAX_PINNED} 条` },
  { key: "startAt", label: `生效开始（${zone}）`, type: "datetime", required: true, section: "生效期", help: "按市场当地时间填写" },
  { key: "endAt", label: `生效结束（${zone}）`, type: "datetime", section: "生效期", help: "留空表示长期有效；到点后 C 端自动不再显示" },
  { key: "title", label: "标题（中文）", required: true, maxLength: 128, section: "标题" },
  { key: "titleEn", label: "标题（English）", maxLength: 128, section: "标题", help: "不填时英文界面显示中文" },
  { key: "titleAr", label: "标题（العربية）", maxLength: 128, section: "标题", help: "不填时阿语界面显示中文" },
  { key: "content", label: "内容（中文）", type: "textarea", rows: 4, required: true, maxLength: 2000, section: "内容" },
  { key: "contentEn", label: "内容（English）", type: "textarea", rows: 4, maxLength: 2000, section: "内容" },
  { key: "contentAr", label: "内容（العربية）", type: "textarea", rows: 4, maxLength: 2000, section: "内容" },
];

/** 表单里的时间是市场时区的本地串；进出表单时与 UTC 互转。 */
type NoticeForm = Omit<Partial<Notice>, "startAt" | "endAt"> & { startAt?: string; endAt?: string };
const toForm = (n: Partial<Notice>): NoticeForm => ({
  ...n,
  startAt: n.startAt ? formatMarketTime(n.startAt).replace(" ", "T") : "",
  endAt: n.endAt ? formatMarketTime(n.endAt).replace(" ", "T") : "",
});
const fromForm = (f: NoticeForm): Partial<Notice> => ({
  ...f,
  startAt: f.startAt ? marketLocalToUtcIso(f.startAt) : "",
  endAt: f.endAt ? marketLocalToUtcIso(f.endAt) : "",
});

export default function NoticesPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav, locale } = useI18n();
  const { confirm, dialog } = useConfirm();
  const canWrite = allow("marketing:notice:update");

  const [tab, setTab] = useState("active");
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<NoticeForm | null>(null);
  const [editing, setEditing] = useState<Notice | undefined>();
  const [preview, setPreview] = useState<Notice | null>(null);

  // 派生状态只能在前端算，所以公告（几十条量级）一次取全，页签与类型筛选在前端做
  const q = useQuery({
    queryKey: ["op", "notices", keyword, showArchived],
    queryFn: () => api.listNotices({ page: 1, size: 500, keyword, showArchived }),
  });
  const all = useMemo(() => q.data?.list ?? [], [q.data]);
  const now = new Date();
  const views = VIEW_TABS.find((t) => t.key === tab)!.views;
  const rows = all
    .filter((n) => views.includes(noticeView(n, now)) && (!type || n.type === type))
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || (b.startAt ?? "").localeCompare(a.startAt ?? ""));
  const pinnedLive = all.filter((n) => n.pinned && n.status === "PUBLISHED" && !n.archivedAt && ["LIVE", "SCHEDULED"].includes(noticeView(n, now)));

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "notices"] });
  const save = useMutation({
    mutationFn: (v: Partial<Notice>) => api.saveNotice(v),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const transit = useMutation({
    mutationFn: (v: { noticeNo: string; status: Notice["status"] }) => api.saveNotice(v),
    onSuccess: (_r, v) => { refresh(); notify.success(v.status === "PUBLISHED" ? "已发布" : "已下线"); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveNotice(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveNotice(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const openNew = () => {
    setEditing(undefined);
    setForm({ type: "SYSTEM", pinned: false, status: "DRAFT", startAt: marketNowLocal(), endAt: "", title: "", content: "" });
  };
  const openEdit = (n: Notice) => { setEditing(n); setForm(toForm(n)); };

  const submit = async () => {
    if (!form) return;
    const v = fromForm(form);
    const errors = validateNotice(v, editing, all);
    if (errors.length) { notify.error(errors[0]); return; }
    // 已发布且在有效期内的公告，改动会立刻出现在用户手机上
    if (editing && editing.status === "PUBLISHED" && noticeView(editing) !== "EXPIRED") {
      const ok = await confirm({ title: "修改已发布的公告", desc: "保存后修改立即对 C 端用户可见。", confirmText: "保存" });
      if (!ok) return;
    }
    save.mutate(v);
  };

  const doTransit = async (n: Notice, to: Notice["status"]) => {
    const errors = validateNotice({ ...n, status: to }, n, all);
    if (errors.length) { notify.error(errors[0]); return; }
    const publishing = to === "PUBLISHED";
    const v = noticeView({ ...n, status: to });
    const ok = await confirm({
      title: `${publishing ? (n.status === "OFFLINE" ? "重新发布" : "发布") : "下线"}公告「${n.title}」`,
      desc: publishing
        ? (v === "SCHEDULED" ? `将在 ${formatMarketTime(n.startAt)}（${zone}）自动出现在 C 端首页。`
          : v === "EXPIRED" ? "注意：这条公告的生效期已经结束，发布后 C 端也不会显示。请先修改生效期。"
          : "发布后立即出现在 C 端首页。")
        : "下线后 C 端立即不再显示，可以随时重新发布。",
      danger: !publishing,
      confirmText: publishing ? "发布" : "下线",
    });
    if (ok) transit.mutate({ noticeNo: n.noticeNo, status: to });
  };

  const titleOf = (n: Notice) => pickLang({ zh: n.title, en: n.titleEn, ar: n.titleAr }, locale).text;

  const cols: Column<Notice>[] = [
    { header: "公告", cell: (n) => (
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          {n.pinned && <Pin className="size-3.5 shrink-0 text-primary" aria-label="置顶" />}
          <span className="truncate">{titleOf(n)}</span>
        </div>
        <div className="truncate txt-caption text-muted-foreground">{n.noticeNo}</div>
      </div>
    ) },
    { header: "类型", className: "whitespace-nowrap", cell: (n) => <StatusBadge map={TYPE} value={n.type} /> },
    { header: `生效期（${zone}）`, className: "whitespace-nowrap", cell: (n) => (
      <span className="txt-caption">{formatMarketTime(n.startAt)} ～ {n.endAt ? formatMarketTime(n.endAt) : "长期"}</span>
    ) },
    { header: "状态", className: "whitespace-nowrap", cell: (n) => <StatusBadge map={VIEW} value={noticeView(n, now)} /> },
    { header: "发布人", className: "whitespace-nowrap", cell: (n) => n.publishedBy || "-" },
    {
      header: "操作",
      cell: (n) => (
        <div className="flex w-max items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => setPreview(n)}><Eye className="size-4" /> 预览</Button>
          <ArchiveActions
            archived={!!n.archivedAt}
            canWrite={canWrite}
            onArchive={async () => { if (await confirm(archiveConfirm("公告", n.title))) archive.mutate(n.noticeNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("公告", n.title))) unarchive.mutate(n.noticeNo); }}
            actions={
              <>
                <Button size="sm" variant="outline" onClick={() => openEdit(n)}><Pencil className="size-4" /> 编辑</Button>
                {n.status === "PUBLISHED"
                  ? <Button size="sm" variant="outline" onClick={() => doTransit(n, "OFFLINE")}><CircleOff className="size-4" /> 下线</Button>
                  : <Button size="sm" variant="outline" onClick={() => doTransit(n, "PUBLISHED")}><Send className="size-4" /> {n.status === "OFFLINE" ? "重新发布" : "发布"}</Button>}
              </>
            }
          />
        </div>
      ),
    },
  ];

  const filtered = !!(keyword || type);
  const emptyText = filtered
    ? "没有符合筛选条件的公告。试试清空搜索和类型筛选，或切换到「全部」。"
    : tab === "active" ? "当前没有生效中或待生效的公告，C 端首页不会显示公告条。可以新建一条，或到「草稿」里发布已写好的。"
    : tab === "draft" ? "没有草稿。新建的公告会先保存为草稿，确认无误后再发布。"
    : tab === "ended" ? "没有已下线或已过期的公告。"
    : "还没有任何公告。";

  return (
    <div>
      <PageTitle title={tNav("公告管理")} desc={`C 端首页公告条的内容；当前置顶 ${pinnedLive.length} / ${MAX_PINNED} 条`} />
      {!canWrite && <ReadOnlyNotice what="公告发布与维护" perm="marketing:notice:update" note="不能新建、编辑、发布、下线或归档" className="mb-3" />}
      <Tabs tabs={VIEW_TABS} value={tab} onChange={setTab} />
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="搜索标题 / 编号 / 发布人" onAdd={openNew} addLabel="新建公告" canAdd={canWrite}>
        <FilterSelect aria-label="类型" value={type} onChange={setType} options={TYPE} allLabel="全部类型" />
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
      </Toolbar>
      <DataTable rowKey={(n: Notice) => n.noticeNo} columns={cols} rows={q.isLoading ? undefined : rows} loading={q.isLoading} rowClassName={archivedRowClass} empty={emptyText} />

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建公告（保存为草稿）"
        titleEdit={`编辑公告 ${editing?.noticeNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as NoticeForm)}
        onSubmit={submit}
        submitting={save.isPending}
        width="w-[520px]"
      />

      <Drawer open={!!preview} onOpenChange={(o) => !o && setPreview(null)} title="C 端预览" desc="首页公告条（上）与点开后的详情（下）" width="w-[480px]">
        {preview && (
          <LangPreview render={(lang) => {
            const title = pickLang({ zh: preview.title, en: preview.titleEn, ar: preview.titleAr }, lang);
            const content = pickLang({ zh: preview.content, en: preview.contentEn, ar: preview.contentAr }, lang);
            const bar = preview.type === "MAINTENANCE" ? "bg-warning-tint text-warning-ink" : "bg-card";
            return (
              <div className="space-y-3">
                <div className={`flex items-center gap-2 rounded-field px-3 py-2 txt-body ${bar}`}>
                  {preview.pinned && <Pin className="size-3.5 shrink-0" aria-hidden />}
                  <span className="truncate">{title.text}</span>
                </div>
                <FallbackHint show={title.fallback} />
                <div className="rounded-field bg-card p-3">
                  <div className="mb-1 txt-strong">{title.text}</div>
                  <div className="whitespace-pre-line txt-body text-muted-foreground">{content.text}</div>
                  <FallbackHint show={content.fallback} />
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
