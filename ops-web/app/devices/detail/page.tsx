"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Skeleton, EmptyState, ErrorState } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Tabs } from "@/components/ui/tabs";
import { Notice } from "@/components/ui/notice";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { StateActions } from "@/components/state-actions";
import { GateChecklist } from "@/components/gate-checklist";
import { RefLink } from "@/components/ref-link";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { useCabinetActions } from "@/components/device/cabinet-actions";
import { QcDrawer, QcRecords, type QcTarget } from "@/components/device/qc";
import { TrialRentPanel } from "@/components/device/trial-rents";
import { ProtectionPanel, useSlotProtections } from "@/components/device/protections";
import { SignalCodesTable } from "@/components/device/signal-codes";
import { PROTECTION_ACTION, QC_STATUS } from "@/components/device/device-maps";
import { fmtTime, cn } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import type { Cabinet, Checklist, Protection, Slot } from "@/lib/types";
import { ChevronLeft, RotateCw } from "lucide-react";

// 静态导出（output: export）用 query 参数而非动态段（对齐 ai-boss/ops-web 的 /detail 模式）。
// 页签也进 URL（`&tab=`）：门禁清单的「去处理」要能直接落到试借还 / 质检 / 仓位那一格。

const TABS = [
  { key: "overview", label: "概览" },
  { key: "slots", label: "仓位" },
  { key: "trial", label: "试借还" },
  { key: "protections", label: "保护" },
  { key: "qc", label: "入库质检" },
  { key: "signals", label: "设备信号" },
];
/** 后端 fixHref 里出现过、但本页不单列的页签 → 落到哪一格。 */
const TAB_ALIAS: Record<string, string> = { gate: "overview", edit: "overview" };

/** 生命周期步骤条：故障是**分支态**（修好还能回在用），不在主线上。 */
const STEPS = [
  { key: "IN_STOCK", label: "在库" },
  { key: "IN_TRANSIT", label: "运输中" },
  { key: "DEPLOYED", label: "已布放" },
  { key: "RETIRED", label: "退役" },
];

/** 仓位格的形状：有宝 / 空仓 / 故障 / 禁用 / 锁定。文字 + 色调两路表达，不只靠颜色（规范 §11.4）。 */
type SlotShape = "LOCKED" | "DISABLED" | "FAULT" | "FILLED" | "EMPTY";
const SLOT_SHAPE: StatusMap<SlotShape> = {
  LOCKED: { label: "锁定", tone: "danger" },
  DISABLED: { label: "禁用", tone: "warning" },
  FAULT: { label: "故障", tone: "danger" },
  FILLED: { label: "有宝", tone: "success" },
  EMPTY: { label: "空仓", tone: "muted" },
};
const shapeOf = (s: Slot, ps: Protection[]): SlotShape =>
  ps.some((p) => p.action === "SLOT_LOCK") ? "LOCKED"
    : ps.some((p) => p.action === "SLOT_DISABLE") ? "DISABLED"
      : s.health === "FAULT" ? "FAULT"
        : s.powerbankNo ? "FILLED" : "EMPTY";

/** 保护由谁施加：告警号可跳，信号只显示信号码，人工显示操作人。 */
function ProtectionSource({ p }: { p: Protection }) {
  return (
    <div className="truncate">
      {PROTECTION_ACTION[p.action]?.label ?? p.action} ·{" "}
      {p.holderType === "ALARM" ? <RefLink kind="alarm" no={p.holderRef} />
        : p.holderType === "SIGNAL" ? `信号 ${(p.holderRef ?? "").split(":")[0]}`
          : `人工 ${p.holderRef ?? ""}`}
    </div>
  );
}

function SlotsGrid({ cabinet, slots, canCmd, onEject }: {
  cabinet: Cabinet; slots: Slot[]; canCmd: boolean; onEject: (slot: number) => void;
}) {
  const { bySlot, whole } = useSlotProtections(cabinet.cabinetNo);
  if (slots.length === 0) {
    return <EmptyState title="没有仓位数据" desc="设备还没上报过仓位表——设备首次上线心跳后会自动出现；上线门禁的「装宝比例」也依赖它" />;
  }
  return (
    <div className="space-y-3">
      {whole.length > 0 && (
        <Notice className="mb-0 bg-warning-tint text-warning-ink">
          整柜保护生效中：{whole.map((p) => PROTECTION_ACTION[p.action]?.label ?? p.action).join("、")}（详见「保护」页签）
        </Notice>
      )}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {slots.map((s) => {
          const ps = bySlot.get(s.slotIndex) ?? [];
          const shape = shapeOf(s, ps);
          return (
            <div key={s.slotIndex} className="rounded-card bg-muted/60 p-3">
              <div className="flex items-center justify-between">
                <span className="txt-strong">仓位 {s.slotIndex}</span>
                <StatusBadge map={SLOT_SHAPE} value={shape} />
              </div>
              <div className="mt-2 space-y-0.5 txt-caption text-muted-foreground">
                <div>充电宝：{s.powerbankNo ?? "-"}</div>
                <div>电量：{s.battery != null ? `${s.battery}%` : "-"}</div>
                {ps.map((p) => <ProtectionSource key={p.protectionNo} p={p} />)}
              </div>
              {canCmd && (
                <Button className="mt-2 w-full" size="sm" variant="outline"
                  disabled={!s.powerbankNo || shape === "LOCKED"}
                  title={shape === "LOCKED" ? "仓位已锁定（安全隔离），不能弹出" : undefined}
                  onClick={() => onEject(s.slotIndex)}>
                  弹出
                </Button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Overview({ cabinet, gateQ, onQc }: {
  cabinet: Cabinet;
  gateQ: { data?: Checklist; isLoading: boolean };
  onQc: () => void;
}) {
  const canUpdate = useCan()("device:cabinet:update");
  const qcQ = useQuery({ queryKey: ["qc-records", cabinet.cabinetNo], queryFn: () => api.listQcRecords(cabinet.cabinetNo) });
  const lastQc = qcQ.data?.[0];
  return (
    <div className="space-y-6">
      <Card>
        <CardContent className="grid grid-cols-2 gap-4 pt-5 md:grid-cols-4">
          <Field label="点位" className="mb-0">{cabinet.locationName ?? "未上架"}</Field>
          {/* 归属站点（偏差 A1）：站点号 + 点位号一起给，排障时能一路查到站点与合同 */}
          <Field label="归属站点" className="mb-0">
            {cabinet.siteNo ? <RefLink kind="site" no={cabinet.siteNo} /> : "未归属"}
            {cabinet.locationNo && <span className="text-muted-foreground"> · {cabinet.locationNo}</span>}
          </Field>
          <Field label="供应商 / 型号" className="mb-0">{cabinet.vendorCode} · {cabinet.model}</Field>
          <Field label="SN" className="mb-0"><span className="tabular-nums">{cabinet.sn}</span></Field>
          <Field label="固件" className="mb-0">{cabinet.fwVersion}</Field>
          <Field label="在线" className="mb-0"><OnlineBadge s={cabinet.onlineStatus} /></Field>
          <Field label="可借 / 仓位" className="mb-0">{`${cabinet.availableCount} / ${cabinet.slotTotal}`}</Field>
          <Field label="最后心跳" className="mb-0">{fmtTime(cabinet.lastHeartbeatAt)}</Field>
          <Field label="归属代理" className="mb-0">{cabinet.agentNo ? <RefLink kind="agent" no={cabinet.agentNo} /> : "平台直营"}</Field>
          {/* 质检状态后端机柜出参没有，取最近一条质检记录；没有记录 = 质检上线前入库的存量，免检 */}
          <Field label="入库质检" className="mb-0">
            {lastQc ? <StatusBadge map={QC_STATUS} value={lastQc.result} /> : <span className="text-muted-foreground">无记录（存量免检）</span>}
          </Field>
        </CardContent>
      </Card>

      {cabinet.status === "IN_STOCK" ? (
        <Card>
          <CardHeader><CardTitle>上线门禁</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <GateChecklist
              data={gateQ.data}
              loading={gateQ.isLoading}
              passedHint="门禁全部通过，可以点右上角「上线」"
              blockedHint="还差 {n} 项，逐条处理后再上线；每条都给了去处"
            />
            {canUpdate && (
              <div className="flex justify-end">
                <Button size="sm" variant="outline" onClick={onQc}>做入库质检</Button>
              </div>
            )}
          </CardContent>
        </Card>
      ) : (
        <div className="txt-caption text-muted-foreground">
          {cabinet.status === "IN_TRANSIT" ? "运输中：随调拨单发出，签收后回到在库，届时再走上线门禁。"
            : cabinet.status === "RETIRED" ? "已报废：不能再上线、调拨或维修。"
              : "已上线：上线门禁只在在库时显示；撤机回库后需重新走一遍（含试借还）。"}
        </div>
      )}
    </div>
  );
}

const useGate = (cabinet: Cabinet | undefined) => useQuery({
  queryKey: ["go-live-gate", cabinet?.cabinetNo],
  queryFn: () => api.goLiveGate(cabinet!.cabinetNo),
  enabled: !!cabinet && cabinet.status === "IN_STOCK" && !cabinet.archivedAt,
});

function CabinetDetail() {
  const sp = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const cabinetNo = sp.get("no") ?? "";
  const rawTab = sp.get("tab") ?? "overview";
  const tab = TABS.some((t) => t.key === rawTab) ? rawTab : TAB_ALIAS[rawTab] ?? "overview";
  const setTab = (k: string) => {
    const q = new URLSearchParams(sp.toString());
    q.set("tab", k);
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
  };

  const qc = useQueryClient();
  const allow = useCan();
  const canCmd = allow("device:command:send");
  const [qcTarget, setQcTarget] = useState<QcTarget | null>(null);
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["cabinet", cabinetNo],
    queryFn: () => api.getCabinet(cabinetNo),
    enabled: !!cabinetNo,
  });
  const cabinet = data?.cabinet;
  const gateQ = useGate(cabinet);
  const { actions, drawers, confirm } = useCabinetActions(cabinet, gateQ.data);

  const cmd = useMutation({
    mutationFn: (v: { type: string; slotIndex?: number }) => api.sendCommand(cabinetNo, v.type, v.slotIndex ? { slotIndex: v.slotIndex } : undefined),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["devices"] });
      notify.success(`指令已下发：${r.commandId}（先记「已下发」，设备回执后转「已确认」，见指令记录）`);
    },
  });
  // 重启 / 弹出都有现场副作用（重启约 30 秒不可借还；弹出是真把宝推出来），一律先确认（R4）
  const ask = async (type: "REBOOT" | "EJECT", slotIndex?: number) => {
    const ok = await confirm(type === "REBOOT"
      ? { title: `重启 ${cabinetNo}`, desc: "重启期间约 30 秒不可借还。离线柜的指令会排队到上线后执行。", confirmText: "重启", danger: true }
      : { title: `弹出充电宝 · ${cabinetNo}`, desc: slotIndex ? `将弹出 ${slotIndex} 号仓的充电宝，请确认现场有人接。` : "将弹出任意一个可弹仓的充电宝，请确认现场有人接。", confirmText: "弹出", danger: true });
    if (ok) cmd.mutate({ type, slotIndex });
  };

  if (!cabinetNo) return <EmptyState title="缺少柜机号" desc="从设备台账点柜机号进入，或在地址里带上 ?no=CAB…" />;

  const status = cabinet?.status;
  return (
    <div>
      <Link href="/devices" className="mb-2 inline-flex items-center gap-1 txt-body text-muted-foreground hover:text-foreground">
        <ChevronLeft className="size-4" /> 返回设备列表
      </Link>

      {error ? <ErrorState error={error} onRetry={refetch} /> : isLoading || !cabinet ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <DetailHeader
            className="mb-5"
            no={cabinet.cabinetNo}
            title={cabinet.locationName ?? "未上架（在仓库）"}
            badge={<><CabinetStatusBadge s={cabinet.status} /><OnlineBadge s={cabinet.onlineStatus} /></>}
            meta={`${cabinet.vendorCode} · ${cabinet.model} · ${cabinet.slotTotal} 仓`}
            stepper={
              <StatusStepper
                steps={STEPS}
                current={status === "FAULT" ? "DEPLOYED" : status!}
                branch={status === "FAULT" ? { label: "故障（修复后回到已布放）", after: "DEPLOYED" } : null}
              />
            }
            actions={
              <>
                <Button size="sm" variant="outline" onClick={() => { void refetch(); void gateQ.refetch(); }}>
                  <RotateCw className="size-4" /> 刷新
                </Button>
                {canCmd && cabinet.status !== "RETIRED" && (
                  <>
                    <Button size="sm" variant="outline" disabled={cmd.isPending} onClick={() => void ask("REBOOT")}>重启</Button>
                    {/* 指令名走 types 层的 COMMAND_TYPES 词表：不带仓位 = 任意仓、带仓位 = 指定仓 */}
                    <Button size="sm" variant="outline" disabled={cmd.isPending} onClick={() => void ask("EJECT")}>弹出充电宝</Button>
                  </>
                )}
                <StateActions actions={actions} />
              </>
            }
          />
          {cabinet.archivedAt && (
            <Notice className="mb-4">这台柜子已归档（{fmtTime(cabinet.archivedAt)}）：只读，所有动作不可用。要操作请先在设备台账里恢复。</Notice>
          )}

          <Tabs tabs={TABS} value={tab} onChange={setTab} />
          <div className={cn(tab !== "overview" && "min-h-40")}>
            {tab === "overview" && (
              <Overview cabinet={cabinet} gateQ={gateQ} onQc={() => setQcTarget({ itemType: "CABINET", itemNo: cabinet.cabinetNo })} />
            )}
            {tab === "slots" && (
              <SlotsGrid cabinet={cabinet} slots={data!.slots} canCmd={canCmd} onEject={(i) => void ask("EJECT", i)} />
            )}
            {tab === "trial" && <TrialRentPanel cabinet={cabinet} />}
            {tab === "protections" && <ProtectionPanel cabinet={cabinet} />}
            {tab === "qc" && (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="txt-caption text-muted-foreground">
                    入库质检只在在库时做；质检未过的柜子不能发货调拨，也过不了上线门禁。
                  </div>
                  {allow("device:cabinet:update") && (
                    <Button size="sm" disabled={cabinet.status !== "IN_STOCK"}
                      title={cabinet.status !== "IN_STOCK" ? "只有在库的柜子能做入库质检；已布放的出问题走标记故障 / 修复" : undefined}
                      onClick={() => setQcTarget({ itemType: "CABINET", itemNo: cabinet.cabinetNo })}>
                      做入库质检
                    </Button>
                  )}
                </div>
                <QcRecords itemNo={cabinet.cabinetNo} />
              </div>
            )}
            {tab === "signals" && (
              <div className="space-y-3">
                <div className="txt-caption text-muted-foreground">
                  设备上报的信号码与系统的自动止损动作。信号不是告警：它触发的是「保护」页签里的停借 / 禁仓 / 锁仓，业务影响由告警中心按站点汇总。
                </div>
                <SignalCodesTable />
              </div>
            )}
          </div>
          {drawers}
          <QcDrawer target={qcTarget} onClose={() => setQcTarget(null)} />
        </>
      )}
    </div>
  );
}

export default function CabinetDetailPage() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <CabinetDetail />
    </Suspense>
  );
}
