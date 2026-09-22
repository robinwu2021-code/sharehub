"use client";

// 运营管理 › 场站管理 › 站点管理（清单 OM-S2，对标简电「站场管理」的 新增 / 编辑 / 统计）
//
// 与旧入口（站点与点位 › 站点管理）读写同一份数据，旧页面不动。
// 点位在**站点详情的「点位」页签**里维护，不单独占菜单（清单 §1）。
//
// 后端就绪度（lib/backend-ready）：列表 / 新增 / 编辑 / 归档已有，可上线；
// 「暂停营业 / 恢复营业」「统计」依赖尚未实现的接口，真实后端模式下不渲染这些入口，
// 而不是点了再报错。
import { Suspense, useMemo, useState } from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { BarChart3, Pause, Pencil, Play } from "lucide-react";
import { api } from "@/lib/api";
import type { Site } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { featureReady } from "@/lib/backend-ready";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { SiteDetailDrawer } from "@/components/operation/site-detail";

const SIZE = 20;

const SITE_STATUS: StatusMap<Site["status"]> = {
  ACTIVE: { label: "营业中", tone: "success" },
  PAUSED: { label: "暂停营业", tone: "warning" },
};
const SCENES = ["商场", "机场", "餐饮", "地铁", "写字楼", "酒店", "医院", "其他"];

const PAUSE_FIELDS: FieldDef[] = [
  { key: "reason", label: "暂停原因", type: "textarea", rows: 2, required: true, maxLength: 100,
    placeholder: "如：商场装修，预计 10 月复业",
    help: "暂停后 C 端附近列表不再显示该站点，站内机柜不允许新借；已经借出的仍可正常归还。原因会记入操作日志" },
];

const FIELDS: FieldDef[] = [
  { key: "name", label: "站点名称", required: true, maxLength: 128, section: "基本信息" },
  { key: "nameAr", label: "站点名称（العربية）", maxLength: 128, section: "基本信息", help: "不填时阿语界面显示中文名" },
  { key: "venueName", label: "场地方", required: true, maxLength: 128, section: "基本信息", help: "分成按场地方结算，必须填写" },
  { key: "sceneType", label: "场景类型", type: "select", required: true, section: "基本信息", options: SCENES.map((s) => ({ value: s, label: s })) },
  { key: "regionName", label: "区域", required: true, section: "位置", help: "数据权限按区域收敛" },
  { key: "address", label: "地址", required: true, maxLength: 256, section: "位置" },
  { key: "lat", label: "纬度", type: "number", min: -90, max: 90, section: "位置", help: "选填。地图撒点用；留空不影响其它功能" },
  { key: "lng", label: "经度", type: "number", min: -180, max: 180, section: "位置" },
  { key: "openHours", label: "营业时间", section: "营业", placeholder: "10:00-22:00", help: "多段用逗号分隔，如 10:00-14:00,17:00-22:00；24 小时填 00:00-24:00" },
];

function SitesInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav } = useI18n();
  const { confirm, dialog } = useConfirm();
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const canWrite = allow("location:poi:create") || allow("location:poi:update");
  const canPause = featureReady("sites.pause");
  const canStats = featureReady("sites.stats");

  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [scene, setScene] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Partial<Site> | null>(null);
  const [editing, setEditing] = useState<Site | undefined>();
  const [pauseTarget, setPauseTarget] = useState<Site | null>(null);
  const [pauseForm, setPauseForm] = useState<Record<string, unknown>>({});

  // 详情写进 URL（?no=），刷新与分享不丢
  const detailNo = sp.get("no");
  const openDetail = (siteNo: string, tab?: string) => {
    const q = new URLSearchParams(sp.toString());
    q.set("no", siteNo);
    if (tab) q.set("tab", tab); else q.delete("tab");
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
  };
  const closeDetail = () => {
    const q = new URLSearchParams(sp.toString());
    q.delete("no"); q.delete("tab");
    router.replace(q.size ? `${pathname}?${q.toString()}` : pathname, { scroll: false });
  };

  const q = useQuery({
    queryKey: ["op", "sites", page, keyword, status, scene, showArchived],
    queryFn: () => api.listSites({ page, size: SIZE, keyword, status, showArchived }),
    placeholderData: keepPreviousData,
  });
  // 场景筛选后端列表参数不支持，前端按当前页过滤并在页面上说明
  const rows = useMemo(
    () => (q.data?.list ?? []).filter((s) => !scene || s.sceneType === scene),
    [q.data, scene],
  );
  // 点位数 / 机柜数按**实时关系聚合**，不读 Site 上的计数字段：
  // 那两个字段是种子/冗余值，与实际关系对不上（db-design §1.4「计数不是列，是聚合」），
  // 页面若读它，就会出现「列表说有 3 台机柜，待关注说一台都没有」这种自相矛盾。
  const relQ = useQuery({
    queryKey: ["op", "site-relations"],
    queryFn: async () => ({
      points: (await api.listLocations({ page: 1, size: 500 })).list,
      cabinets: (await api.listCabinets({ page: 1, size: 500 })).list,
    }),
  });
  const countsOf = (siteNo: string) => {
    const pts = (relQ.data?.points ?? []).filter((p) => p.siteNo === siteNo);
    const cabs = (relQ.data?.cabinets ?? []).filter(
      (c) => c.siteNo === siteNo || pts.some((p) => p.locationNo === c.locationNo),
    );
    return { points: pts.length, cabinets: cabs.length };
  };

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "sites"] });

  const save = useMutation({
    mutationFn: (v: Partial<Site>) => api.saveSite(v as Partial<Site> & { siteNo?: string }),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveSite(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveSite(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });
  const pause = useMutation({
    mutationFn: (v: { siteNo: string; reason: string }) => api.pauseSite(v.siteNo, v.reason),
    onSuccess: () => { refresh(); notify.success("已暂停营业"); setPauseTarget(null); },
  });
  const resume = useMutation({
    mutationFn: (siteNo: string) => api.resumeSite(siteNo),
    onSuccess: () => { refresh(); notify.success("已恢复营业"); },
  });

  const openNew = () => { setEditing(undefined); setForm({ sceneType: "商场", status: "ACTIVE" }); };
  const openEdit = (s: Site) => { setEditing(s); setForm({ ...s }); };
  const submit = () => {
    if (!form) return;
    if (!(form.name ?? "").trim()) { notify.error("请填写站点名称"); return; }
    if (!(form.venueName ?? "").trim()) { notify.error("请填写场地方——分成按场地方结算"); return; }
    save.mutate({ ...form, lat: Number(form.lat ?? 0), lng: Number(form.lng ?? 0) });
  };

  // 暂停要填原因，而 confirm 只能确认不能收集输入，故用一个单字段抽屉
  const submitPause = () => {
    if (!pauseTarget) return;
    const reason = String(pauseForm.reason ?? "").trim();
    if (!reason) { notify.error("请填写暂停原因"); return; }
    pause.mutate({ siteNo: pauseTarget.siteNo, reason });
  };

  const cols: Column<Site>[] = [
    { header: "站点", cell: (s) => (
      <button className="min-w-0 text-left hover:underline" onClick={() => openDetail(s.siteNo)}>
        <div className="truncate">{s.name}</div>
        <div className="truncate txt-caption text-muted-foreground">{s.siteNo} · {s.venueName}</div>
      </button>
    ) },
    { header: "区域 · 场景", className: "whitespace-nowrap", cell: (s) => <span className="txt-caption">{s.regionName} · {s.sceneType}</span> },
    { header: "归属", className: "whitespace-nowrap", cell: (s) => s.agentNo ?? <span className="text-muted-foreground">平台直营</span> },
    { header: "地址", cell: (s) => <span className="line-clamp-2 txt-caption text-muted-foreground">{s.address}</span> },
    { header: "点位 / 机柜", className: "whitespace-nowrap text-right", cell: (s) => {
      if (relQ.isLoading) return <span className="text-muted-foreground">…</span>;
      const c = countsOf(s.siteNo);
      return <span>{c.points} / {c.cabinets}</span>;
    } },
    { header: "状态", className: "whitespace-nowrap", cell: (s) => <StatusBadge map={SITE_STATUS} value={s.status} /> },
    {
      header: "操作",
      cell: (s) => (
        <div className="flex w-max items-center gap-2">
          {canStats && (
            <Button size="sm" variant="outline" onClick={() => openDetail(s.siteNo, "stats")}>
              <BarChart3 className="size-4" /> 统计
            </Button>
          )}
          <ArchiveActions
            archived={!!s.archivedAt}
            canWrite={canWrite}
            onArchive={async () => {
              const n = countsOf(s.siteNo).cabinets;
              if (n > 0) { notify.error(`站点下还有 ${n} 台机柜，请先迁出或归档`); return; }
              if (await confirm(archiveConfirm("站点", s.name, s.siteNo))) archive.mutate(s.siteNo);
            }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("站点", s.name))) unarchive.mutate(s.siteNo); }}
            actions={
              <>
                <Button size="sm" variant="outline" onClick={() => openEdit(s)}><Pencil className="size-4" /> 编辑</Button>
                {canPause && (s.status === "ACTIVE"
                  ? <Button size="sm" variant="outline" onClick={() => { setPauseTarget(s); setPauseForm({}); }}><Pause className="size-4" /> 暂停营业</Button>
                  : <Button size="sm" variant="outline" onClick={() => resume.mutate(s.siteNo)}><Play className="size-4" /> 恢复营业</Button>)}
              </>
            }
          />
        </div>
      ),
    },
  ];

  const filtered = !!(keyword || status || scene);

  return (
    <div>
      <PageTitle title={tNav("站点管理")} desc="站点的新增、编辑与营业状态；点位在站点详情里维护" />
      {!canWrite && <ReadOnlyNotice what="站点维护" perm="location:poi:create / location:poi:update" note="不能新增、编辑、暂停营业或归档" className="mb-3" />}
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder="搜索站点名 / 编号 / 地址"
        onAdd={openNew}
        addLabel="新增站点"
        canAdd={canWrite}
      >
        <FilterSelect aria-label="状态" value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={SITE_STATUS} allLabel="全部状态" />
        <FilterSelect aria-label="场景" value={scene} onChange={(v) => { setScene(v); setPage(1); }} options={SCENES.map((s) => ({ value: s, label: s }))} allLabel="全部场景" />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
      </Toolbar>
      <DataTable
        rowKey={(s: Site) => s.siteNo}
        columns={cols}
        rows={q.isLoading ? undefined : rows}
        loading={q.isLoading}
        rowClassName={archivedRowClass}
        empty={filtered
          ? "没有符合筛选条件的站点。场景筛选只作用于当前页，翻页后需要重新筛选。"
          : "还没有站点。站点是点位和机柜的归属单位，也是分成与统计的口径，先新增站点再投放设备。"}
      />
      <Pagination page={page} size={SIZE} total={q.data?.total ?? 0} onPage={setPage} />

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增站点"
        titleEdit={`编辑站点 ${editing?.siteNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<Site>)}
        onSubmit={submit}
        submitting={save.isPending}
        width="w-[520px]"
      />

      <FormDrawer
        open={!!pauseTarget}
        onOpenChange={(o) => !o && setPauseTarget(null)}
        titleNew=""
        titleEdit={`暂停营业：${pauseTarget?.name ?? ""}`}
        isEdit
        fields={PAUSE_FIELDS}
        value={pauseForm}
        onChange={setPauseForm}
        onSubmit={submitPause}
        submitting={pause.isPending}
      />

      <SiteDetailDrawer
        siteNo={detailNo}
        tab={sp.get("tab")}
        onTab={(t) => detailNo && openDetail(detailNo, t)}
        onClose={closeDetail}
      />
      {dialog}
    </div>
  );
}

export default function SitesPage() {
  // 读 useSearchParams 的组件在静态导出下必须包 Suspense
  return <Suspense fallback={null}><SitesInner /></Suspense>;
}
