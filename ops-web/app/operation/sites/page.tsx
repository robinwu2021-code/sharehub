"use client";

// 运营管理 › 场站管理 › 站点管理（清单 OM-S2，对标简电「站场管理」的 新增 / 编辑 / 统计）
//
// 2026-09-23 起是站点的**唯一**入口：旧的「站点与点位 › 站点管理」调同一组 API，
// 属于同一张表的第二个维护入口，已随菜单收敛撤销（见 docs/technical/菜单重合梳理与优化方案.md）。
// 点位在**站点详情的「点位」页签**里维护，不单独占菜单（清单 §1）。
//
// 后端就绪度（lib/backend-ready）：列表 / 新增 / 编辑 / 归档已有，可上线；
// 「暂停营业 / 恢复营业」「统计」依赖尚未实现的接口，真实后端模式下不渲染这些入口，
// 而不是点了再报错。
import { Suspense, useMemo, useState } from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { BarChart3, Pause, Pencil, Play } from "lucide-react";
import { api } from "@/lib/api";
import type { Site } from "@/lib/types";
import { SITE_TRANSITIONS } from "@/lib/types";
import { SITE_COORD_BOUNDS } from "@/lib/types/location";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { featureReady } from "@/lib/backend-ready";
import { PageTitle } from "@/components/ui/misc";
import { PagedTable } from "@/components/ui/paged-table";
import { usePaging } from "@/lib/hooks/use-paging";
import { UNPAGED_SIZE } from "@/lib/constants";
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
import { SiteDetailDrawer, SITE_STATUS, siteFixHref } from "@/components/operation/site-detail";
import { SummaryCard } from "@/components/ui/summary-card";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";


/**
 * 撤场提前通知天数：镜像后端参数 `site.withdraw.lead_days`（缺省 7）。
 * 只用来给日期框设下限、把规则写在界面上 —— 服务端才是权威，参数调了这里没跟上时以它的报错为准。
 */
const WITHDRAW_LEAD_DAYS = 7;
const earliestWithdraw = () => new Date(Date.now() + WITHDRAW_LEAD_DAYS * 86400_000).toISOString().slice(0, 10);

const SCENES = ["商场", "机场", "餐饮", "地铁", "写字楼", "酒店", "医院", "其他"];

/**
 * @form POST /api/ops/sites/{siteNo}/pause
 */
const PAUSE_FIELDS: FieldDef[] = [
  { key: "reason", label: "暂停原因", type: "textarea", rows: 2, required: true, maxLength: 100,
    placeholder: "如：商场装修，预计 10 月复业",
    help: "暂停后 C 端附近列表不再显示该站点，站内机柜不允许新借；已经借出的仍可正常归还。原因会记入操作日志" },
];

/** 营业时间：多段 `HH:MM-HH:MM`，逗号分隔。以前只在 help 里说，填错了没人拦。 */
const OPEN_HOURS_RE = "^([01]\\d|2[0-4]):[0-5]\\d-([01]\\d|2[0-4]):[0-5]\\d(,([01]\\d|2[0-4]):[0-5]\\d-([01]\\d|2[0-4]):[0-5]\\d)*$";

/**
 * 字段定义依赖「场地方 / 区域」两份下拉数据，故做成函数而非常量。
 *
 * ⚠️ 2026-09-23 之前这两个字段是**自由文本**，各踩过一次：
 *  - 场地方存名字不存编号 → 分成链在「站点→场地方」这一跳断掉，同名场地方会把钱分错家；
 *  - 区域存名字 → 数据权限按区域收敛，打错一个字**静默失效**（不报错，是看不到/看到不该看的）。
 * 所以现在一律「选出来」，存编号，名字只作展示冗余。
 */
/**
 * @form POST /api/ops/sites
 * @form POST /api/ops/sites/{siteNo}
 */
function fieldsFor(
  venues: { value: string; label: string }[],
  regions: { value: string; label: string }[],
  brands: { value: string; label: string }[],
): FieldDef[] {
  return [
    { key: "name", label: "站点名称", required: true, maxLength: 128, section: "基本信息" },
    { key: "nameAr", label: "站点名称（العربية）", maxLength: 128, section: "基本信息", help: "不填时阿语界面显示中文名" },
    { key: "venueNo", label: "场地方", type: "select", required: true, section: "基本信息",
      options: [{ value: "", label: "请选择场地方" }, ...venues],
      help: "分成按场地方结算；这里选的是档案里的场地方，不是手打名字" },
    { key: "brandNo", label: "品牌", type: "select", required: true, section: "基本信息",
      options: [{ value: "", label: "请选择品牌" }, ...brands],
      help: "站点以哪个品牌对 C 端呈现。一站只能挂一个品牌 —— 分成与坪效都按站点统计，挂两个品牌会让「这笔钱算哪个品牌的」没有答案" },
    { key: "sceneType", label: "场景类型", type: "select", required: true, section: "基本信息", options: SCENES.map((s) => ({ value: s, label: s })) },
    { key: "regionId", label: "区域", type: "select", required: true, section: "位置",
      options: [{ value: "", label: "请选择区域" }, ...regions],
      help: "数据权限按区域收敛，必须选字典里的区域" },
    { key: "address", label: "地址", type: "address", latKey: "lat", lngKey: "lng",
      required: true, maxLength: 256, section: "位置",
      placeholder: "点「地图选点」或直接输入",
      help: "地址与经纬度请保持一致：C 端「找附近」按经纬度排，地址只给人看" },
    { key: "openHours", label: "营业时间", section: "营业", placeholder: "10:00-22:00",
      pattern: { re: OPEN_HOURS_RE, msg: "格式应为 HH:MM-HH:MM，多段用逗号分隔" },
      help: "多段用逗号分隔，如 10:00-14:00,17:00-22:00；24 小时填 00:00-24:00" },
  ];
}

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

  const paging = usePaging();
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
    queryKey: ["op", "sites", paging.page, paging.size, keyword, status, scene, showArchived],
    queryFn: () => api.listSites({ page: paging.page, size: paging.size, keyword, status, showArchived }),
    // 场景筛选后端列表参数不支持，只能前端筛。放在 select 里而不是拿到 list 之后再 filter：
    // 那样 PagedTable 就得多开一个 rows 口子，而「rows 可以自己传」正是错误态漏接的来源。
    // 代价照旧且已在空态文案里说明：total 仍是后端的数，筛选只作用于当前页。
    select: (d) => (scene ? { ...d, list: d.list.filter((x) => x.sceneType === scene) } : d),
    placeholderData: keepPreviousData,
  });
  // 点位数 / 机柜数按**实时关系聚合**，不读 Site 上的计数字段：
  // 那两个字段是种子/冗余值，与实际关系对不上（db-design §1.4「计数不是列，是聚合」），
  // 页面若读它，就会出现「列表说有 3 台机柜，待关注说一台都没有」这种自相矛盾。
  const relQ = useQuery({
    queryKey: ["op", "site-relations"],
    queryFn: async () => ({
      points: (await api.listLocations({ page: 1, size: UNPAGED_SIZE })).list,
      cabinets: (await api.listCabinets({ page: 1, size: UNPAGED_SIZE })).list,
    }),
  });
  const countsOf = (siteNo: string) => {
    const pts = (relQ.data?.points ?? []).filter((p) => p.siteNo === siteNo);
    const cabs = (relQ.data?.cabinets ?? []).filter(
      (c) => c.siteNo === siteNo || pts.some((p) => p.locationNo === c.locationNo),
    );
    return { points: pts.length, cabinets: cabs.length };
  };

  // 场地方 / 区域下拉。两份都是小字典（几十条），一次拉全量不分页。
  const venueQ = useQuery({
    queryKey: ["op", "venue-options"],
    queryFn: () => api.listVenues({ page: 1, size: UNPAGED_SIZE }),
  });
  const regionQ = useQuery({
    queryKey: ["op", "region-options"],
    queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }),
  });
  const brandQ = useQuery({
    queryKey: ["op", "brand-options"],
    queryFn: () => api.listBrands({ page: 1, size: UNPAGED_SIZE }),
  });
  const venueOpts = useMemo(
    () => (venueQ.data?.list ?? []).map((v) => ({ value: v.venueNo, label: `${v.name}（${v.venueNo}）` })),
    [venueQ.data],
  );
  // 只列叶子层（level 3 的区/城区）：站点落在区一级，挂到「阿联酋」这种国家节点
  // 等于没收敛数据权限。与 /locations 的点位/场地方页共用同一份区域字典，口径一致。
  const regionOpts = useMemo(
    () => (regionQ.data?.list ?? []).filter((r) => r.level === 3)
      .map((r) => ({ value: r.regionId, label: `${r.name}（${r.regionId}）` })),
    [regionQ.data],
  );
  // 只列启用中的品牌：停用的品牌不该再被新站点选中（已挂着它的站点不受影响）
  const brandOpts = useMemo(
    () => (brandQ.data?.list ?? []).filter((b) => b.status === "ENABLED")
      .map((b) => ({ value: b.brandNo, label: `${b.name}（${b.brandNo}）` })),
    [brandQ.data],
  );
  const fields = useMemo(() => fieldsFor(venueOpts, regionOpts, brandOpts), [venueOpts, regionOpts, brandOpts]);

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "sites"] });

  const save = useMutation({
    mutationFn: (v: Partial<Site>) => api.saveSite(v as Partial<Site> & { siteNo?: string }),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveSite(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveSite(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });
  const pause = useMutation({
    mutationFn: (v: { siteNo: string; reason: string; pauseUntil?: string }) =>
      api.pauseSite(v.siteNo, v.reason, v.pauseUntil),
    onSuccess: () => { refresh(); notify.success("已暂停营业"); setPauseTarget(null); },
  });
  /** 撤场 / 关闭。合成一个：成功后处理完全一样，分两个会把这段抄两遍。 */
  const exitFlow = useMutation({
    mutationFn: (v: { kind: "withdraw"; siteNo: string; reason: string; plannedAt: string }
      | { kind: "close"; siteNo: string; note: string }) =>
      v.kind === "withdraw"
        ? api.withdrawSite(v.siteNo, v.reason, v.plannedAt)
        : api.closeSite(v.siteNo, v.note),
    onSuccess: (_d, v) => {
      refresh();
      qc.invalidateQueries({ queryKey: ["site-summary"] });
      notify.success(v.kind === "withdraw" ? "已进入撤场" : "站点已关闭");
      setExitTarget(null); setExitReason(""); setExitPlannedAt(""); setExitConfirm("");
    },
  });
  const resume = useMutation({
    mutationFn: (siteNo: string) => api.resumeSite(siteNo),
    onSuccess: () => { refresh(); notify.success("已恢复营业"); },
  });

  /** 摘要条：五态计数 + 两项待办。 */
  const summary = useQuery({ queryKey: ["site-summary"], queryFn: () => api.siteSummary() });

  /**
   * 门禁抽屉。开业清单（筹备中）与关闭门禁（撤场中）共用一个 ——
   * 两者的形状完全一样（一串「通过/未通过 + 为什么 + 去哪办」），
   * 分两个组件只会把同一段渲染抄两遍。
   */
  const [gateTarget, setGateTarget] = useState<Site | null>(null);
  const gate = useQuery({
    queryKey: ["site-gate", gateTarget?.siteNo, gateTarget?.status],
    queryFn: () => gateTarget!.status === "WITHDRAWING"
      ? api.siteCloseGate(gateTarget!.siteNo)
      : api.siteOpeningChecklist(gateTarget!.siteNo),
    enabled: !!gateTarget,
  });

  /** 撤场 / 关闭抽屉。 */
  const [exitTarget, setExitTarget] = useState<{ site: Site; kind: "withdraw" | "close" } | null>(null);
  const [exitReason, setExitReason] = useState("");
  const [exitPlannedAt, setExitPlannedAt] = useState("");
  /** 关站不可逆：手输站点号确认（R4）。 */
  const [exitConfirm, setExitConfirm] = useState("");

  // 只有一个品牌时直接预选：让人在唯一选项上点一下，是没有意义的一步
  const openNew = () => {
    setEditing(undefined);
    // 不预置 status：五态之后新建一律 PREPARING，由后端定。
    // 手填 ACTIVE 等于跳过开业清单——那张清单存在的意义就是不让人跳过它
    setForm({ sceneType: "商场", brandNo: brandOpts.length === 1 ? brandOpts[0].value : undefined });
  };
  const openEdit = (s: Site) => { setEditing(s); setForm({ ...s }); };
  const submit = () => {
    if (!form) return;
    if (!(form.name ?? "").trim()) { notify.error("请填写站点名称"); return; }
    if (!form.venueNo) { notify.error("请选择场地方——分成按场地方结算"); return; }
    if (!form.regionId) { notify.error("请选择区域——数据权限按区域收敛"); return; }
    if (!form.brandNo) { notify.error("请选择品牌——站点必须以某个品牌对 C 端呈现"); return; }

    const lat = Number(form.lat ?? 0), lng = Number(form.lng ?? 0);
    // 经纬度填反（把 55 填进纬度）在全球范围内完全合法，但会把站点扔到印度洋，
    // 地图上表现为「站点凭空消失」——最难查的一类脏数据，所以在入口就拦。
    const B = SITE_COORD_BOUNDS;
    if ((lat || lng) && (lat < B.latMin || lat > B.latMax || lng < B.lngMin || lng > B.lngMax)) {
      notify.error(`经纬度超出当前运营范围（纬度 ${B.latMin}~${B.latMax}，经度 ${B.lngMin}~${B.lngMax}）。是不是填反了？`);
      return;
    }

    // 名字是冗余展示字段，由所选编号带出——不让它和编号各说各话。
    const venueName = venueQ.data?.list.find((v) => v.venueNo === form.venueNo)?.name ?? form.venueName ?? "";
    const regionName = regionQ.data?.list.find((r) => r.regionId === form.regionId)?.name ?? form.regionName ?? "";
    save.mutate({ ...form, venueName, regionName, lat, lng });
  };

  // 暂停要填原因，而 confirm 只能确认不能收集输入，故用一个单字段抽屉
  const submitPause = () => {
    if (!pauseTarget) return;
    const reason = String(pauseForm.reason ?? "").trim();
    if (!reason) { notify.error("请填写暂停原因"); return; }
    // pauseUntil 可空：留空 = 无限期，填了到那天由定时任务自动恢复
    pause.mutate({ siteNo: pauseTarget.siteNo, reason, pauseUntil: String(pauseForm.pauseUntil ?? "") || undefined });
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
                {/* 开业清单 / 关闭门禁：只在对应状态出现，其余状态点开是一张空清单 */}
                {(s.status === "PREPARING" || s.status === "WITHDRAWING") && (
                  <Button size="sm" variant="outline" onClick={() => setGateTarget(s)}>
                    {s.status === "PREPARING" ? "开业清单" : "关闭门禁"}
                  </Button>
                )}
                {/* 可用性一律问 SITE_TRANSITIONS，页面不另写一套 */}
                {canPause && SITE_TRANSITIONS.pause.from.includes(s.status) && (
                  <Button size="sm" variant="outline" onClick={() => { setPauseTarget(s); setPauseForm({}); }}><Pause className="size-4" /> 暂停营业</Button>
                )}
                {canPause && SITE_TRANSITIONS.resume.from.includes(s.status) && (
                  <Button size="sm" variant="outline" onClick={async () => {
                    // 可逆但有副作用（C 端重新可借），走一次确认（R4）
                    if (await confirm({ title: `恢复营业：${s.name}？`, desc: "恢复后 C 端附近列表重新显示该站点，站内机柜允许新借。站点须有生效合同。" })) {
                      resume.mutate(s.siteNo);
                    }
                  }}><Play className="size-4" /> 恢复营业</Button>
                )}
                {canWrite && SITE_TRANSITIONS.withdraw.from.includes(s.status) && (
                  <Button size="sm" variant="outline" onClick={() => { setExitTarget({ site: s, kind: "withdraw" }); setExitReason(""); setExitPlannedAt(earliestWithdraw()); }}>撤场</Button>
                )}
                {canWrite && SITE_TRANSITIONS.close.from.includes(s.status) && (
                  <Button size="sm" variant="outline" onClick={() => { setExitTarget({ site: s, kind: "close" }); setExitReason(""); setExitConfirm(""); }}>关闭站点</Button>
                )}
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
      {summary.data && (
        /* 前五个是状态计数（一眼看出盘子的形状），后两个是**要人动手的事** ——
           缺运维责任人 / 缺营业时间，缺了站点照常营业，所以没人会主动发现 */
        <div className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-7">
          <SummaryCard label="筹备中" value={summary.data.preparing} />
          <SummaryCard label="营业中" value={summary.data.active} />
          <SummaryCard label="暂停营业" value={summary.data.paused} />
          <SummaryCard label="撤场中" value={summary.data.withdrawing} />
          <SummaryCard label="已关闭" value={summary.data.closed} />
          <SummaryCard label="缺责任人" value={summary.data.missingOwner} />
          <SummaryCard label="缺营业时间" value={summary.data.missingOpenHours} />
        </div>
      )}
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索站点名 / 编号 / 地址"
        onAdd={openNew}
        addLabel="新增站点"
        canAdd={canWrite}
      >
        <FilterSelect aria-label="状态" value={status} onChange={(v) => { setStatus(v); paging.reset(); }} options={SITE_STATUS} allLabel="全部状态" />
        <FilterSelect aria-label="场景" value={scene} onChange={(v) => { setScene(v); paging.reset(); }} options={SCENES.map((s) => ({ value: s, label: s }))} allLabel="全部场景" />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
      </Toolbar>
      <PagedTable
        query={q}
        paging={paging}
        rowKey={(s: Site) => s.siteNo}
        columns={cols}
        rowClassName={archivedRowClass}
        empty={filtered
          ? "没有符合筛选条件的站点。场景筛选只作用于当前页，翻页后需要重新筛选。"
          : "还没有站点。站点是点位和机柜的归属单位，也是分成与统计的口径，先新增站点再投放设备。"}
      />

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增站点"
        titleEdit={`编辑站点 ${editing?.siteNo ?? ""}`}
        isEdit={!!editing}
        fields={fields}
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

      {/* 开业清单 / 关闭门禁：同一个抽屉，两者形状完全一样 */}
      <Drawer
        open={!!gateTarget}
        onOpenChange={(o) => !o && setGateTarget(null)}
        title={gateTarget?.status === "WITHDRAWING" ? `关闭门禁 ${gateTarget?.name ?? ""}` : `开业清单 ${gateTarget?.name ?? ""}`}
        desc={gateTarget?.status === "WITHDRAWING"
          ? "全部了结才能关闭站点。未通过的每条都带「去处理」——不必自己猜去哪儿办"
          : "首台设备上线时站点自动转营业。这张清单列的是在那之前还差什么"}
        width="w-[560px]"
      >
        {gate.isLoading && <span className="text-muted-foreground">加载中…</span>}
        {gate.data && (
          <ol className="space-y-3">
            {gate.data.items.map((it) => (
              <li key={it.key} className="flex items-start gap-2">
                {/* 两个静态 Badge 而不是 tone={三元}：这里是布尔不是枚举，
                    而棘轮拦的正是「状态→色调」的内联映射（它该走 StatusMap） */}
                {it.passed ? <Badge tone="success">已满足</Badge> : <Badge tone="warning">待处理</Badge>}
                <div className="min-w-0">
                  <div className="txt-strong">{it.label}</div>
                  {it.detail && <div className="txt-caption text-muted-foreground">{it.detail}</div>}
                  {/* 未通过必须给去处：只说缺什么而不给链接，门禁就成了拦路虎 */}
                  {!it.passed && it.fixHref && (
                    <Link href={siteFixHref(it.fixHref) ?? it.fixHref} className="txt-caption underline">去处理</Link>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
      </Drawer>

      {/* 撤场 / 关闭 */}
      <Drawer
        open={!!exitTarget}
        onOpenChange={(o) => !o && setExitTarget(null)}
        title={exitTarget?.kind === "withdraw" ? `撤场 ${exitTarget?.site.name ?? ""}` : `关闭站点 ${exitTarget?.site.name ?? ""}`}
        desc={exitTarget?.kind === "withdraw"
          ? "进入撤场后站点停止新借（已借出的照常归还），系统为每台在站机柜开撤机工单；全部了结后再「关闭站点」"
          : "关闭不可逆。关了要重开只能另建站点——同一站点号跨两段经营期，报表再也对不上"}
        width="w-[520px]"
        footer={exitTarget && (
          <>
            <Button variant="outline" onClick={() => setExitTarget(null)}>取消</Button>
            <Button
              variant={exitTarget.kind === "close" ? "destructive" : "default"}
              disabled={exitFlow.isPending || !exitReason.trim() || (exitTarget.kind === "withdraw"
                ? !exitPlannedAt || exitPlannedAt < earliestWithdraw()
                : exitConfirm.trim() !== exitTarget.site.siteNo)}
              onClick={() => exitTarget.kind === "withdraw"
                ? exitFlow.mutate({ kind: "withdraw", siteNo: exitTarget.site.siteNo, reason: exitReason.trim(), plannedAt: exitPlannedAt })
                : exitFlow.mutate({ kind: "close", siteNo: exitTarget.site.siteNo, note: exitReason.trim() })}
            >确认</Button>
          </>
        )}
      >
        {exitTarget && (
          <>
            <Field label={exitTarget.kind === "withdraw" ? "撤场原因（必填）" : "关闭说明（必填）"}>
              <Input className="w-full" value={exitReason}
                placeholder={exitTarget.kind === "withdraw" ? "如：合同到期不续 / 场地方收回 / 低效" : "如：设备已撤、账已结清"}
                onChange={(e) => setExitReason(e.target.value)} />
            </Field>
            {exitTarget.kind === "withdraw" && (
              <Field label="计划撤场日（必填）">
                <Input type="date" className="w-full" min={earliestWithdraw()} value={exitPlannedAt} onChange={(e) => setExitPlannedAt(e.target.value)} />
                <div className="mt-1 txt-caption text-muted-foreground">
                  至少提前 {WITHDRAW_LEAD_DAYS} 天：给运维排撤机、给在借用户留归还时间（系统参数 site.withdraw.lead_days）
                </div>
              </Field>
            )}
            {exitTarget.kind === "close" && (<>
              <Field label="提醒">
                <span className="text-muted-foreground">关闭前请先看「关闭门禁」——设备没撤完、工单没结或还有在借订单时会被拒</span>
              </Field>
              <Field label={`输入站点号 ${exitTarget.site.siteNo} 确认`}>
                <Input className="w-full" value={exitConfirm} onChange={(e) => setExitConfirm(e.target.value)} />
              </Field>
            </>)}
          </>
        )}
      </Drawer>

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
