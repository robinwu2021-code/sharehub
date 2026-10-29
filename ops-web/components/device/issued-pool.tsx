"use client";

// 出库池（设备管理 › 仓储与出库）—— 开发计划 F3；口径：用户 2026-10-28
//   绑定商家先出库，出库必须选商家；绑定的设备只从该商家的出库池里选，
//   默认绑到商家的默认站点、默认点位；站点与点位由运营代建；绑定在运营端做。
//
// 左：按商家汇总（未绑定 / 已绑定 / 已上线）；右：所选商家出库池里的设备。
// 动作：出库给商家 · 绑定（单台 / 批量）· 解绑 · 退回仓库。规则全在服务端（mock 同口径强制），
// 这里只做「能点哪些」的提示，违规由接口拒绝并给原因。
//
// 后端分步上线（lib/backend-ready.ts）：devices.pool（出库 / 退回 / 汇总，已上线）、
// devices.bind（绑定 / 解绑，待上线）—— 未上线的动作按钮不渲染，并写明原因。
import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { featureReady } from "@/lib/backend-ready";
import { usePaging } from "@/lib/hooks/use-paging";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Pagination } from "@/components/ui/misc";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Notice } from "@/components/ui/notice";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { RefLink } from "@/components/ref-link";
import { agentCoreComplete } from "@/lib/types";
import { useAuth } from "@/lib/auth";
import type { Agent, Cabinet, IssuedSummary, PoolFilter } from "@/lib/types";

type PoolState = "UNCLAIMED" | "UNBOUND" | "BOUND" | "LIVE" | "FAULT";
export const POOL_STATE_MAP: StatusMap<PoolState> = {
  UNCLAIMED: { label: "待签收", tone: "info" },
  UNBOUND: { label: "待绑定", tone: "warning" },
  BOUND: { label: "已绑定 · 待安装", tone: "info" },
  LIVE: { label: "已上线", tone: "success" },
  FAULT: { label: "故障", tone: "danger" },
};
export const poolStateOf = (c: Cabinet): PoolState =>
  !c.locationNo ? (c.issueClaimedAt === null ? "UNCLAIMED" : "UNBOUND") : c.status === "DEPLOYED" ? "LIVE" : c.status === "FAULT" ? "FAULT" : "BOUND";

const POOL_FILTER: Record<PoolFilter, string> = { UNBOUND: "待绑定", BOUND: "待安装", UNCLAIMED: "待签收", MINE: "待我签收", ALL: "全部" };

export function IssuedPoolTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const canTransfer = allow("device:inventory:transfer");
  const canBind = allow("device:cabinet:update");
  const poolReady = featureReady("devices.pool");
  const bindReady = featureReady("devices.bind");
  const paging = usePaging();
  const [agentNo, setAgentNo] = useState("");
  const [pool, setPool] = useState<PoolFilter>("UNBOUND");
  const [keyword, setKeyword] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [issueOpen, setIssueOpen] = useState(false);
  const [bindTarget, setBindTarget] = useState<{ agentNo: string; siteNo?: string; cabinetNos: string[] } | null>(null);
  const [reasonFor, setReasonFor] = useState<{ kind: "unbind" | "return"; cabinetNos: string[] } | null>(null);
  const [redirectFor, setRedirectFor] = useState<Cabinet[] | null>(null);
  const me = useAuth((a) => a.username);

  const summaryQ = useQuery({ queryKey: ["issued-summary"], queryFn: () => api.issuedSummary(), enabled: poolReady });
  const listQ = useQuery({
    queryKey: ["issued-pool", agentNo, pool, keyword, paging.page, paging.size],
    queryFn: () => api.listCabinets({ issuedAgentNo: agentNo || undefined, pool, keyword, page: paging.page, size: paging.size }),
    placeholderData: keepPreviousData,
    enabled: poolReady,
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["issued-summary"] });
    qc.invalidateQueries({ queryKey: ["issued-pool"] });
    qc.invalidateQueries({ queryKey: ["cabinets"] });
    setSelected([]);
  };

  const rows = listQ.data?.list ?? [];
  const selectedRows = rows.filter((c) => selected.includes(c.cabinetNo));
  const selAgents = [...new Set(selectedRows.map((c) => c.issuedAgentNo))];
  const selUnbound = selectedRows.length > 0 && selectedRows.every((c) => !c.locationNo);
  const selMine = selectedRows.length > 0 && selectedRows.every((c) => poolStateOf(c) === "UNCLAIMED" && c.issueReceiverNo === me);
  const claimM = useMutation({
    mutationFn: (cabinetNos: string[]) => api.claimDevices({ cabinetNos }),
    onSuccess: (r) => { notify.success(`已签收 ${r.length} 台`); refresh(); },
  });

  const summaryCols: Column<IssuedSummary>[] = [
    { header: "商家", cell: (r) => (
      <button type="button" className="txt-strong text-start hover:underline" onClick={() => { setAgentNo(r.agentNo); paging.reset(); }}>
        {r.agentName} <span className="txt-caption text-muted-foreground tabular-nums">{r.agentNo}</span>
      </button>
    ) },
    { header: "待绑定", className: "text-right", cell: (r) => <span className="tabular-nums">{r.unbound}</span> },
    { header: "待安装", className: "text-right", cell: (r) => <span className="tabular-nums">{r.bound}</span> },
    { header: "已上线", className: "text-right", cell: (r) => <span className="tabular-nums">{r.live}</span> },
  ];
  const cols: Column<Cabinet>[] = [
    { header: "柜机号", cell: (c) => <Link href={`/devices/detail?no=${c.cabinetNo}`} className="txt-strong tabular-nums hover:underline">{c.cabinetNo}</Link> },
    { header: "SN / 型号", cell: (c) => <span className="tabular-nums">{c.sn}<span className="text-muted-foreground"> · {c.model}</span></span> },
    { header: "出库给", cell: (c) => c.issuedAgentNo ? <RefLink kind="agent" no={c.issuedAgentNo} /> : "-" },
    { header: "目的站点", cell: (c) => c.issuedSiteNo ? <RefLink kind="site" no={c.issuedSiteNo} /> : <span className="text-muted-foreground">到商家</span> },
    { header: "接收人", cell: (c) => <span className="tabular-nums">{c.issueReceiverNo ?? "-"}</span> },
    { header: "状态", cell: (c) => <StatusBadge map={POOL_STATE_MAP} value={poolStateOf(c)} /> },
    { header: "站点 › 点位", cell: (c) => c.siteNo
      ? <span><RefLink kind="site" no={c.siteNo} label={c.locationName ?? c.siteNo} /> <span className="text-muted-foreground tabular-nums">› {c.locationNo}</span></span>
      : <span className="text-muted-foreground">未绑定</span> },
    { header: "出库时间", cell: (c) => <span className="text-muted-foreground">{c.issuedAt ? fmtTime(c.issuedAt) : "-"}</span> },
    { header: "操作", className: "whitespace-nowrap", cell: (c) => {
      const s = poolStateOf(c);
      return (
        <span className="inline-flex gap-1.5">
          {s === "UNCLAIMED" && c.issueReceiverNo === me && (
            <Button size="sm" disabled={claimM.isPending} onClick={() => claimM.mutate([c.cabinetNo])}>签收</Button>
          )}
          {(s === "UNCLAIMED" || s === "UNBOUND") && canTransfer && (
            <Button size="sm" variant="outline" onClick={() => setRedirectFor([c])}>调整</Button>
          )}
          {s === "UNBOUND" && canBind && bindReady && (
            <Button size="sm" onClick={() => setBindTarget({ agentNo: c.issuedAgentNo!, siteNo: c.issuedSiteNo ?? undefined, cabinetNos: [c.cabinetNo] })}>绑定</Button>
          )}
          {s === "BOUND" && canBind && bindReady && (
            <Button size="sm" variant="outline" onClick={() => setReasonFor({ kind: "unbind", cabinetNos: [c.cabinetNo] })}>解绑</Button>
          )}
          {(s === "UNCLAIMED" || s === "UNBOUND") && canTransfer && (
            <Button size="sm" variant="outline" onClick={() => setReasonFor({ kind: "return", cabinetNos: [c.cabinetNo] })}>退回仓库</Button>
          )}
          {(s === "LIVE" || s === "FAULT") && <span className="txt-caption text-muted-foreground">已上线，移位走撤机工单</span>}
        </span>
      );
    } },
  ];

  if (!poolReady) {
    return <Notice>出库池的后端接口尚未上线（商户入驻会话实现中）。上线后此页自动可用。</Notice>;
  }

  return (
    <div className="space-y-4">
      <Notice>
        绑定商家先出库：出库必须选商家，设备进入该商家的出库池；绑定只能从出库池里选，
        默认绑到商家的默认站点、默认点位。出库池里的设备商家端看得到（待安装），但没有点位就不产生营收。
        {!bindReady && " 绑定 / 解绑的后端接口尚未上线，暂时只能出库与退回。"}
      </Notice>
      {!canTransfer && <ReadOnlyNotice what="出库与退回" perm="device:inventory:transfer" />}

      <DataTable
        rowKey={(r: IssuedSummary) => r.agentNo}
        columns={summaryCols}
        rows={summaryQ.data}
        loading={summaryQ.isLoading} error={summaryQ.error} onRetry={summaryQ.refetch}
        rowClassName={(r) => (r.agentNo === agentNo ? "bg-accent" : undefined)}
        empty="还没有设备出库给任何商家 —— 点右上「出库给商家」，从仓里选设备出库"
      />

      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索柜机号 / 站点"
        onAdd={canTransfer ? () => setIssueOpen(true) : undefined}
        addLabel="出库给商家"
      >
        <Select className="w-48" aria-label="出库池商家" value={agentNo} onChange={(e) => { setAgentNo(e.target.value); paging.reset(); }}>
          <option value="">全部商家</option>
          {(summaryQ.data ?? []).map((r) => <option key={r.agentNo} value={r.agentNo}>{r.agentName}</option>)}
        </Select>
        <Select className="w-32" aria-label="出库池状态" value={pool} onChange={(e) => { setPool(e.target.value as PoolFilter); paging.reset(); }}>
          {(Object.keys(POOL_FILTER) as PoolFilter[]).map((k) => <option key={k} value={k}>{POOL_FILTER[k]}</option>)}
        </Select>
      </Toolbar>

      {selectedRows.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-card bg-muted px-3.5 py-2 txt-body">
          <span>已选 {selectedRows.length} 台</span>
          {selMine && <Button size="sm" disabled={claimM.isPending} onClick={() => claimM.mutate(selectedRows.map((c) => c.cabinetNo))}>批量签收</Button>}
          {canBind && bindReady && selUnbound && selAgents.length === 1 && selectedRows.every((c) => poolStateOf(c) === "UNBOUND") && (
            <Button size="sm" onClick={() => setBindTarget({ agentNo: selAgents[0]!, cabinetNos: selectedRows.map((c) => c.cabinetNo) })}>批量绑定</Button>
          )}
          {canTransfer && selUnbound && (
            <Button size="sm" variant="outline" onClick={() => setReasonFor({ kind: "return", cabinetNos: selectedRows.map((c) => c.cabinetNo) })}>批量退回仓库</Button>
          )}
          {selAgents.length > 1 && <span className="txt-caption text-muted-foreground">批量绑定只能选同一个商家的设备</span>}
          {!selUnbound && <span className="txt-caption text-muted-foreground">选中的设备里有已绑定的：批量动作只对未绑定的设备</span>}
          <Button size="sm" variant="ghost" onClick={() => setSelected([])}>清除</Button>
        </div>
      )}

      <DataTable
        rowKey={(c: Cabinet) => c.cabinetNo}
        columns={cols}
        rows={listQ.data?.list}
        loading={listQ.isLoading} error={listQ.error} onRetry={listQ.refetch}
        selectable={canTransfer || canBind}
        selectedKeys={selected}
        onSelectedChange={setSelected}
        empty={agentNo ? `${POOL_FILTER[pool]}的出库池设备为空 —— 换个筛选，或先出库给这个商家` : "出库池为空 —— 先出库给商家"}
      />
      {listQ.data && <Pagination page={paging.page} size={paging.size} total={listQ.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      <IssueDrawer open={issueOpen} onClose={() => setIssueOpen(false)} defaultAgent={agentNo} onDone={refresh} />
      <BindDrawer target={bindTarget} onClose={() => setBindTarget(null)} onDone={refresh} />
      <ReasonDrawer target={reasonFor} onClose={() => setReasonFor(null)} onDone={refresh} />
      <RedirectDrawer rows={redirectFor} onClose={() => setRedirectFor(null)} onDone={refresh} />
    </div>
  );
}

/** 出库给商家：选商家（只列资料齐全、已启用的）→ 选仓里在库且质检放行的设备。 */
function IssueDrawer({ open, onClose, defaultAgent, onDone }: {
  open: boolean; onClose: () => void; defaultAgent: string; onDone: () => void;
}) {
  const [agentNo, setAgentNo] = useState("");
  const [siteNo, setSiteNo] = useState("");
  const [receiverNo, setReceiverNo] = useState("");
  const [picked, setPicked] = useState<string[]>([]);
  const agentsQ = useQuery({ queryKey: ["agents", "issuable"], queryFn: () => api.listAgents({ size: UNPAGED_SIZE }), enabled: open });
  const stockQ = useQuery({
    queryKey: ["cabinets", "in-stock"],
    queryFn: () => api.listCabinets({ status: "IN_STOCK", size: UNPAGED_SIZE }),
    enabled: open,
  });
  const issuable = (agentsQ.data?.list ?? []).filter((a: Agent) => a.status === "ENABLED" && !a.archivedAt);
  const stock = useMemo(() => (stockQ.data?.list ?? []).filter((c) =>
    !c.issuedAgentNo && !c.locationNo && !c.archivedAt && (!c.qcStatus || c.qcStatus === "PASSED")), [stockQ.data]);
  const chosen = issuable.find((a) => a.agentNo === (agentNo || defaultAgent));
  const notReady = chosen && (!agentCoreComplete(chosen) || !chosen.defaultSiteNo);
  const m = useMutation({
    mutationFn: () => api.issueDevices({ agentNo: chosen!.agentNo, siteNo: siteNo || undefined, receiverNo: receiverNo || undefined, cabinetNos: picked }),
    onSuccess: (r) => {
      notify.success(`已出库 ${r.length} 台给 ${chosen!.name}`);
      setPicked([]); setAgentNo(""); setSiteNo(""); setReceiverNo(""); onClose(); onDone();
    },
  });
  return (
    <Drawer open={open} onOpenChange={(o) => !o && onClose()} title="出库给商家" desc="设备离开仓库，进入该商家的出库池；之后从出库池绑定到它的站点点位"
      footer={<Button disabled={!chosen || !!notReady || !picked.length || m.isPending} onClick={() => m.mutate()}>出库 {picked.length || ""} 台</Button>}>
      <Field label="商家（必选）">
        <Select aria-label="商家" value={agentNo || defaultAgent} onChange={(e) => setAgentNo(e.target.value)}>
          <option value="">请选择商家</option>
          {issuable.map((a) => <option key={a.agentNo} value={a.agentNo}>{a.name}（{a.agentNo}）{agentCoreComplete(a) ? "" : " · 资料未齐"}</option>)}
        </Select>
        {notReady && <div className="mt-1 txt-caption text-destructive-ink">该商家还没补齐位置与行业（没有默认站点），补齐后才能出库：去 <RefLink kind="agent" no={chosen!.agentNo} label="商家档案" /></div>}
      </Field>
      <DestinationFields agentNo={chosen?.agentNo ?? ""} siteNo={siteNo} onSite={setSiteNo} receiverNo={receiverNo} onReceiver={setReceiverNo} />
      <Field label={`仓里可出库的设备（在库 · 质检放行 · ${stock.length} 台）`}>
        {stockQ.isLoading ? <span className="text-muted-foreground">加载中…</span>
          : !stock.length ? <span className="text-muted-foreground">仓里没有可出库的设备</span>
            : (
              <div className="max-h-72 space-y-1 overflow-y-auto">
                {stock.map((c) => (
                  <label key={c.cabinetNo} className="flex items-center gap-2 txt-body">
                    <input type="checkbox" checked={picked.includes(c.cabinetNo)}
                      onChange={(e) => setPicked(e.target.checked ? [...picked, c.cabinetNo] : picked.filter((x) => x !== c.cabinetNo))} />
                    <span className="tabular-nums">{c.cabinetNo}</span>
                    <span className="text-muted-foreground">{c.model} · {c.warehouseNo ?? "-"}</span>
                  </label>
                ))}
              </div>
            )}
      </Field>
    </Drawer>
  );
}

/** 绑定：默认选中商家的默认站点、默认点位，可改选该商家的其它站点与点位；可同时建装机工单。 */
function BindDrawer({ target, onClose, onDone }: {
  target: { agentNo: string; siteNo?: string; cabinetNos: string[] } | null; onClose: () => void; onDone: () => void;
}) {
  const agentNo = target?.agentNo ?? "";
  const fixedSite = target?.siteNo;
  const [siteNo, setSiteNo] = useState("");
  const [locationNo, setLocationNo] = useState("");
  const [openWo, setOpenWo] = useState(true);
  const agentQ = useQuery({ queryKey: ["agent", agentNo], queryFn: () => api.listAgents({ keyword: agentNo, size: 5 }), enabled: !!agentNo });
  const agent = agentQ.data?.list.find((a) => a.agentNo === agentNo);
  const sitesQ = useQuery({ queryKey: ["sites", "of-agent", agentNo], queryFn: () => api.listSites({ agentNo, size: UNPAGED_SIZE }), enabled: !!agentNo });
  // 出库到站点的只能绑该站点（V195）
  const site = fixedSite || siteNo || agent?.defaultSiteNo || "";
  const pointsQ = useQuery({ queryKey: ["locations", "of-site", site], queryFn: () => api.listLocations({ siteNo: site, size: UNPAGED_SIZE }), enabled: !!site });
  const points = (pointsQ.data?.list ?? []).filter((l) => l.status === "ACTIVE" && !l.archivedAt);
  const point = locationNo || points.find((l) => l.isDefault)?.locationNo || "";
  const reset = () => { setSiteNo(""); setLocationNo(""); setOpenWo(true); };
  const m = useMutation({
    mutationFn: () => target!.cabinetNos.length === 1
      ? api.bindDevice(target!.cabinetNos[0], { locationNo: point || undefined, openInstallWo: openWo }).then((r) => [r])
      : api.bindDevices({ agentNo, deviceNos: target!.cabinetNos, locationNo: point || undefined, openInstallWo: openWo }),
    onSuccess: (r) => {
      const wos = r.map((x) => x.woNo).filter(Boolean);
      notify.success(`已绑定 ${r.length} 台${wos.length ? `，装机工单 ${wos.join("、")}` : ""}`);
      reset(); onClose(); onDone();
    },
  });
  return (
    <Drawer open={!!target} onOpenChange={(o) => { if (!o) { reset(); onClose(); } }}
      title={`绑定 ${target?.cabinetNos.length ?? 0} 台到 ${agent?.name ?? agentNo}`}
      desc="只能绑到这个商家的站点与点位；默认是它的默认站点、默认点位"
      footer={<Button disabled={!point || m.isPending} onClick={() => m.mutate()}>确认绑定</Button>}>
      <Field label="站点">
        <Select aria-label="站点" value={site} disabled={!!fixedSite} onChange={(e) => { setSiteNo(e.target.value); setLocationNo(""); }}>
          {!site && <option value="">请选择站点</option>}
          {(sitesQ.data?.list ?? []).map((s) => (
            <option key={s.siteNo} value={s.siteNo}>{s.name}{s.siteNo === agent?.defaultSiteNo ? "（默认站点）" : ""}</option>
          ))}
        </Select>
      </Field>
      <Field label="点位">
        <Select aria-label="点位" value={point} onChange={(e) => setLocationNo(e.target.value)}>
          {!point && <option value="">请选择点位</option>}
          {points.map((l) => <option key={l.locationNo} value={l.locationNo}>{l.name}{l.isDefault ? "（默认点位）" : ""}</option>)}
        </Select>
        {site && !pointsQ.isLoading && !points.length && <div className="mt-1 txt-caption text-muted-foreground">这个站点还没有可用点位</div>}
      </Field>
      <label className="flex items-center gap-2 txt-body">
        <input type="checkbox" checked={openWo} onChange={(e) => setOpenWo(e.target.checked)} />
        同时建装机工单（绑定只是定下位置，装好、试借还、过门禁后才上线）
      </label>
      <div className="mt-3 txt-caption text-muted-foreground">设备：{target?.cabinetNos.join("、")}</div>
    </Drawer>
  );
}

/** 解绑 / 退回仓库：都要写原因。 */
function ReasonDrawer({ target, onClose, onDone }: {
  target: { kind: "unbind" | "return"; cabinetNos: string[] } | null; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = useState("");
  const [warehouseNo, setWarehouseNo] = useState("");
  const isReturn = target?.kind === "return";
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses(), enabled: isReturn });
  const defaultWh = whQ.data?.find((w) => w.isDefault);
  const m = useMutation({
    mutationFn: async () => isReturn
      ? api.returnPoolDevices({ cabinetNos: target!.cabinetNos, reason, warehouseNo: warehouseNo || undefined })
      : Promise.all(target!.cabinetNos.map((no) => api.unbindDevice(no, reason))),
    onSuccess: (r) => {
      notify.success(isReturn ? `已退回仓库 ${r.length} 台` : `已解绑 ${r.length} 台，回到出库池`);
      setReason(""); setWarehouseNo(""); onClose(); onDone();
    },
  });
  return (
    <Drawer open={!!target} onOpenChange={(o) => !o && onClose()}
      title={isReturn ? `退回仓库 ${target?.cabinetNos.length ?? 0} 台` : `解绑 ${target?.cabinetNos.length ?? 0} 台`}
      desc={isReturn ? "设备离开商家的出库池，回到仓里；只收未绑定的设备" : "解除点位绑定，设备回到同一商家的出库池；已上线的要走撤机工单"}
      footer={<Button disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>{isReturn ? "确认退回" : "确认解绑"}</Button>}>
      <Field label="原因（必填）">
        <Textarea value={reason} onChange={setReason} placeholder={isReturn ? "如：多发了 / 商家暂不需要" : "如：换点位"} />
      </Field>
      {isReturn && (
        <Field label="退回到仓">
          <Select aria-label="退回到仓" value={warehouseNo} onChange={(e) => setWarehouseNo(e.target.value)}>
            <option value="">默认回仓仓{defaultWh ? `（${defaultWh.name}）` : ""}</option>
            {(whQ.data ?? []).map((w) => <option key={w.warehouseNo} value={w.warehouseNo}>{w.name} · {w.warehouseNo}</option>)}
          </Select>
        </Field>
      )}
      <div className="txt-caption text-muted-foreground">设备：{target?.cabinetNos.join("、")}</div>
    </Drawer>
  );
}

/** 目的站点（可空 = 到商家）+ 内部接收人（可空 = 出库即签收）。出库与调整共用。 */
function DestinationFields({ agentNo, siteNo, onSite, receiverNo, onReceiver }: {
  agentNo: string; siteNo: string; onSite: (v: string) => void; receiverNo: string; onReceiver: (v: string) => void;
}) {
  const sitesQ = useQuery({ queryKey: ["sites", "of-agent", agentNo], queryFn: () => api.listSites({ agentNo, size: UNPAGED_SIZE }), enabled: !!agentNo });
  const empQ = useQuery({ queryKey: ["employees", "all"], queryFn: () => api.listEmployees({ size: UNPAGED_SIZE }) });
  return (
    <>
      <Field label="目的站点">
        <Select aria-label="目的站点" value={siteNo} onChange={(e) => onSite(e.target.value)} disabled={!agentNo}>
          <option value="">到商家（不限站点）</option>
          {(sitesQ.data?.list ?? []).map((s) => <option key={s.siteNo} value={s.siteNo}>{s.name}</option>)}
        </Select>
      </Field>
      <Field label="接收人">
        <Select aria-label="接收人" value={receiverNo} onChange={(e) => onReceiver(e.target.value)}>
          <option value="">不指定（出库即签收）</option>
          {(empQ.data?.list ?? []).filter((e) => e.status === "ACTIVE").map((e) => (
            <option key={e.employeeNo} value={e.employeeNo}>{e.name}（{e.employeeNo}）</option>
          ))}
        </Select>
      </Field>
    </>
  );
}

/** 出库调整：改商家 / 目的站点 / 接收人；原因必填；换了接收人要重新签收。 */
function RedirectDrawer({ rows, onClose, onDone }: { rows: Cabinet[] | null; onClose: () => void; onDone: () => void }) {
  const first = rows?.[0];
  const [agentNo, setAgentNo] = useState<string | null>(null);
  const [siteNo, setSiteNo] = useState<string | null>(null);
  const [receiverNo, setReceiverNo] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const agentsQ = useQuery({ queryKey: ["agents", "issuable"], queryFn: () => api.listAgents({ size: UNPAGED_SIZE }), enabled: !!rows });
  const agent = agentNo ?? first?.issuedAgentNo ?? "";
  const site = siteNo ?? first?.issuedSiteNo ?? "";
  const receiver = receiverNo ?? first?.issueReceiverNo ?? "";
  const reset = () => { setAgentNo(null); setSiteNo(null); setReceiverNo(null); setReason(""); };
  const m = useMutation({
    mutationFn: () => api.redirectIssue({ cabinetNos: rows!.map((c) => c.cabinetNo), agentNo: agent, siteNo: site || undefined,
      receiverNo: receiver || undefined, reason }),
    onSuccess: (r) => { notify.success(`已调整 ${r.length} 台`); reset(); onClose(); onDone(); },
  });
  return (
    <Drawer open={!!rows} onOpenChange={(o) => { if (!o) { reset(); onClose(); } }} title={`调整出库 ${rows?.length ?? 0} 台`}
      footer={<Button disabled={!agent || !reason.trim() || m.isPending} onClick={() => m.mutate()}>确认调整</Button>}>
      <Field label="商家">
        <Select aria-label="商家" value={agent} onChange={(e) => { setAgentNo(e.target.value); setSiteNo(""); }}>
          {(agentsQ.data?.list ?? []).filter((a) => a.status === "ENABLED" && !a.archivedAt)
            .map((a) => <option key={a.agentNo} value={a.agentNo}>{a.name}（{a.agentNo}）</option>)}
        </Select>
      </Field>
      <DestinationFields agentNo={agent} siteNo={site} onSite={setSiteNo} receiverNo={receiver} onReceiver={setReceiverNo} />
      <Field label="原因（必填）">
        <Textarea value={reason} onChange={setReason} />
      </Field>
      <div className="txt-caption text-muted-foreground">设备：{rows?.map((c) => c.cabinetNo).join("、")}</div>
    </Drawer>
  );
}
