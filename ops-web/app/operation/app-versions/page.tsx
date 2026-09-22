"use client";

// 运营管理 › 基础管理 › 应用版本（清单 OM-B1，对标简电「应用版本」）
// C 端 App 各平台的版本发布、强制更新与灰度。
//
// 与旧入口（系统设置 › 应用版本）读写同一份数据，旧页面不动。
// 规则见 lib/operation-rules#validateAppVersion（版本号 / 构建号递增、同平台只一个灰度、
// 强更必须全量、已发布只能调灰度），表单与 mock 共用；状态机：草稿 → 已发布 → 已回滚。
//
// ⚠️ C 端目前没有调用后端的版本检查接口（GET /mp/app/version 已实现但 c-app 未接），
// 在这里配置的强制更新暂时不会对用户生效——页面顶部如实提示。
import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, Rocket, SlidersHorizontal, Undo2 } from "lucide-react";
import { api } from "@/lib/api";
import type { AppVersion } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { currentReleased, grayRelease, validateAppVersion } from "@/lib/operation-rules";
import { formatMarketTime } from "@/lib/market-time";
import { PageTitle } from "@/components/ui/misc";
import { SummaryCard } from "@/components/ui/summary-card";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Progress } from "@/components/ui/progress";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";

type Platform = AppVersion["platform"];
const PLATFORMS: { key: Platform; label: string }[] = [
  { key: "IOS", label: "iOS" }, { key: "ANDROID", label: "Android" }, { key: "H5", label: "H5 / 小程序" },
];
const STATUS: StatusMap<AppVersion["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  RELEASED: { label: "已发布", tone: "success" },
  ROLLBACK: { label: "已回滚", tone: "danger" },
};

const FIELDS: FieldDef[] = [
  { key: "platform", label: "平台", type: "select", required: true, readOnlyOnEdit: true, section: "基本信息",
    options: PLATFORMS.map((p) => ({ value: p.key, label: p.label })) },
  { key: "versionNo", label: "版本号", required: true, readOnlyOnEdit: true, section: "基本信息", placeholder: "1.5.0",
    pattern: { re: "^\\d+\\.\\d+\\.\\d+$", msg: "版本号格式为 x.y.z，例如 1.5.0" }, help: "必须高于该平台已有的最高版本" },
  { key: "buildNo", label: "构建号", type: "number", required: true, min: 1, section: "基本信息", help: "必须大于该平台已有的最大构建号" },
  { key: "releaseNote", label: "更新说明（中文）", type: "textarea", rows: 3, required: true, maxLength: 200, section: "更新说明（C 端更新弹窗展示）" },
  { key: "releaseNoteEn", label: "更新说明（English）", type: "textarea", rows: 3, maxLength: 300, section: "更新说明（C 端更新弹窗展示）" },
  { key: "releaseNoteAr", label: "更新说明（العربية）", type: "textarea", rows: 3, maxLength: 300, section: "更新说明（C 端更新弹窗展示）" },
  { key: "forceUpdate", label: "强制更新", type: "switch", section: "发布控制", help: "开启后低于「最低支持版本」的 App 启动时会被拦住，必须更新；强更版本只能全量发布" },
  { key: "minSupported", label: "最低支持版本", section: "发布控制", placeholder: "1.4.3",
    pattern: { re: "^\\d+\\.\\d+\\.\\d+$", msg: "版本号格式为 x.y.z" }, help: "强制更新时必填，且不能高于本版本" },
  { key: "rolloutPercent", label: "灰度比例（%）", type: "number", required: true, min: 0, max: 100, section: "发布控制",
    help: "发布后按设备稳定命中这个比例的用户；先小比例观察崩溃率再放量。同一平台同时只能有一个版本在灰度" },
  { key: "downloadUrl", label: "下载地址", maxLength: 300, section: "发布控制", placeholder: "https://apps.apple.com/app/id…",
    help: "iOS 填 App Store 链接，Android 填 APK 地址；H5 不需要" },
];
const ROLLOUT_FIELDS: FieldDef[] = [
  { key: "rolloutPercent", label: "灰度比例（%）", type: "number", required: true, min: 1, max: 100, help: "调到 100 即全量；要停止下发请用「回滚」" },
];

function RolloutBar({ percent }: { percent: number }) {
  const v = Math.max(0, Math.min(100, percent));
  return (
    <div className="flex items-center gap-2">
      <Progress value={v} total={100} showText={false} className="w-20 min-w-0" />
      <span className="tabular-nums txt-caption text-muted-foreground">{v}%</span>
    </div>
  );
}

export default function AppVersionsPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav } = useI18n();
  const { confirm, dialog } = useConfirm();
  const canWrite = allow("system:app_version:release");

  const [platform, setPlatform] = useState<Platform>("IOS");
  const [keyword, setKeyword] = useState("");
  const [form, setForm] = useState<Partial<AppVersion> | null>(null);
  const [editing, setEditing] = useState<AppVersion | undefined>();
  const [rollout, setRollout] = useState<AppVersion | null>(null);
  const [rolloutValue, setRolloutValue] = useState<Record<string, unknown>>({});

  // 取全部平台：跨记录规则（版本号递增、同平台只一个灰度）要看到同平台的所有版本，不能只看当前页
  const q = useQuery({ queryKey: ["op", "app-versions"], queryFn: () => api.listAppVersions({ page: 1, size: 500 }) });
  const all = useMemo(() => q.data?.list ?? [], [q.data]);
  const rows = all
    .filter((v) => v.platform === platform && (!keyword || v.versionNo.includes(keyword) || v.releaseNote.includes(keyword)))
    .sort((a, b) => b.buildNo - a.buildNo);
  const online = currentReleased(all, platform);
  const gray = grayRelease(all, platform);
  const forceFloor = all
    .filter((v) => v.platform === platform && v.status === "RELEASED" && v.forceUpdate && v.minSupported)
    .map((v) => v.minSupported)
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))[0];

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "app-versions"] });
  const save = useMutation({
    mutationFn: ({ v }: { v: Partial<AppVersion>; msg: string }) => api.saveAppVersion(v),
    onSuccess: (_r, { msg }) => { refresh(); notify.success(msg); setForm(null); setRollout(null); },
  });
  const rollback = useMutation({
    mutationFn: (id: string) => api.rollbackAppVersion(id),
    onSuccess: () => { refresh(); notify.success("已回滚，已停止下发该版本"); },
  });

  const openNew = () => {
    const maxBuild = Math.max(0, ...all.filter((v) => v.platform === platform).map((v) => v.buildNo));
    setEditing(undefined);
    setForm({ platform, status: "DRAFT", forceUpdate: false, rolloutPercent: 10, buildNo: maxBuild + 1, releaseNote: "", downloadUrl: "" });
  };
  const openEdit = (v: AppVersion) => { setEditing(v); setForm({ ...v }); };

  const submit = () => {
    if (!form) return;
    const v = { ...form, buildNo: Number(form.buildNo), rolloutPercent: Number(form.rolloutPercent) };
    const errors = validateAppVersion(v, editing, all);
    if (errors.length) { notify.error(errors[0]); return; }
    save.mutate({ v, msg: "已保存" });
  };

  const publish = async (v: AppVersion) => {
    const next = { ...v, status: "RELEASED" as const };
    const errors = validateAppVersion(next, v, all);
    if (errors.length) { notify.error(errors[0]); return; }
    if (v.rolloutPercent <= 0) { notify.error("灰度比例为 0 时发布不会有任何用户收到，请先编辑设置灰度比例"); return; }
    const scope = v.rolloutPercent >= 100 ? "全部用户" : `约 ${v.rolloutPercent}% 的用户`;
    const ok = await confirm({
      title: `发布 ${PLATFORMS.find((p) => p.key === v.platform)?.label} ${v.versionNo}`,
      desc: `发布后${scope}会收到更新提示${v.forceUpdate ? `；低于 ${v.minSupported} 的 App 将被强制更新` : ""}。发布后版本内容不能再修改，只能调整灰度或回滚。`,
      danger: v.forceUpdate,
      confirmText: "发布",
    });
    if (ok) save.mutate({ v: { versionId: v.versionId, status: "RELEASED" }, msg: "已发布" });
  };

  const doRollback = async (v: AppVersion) => {
    const ok = await confirm({
      title: `回滚 ${v.versionNo}`,
      desc: "回滚后立即停止下发这个版本，已经安装的用户不受影响；回滚不可撤销，需要时请发布新版本。请输入版本号确认。",
      danger: true,
      confirmText: "回滚",
      requireText: v.versionNo,
    });
    if (ok) rollback.mutate(v.versionId);
  };

  const submitRollout = () => {
    if (!rollout) return;
    const pct = Number(rolloutValue.rolloutPercent);
    const errors = validateAppVersion({ ...rollout, rolloutPercent: pct }, rollout, all);
    if (errors.length) { notify.error(errors[0]); return; }
    save.mutate({ v: { versionId: rollout.versionId, rolloutPercent: pct }, msg: pct >= 100 ? "已全量发布" : `灰度已调整为 ${pct}%` });
  };

  const cols: Column<AppVersion>[] = [
    { header: "版本", className: "whitespace-nowrap", cell: (v) => (
      <div>
        <div className="font-mono">{v.versionNo}</div>
        <div className="txt-caption text-muted-foreground">构建 {v.buildNo}</div>
      </div>
    ) },
    { header: "更新说明", className: "min-w-56", cell: (v) => <span className="line-clamp-2 txt-body">{v.releaseNote}</span> },
    { header: "强制更新", className: "whitespace-nowrap", cell: (v) => v.forceUpdate ? <span>是 · 最低 {v.minSupported}</span> : <span className="text-muted-foreground">否</span> },
    { header: "灰度", className: "whitespace-nowrap", cell: (v) => (v.status === "DRAFT" ? <span className="txt-caption text-muted-foreground">计划 {v.rolloutPercent}%</span> : <RolloutBar percent={v.rolloutPercent} />) },
    { header: "状态", className: "whitespace-nowrap", cell: (v) => <StatusBadge map={STATUS} value={v.status} /> },
    { header: "发布时间", className: "whitespace-nowrap", cell: (v) => <span className="txt-caption">{v.releasedAt ? formatMarketTime(v.releasedAt) : "-"}</span> },
    {
      header: "操作",
      cell: (v) => {
        if (!canWrite) return <span className="text-muted-foreground">-</span>;
        return (
          <div className="flex w-max items-center gap-2">
            {v.status === "DRAFT" && (
              <>
                <Button size="sm" variant="outline" onClick={() => openEdit(v)}><Pencil className="size-4" /> 编辑</Button>
                <Button size="sm" onClick={() => publish(v)}><Rocket className="size-4" /> 发布</Button>
              </>
            )}
            {v.status === "RELEASED" && (
              <>
                {v.rolloutPercent < 100 && (
                  <Button size="sm" variant="outline" onClick={() => { setRollout(v); setRolloutValue({ rolloutPercent: v.rolloutPercent }); }}>
                    <SlidersHorizontal className="size-4" /> 调整灰度
                  </Button>
                )}
                <Button size="sm" variant="outline" onClick={() => doRollback(v)}><Undo2 className="size-4" /> 回滚</Button>
              </>
            )}
            {v.status === "ROLLBACK" && <span className="txt-caption text-muted-foreground">已停止下发</span>}
          </div>
        );
      },
    },
  ];

  return (
    <div>
      <PageTitle title={tNav("应用版本")} desc="C 端 App 的版本发布、强制更新与灰度" />
      <Notice>
        C 端 App 目前还没有接入版本检查：在这里发布的版本和强制更新设置，暂时不会弹给用户。
        后端接口 <code>GET /mp/app/version</code> 已就绪，需要在 c-app 启动时补上调用。
      </Notice>
      {!canWrite && <ReadOnlyNotice what="版本发布" perm="system:app_version:release" note="不能新建、发布、调整灰度或回滚" className="mb-3" />}
      <Tabs tabs={PLATFORMS} value={platform} onChange={(k) => setPlatform(k as Platform)} />
      <div className="mb-5 grid grid-cols-1 gap-4 md:grid-cols-3">
        <SummaryCard label="当前线上版本（全量）" value={online ? online.versionNo : "—"} sub={online ? `构建 ${online.buildNo} · ${formatMarketTime(online.releasedAt)} 发布` : "该平台还没有全量发布的版本"} />
        <SummaryCard label="灰度中" value={gray ? `${gray.versionNo} · ${gray.rolloutPercent}%` : "—"} sub={gray ? "同一平台同时只能有一个版本在灰度" : "没有正在灰度的版本"} />
        <SummaryCard label="强制更新下限" value={forceFloor ?? "—"} sub={forceFloor ? "低于此版本的 App 会被要求更新" : "未设置强制更新"} />
      </div>
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="搜索版本号 / 更新说明" onAdd={openNew} addLabel="新建版本" canAdd={canWrite} />
      <DataTable
        rowKey={(v: AppVersion) => v.versionId}
        columns={cols}
        rows={q.isLoading ? undefined : rows}
        loading={q.isLoading}
        empty={keyword ? "没有符合搜索条件的版本。" : "这个平台还没有发过版本。先新建一个草稿，确认无误后再小比例灰度发布。"}
      />

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建版本（保存为草稿）"
        titleEdit={`编辑草稿 ${editing?.versionNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<AppVersion>)}
        onSubmit={submit}
        submitting={save.isPending}
        width="w-[520px]"
      />
      <FormDrawer
        open={!!rollout}
        onOpenChange={(o) => !o && setRollout(null)}
        titleNew=""
        titleEdit={`调整灰度 ${rollout?.versionNo ?? ""}`}
        isEdit
        fields={ROLLOUT_FIELDS}
        value={rolloutValue}
        onChange={setRolloutValue}
        onSubmit={submitRollout}
        submitting={save.isPending}
      />
      {dialog}
    </div>
  );
}
