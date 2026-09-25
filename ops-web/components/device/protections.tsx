"use client";

// 保护动作（设备层止损）：停借 / 禁仓 / 锁仓 / 降功率。
//
// 保护是**可叠加的约束，不是状态**：同一台柜子可能同时挂着信号触发的锁仓与人工挂的停借。
// 解除按**持有方**判权 —— 信号 / 告警挂的由系统在条件恢复时自己撤，人工挂的才允许人工撤。
// 不分持有方的话，运维手一抖把告警挂的停借解了，设备会在故障未恢复时重新接客。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge } from "@/components/ui/status-badge";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { RefLink } from "@/components/ref-link";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { Cabinet, Protection, ProtectionAction } from "@/lib/types";
import { HOLDER_TYPE, PROTECTION_ACTION, PROTECTION_HINT } from "./device-maps";
import { ReasonDrawer, type ReasonRequest } from "./reason-drawer";

const WHOLE_CABINET: ProtectionAction[] = ["STOP_RENT", "DERATE"];

/**
 * 人工挂保护。整柜级动作（停借 / 降功率）不需要仓位号，仓位级的必须给 —— 与后端同一条规则。
 *
 * @form POST /api/ops/devices/{deviceNo}/protections
 */
const protectionFields = (slotTotal: number): FieldDef[] => [
  { key: "action", label: "保护动作", type: "select", required: true,
    options: (Object.keys(PROTECTION_ACTION) as ProtectionAction[])
      .map((a) => ({ value: a, label: `${PROTECTION_ACTION[a].label} —— ${PROTECTION_HINT[a]}` })) },
  { key: "slotIndex", label: "仓位号", type: "number", required: true, min: 1, max: slotTotal,
    disabledWhen: (v) => WHOLE_CABINET.includes(v.action as ProtectionAction),
    help: `仓位禁用 / 锁定必填（1~${slotTotal}）；整柜停借与降功率不看仓位` },
  { key: "reason", label: "原因", type: "textarea", required: true, maxLength: 512,
    help: "写清为什么挂、什么情况下可以解 —— 没有原因的保护没人敢解" },
];

/** 持有方说明：告警号可跳；信号的 holderRef 是「信号码:柜:仓」内部键，只显示信号码。 */
function Holder({ p }: { p: Protection }) {
  return (
    <div className="flex flex-col gap-0.5">
      <StatusBadge map={HOLDER_TYPE} value={p.holderType} />
      <span className="txt-caption text-muted-foreground">
        {p.holderType === "ALARM" ? <RefLink kind="alarm" no={p.holderRef} />
          : p.holderType === "SIGNAL" ? (p.holderRef ?? "").split(":")[0] || "-"
            : p.holderRef ?? "-"}
      </span>
    </div>
  );
}

export function ProtectionPanel({ cabinet }: { cabinet: Cabinet }) {
  const qc = useQueryClient();
  const canUpdate = useCan()("device:cabinet:update");
  const no = cabinet.cabinetNo;
  const [activeOnly, setActiveOnly] = useState(true);
  const [form, setForm] = useState<Record<string, unknown> | null>(null);
  const [reasonReq, setReasonReq] = useState<ReasonRequest | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["protections", no, activeOnly],
    queryFn: () => api.listProtections(no, activeOnly),
  });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["protections", no] });
    qc.invalidateQueries({ queryKey: ["go-live-gate", no] });
  };

  const apply = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.applyProtection(no, {
      action: v.action as ProtectionAction,
      slotIndex: WHOLE_CABINET.includes(v.action as ProtectionAction) ? null : Number(v.slotIndex),
      reason: String(v.reason ?? "").trim(),
    }),
    onSuccess: (p) => {
      invalidate();
      notify.success(`已挂「${PROTECTION_ACTION[p.action].label}」${p.slotIndex ? `（${p.slotIndex} 号仓）` : ""}`);
      setForm(null);
    },
  });
  const release = useMutation({
    mutationFn: (v: { protectionNo: string; reason: string }) => api.releaseProtection(v.protectionNo, v.reason),
    onSuccess: () => { invalidate(); notify.success("已解除"); },
  });

  const cols: Column<Protection>[] = [
    { header: "动作", cell: (p) => <StatusBadge map={PROTECTION_ACTION} value={p.action} /> },
    { header: "范围", cell: (p) => <span className="tabular-nums">{p.slotIndex == null ? "整柜" : `${p.slotIndex} 号仓`}</span> },
    { header: "持有方", cell: (p) => <Holder p={p} /> },
    { header: "原因", cell: (p) => <span className="txt-caption">{p.reason ?? "-"}</span> },
    { header: "挂上时间", cell: (p) => <span className="text-muted-foreground">{fmtTime(p.createdAt)}</span> },
    {
      header: "状态",
      cell: (p) => (p.active
        ? <span className="txt-caption">生效中</span>
        : <span className="txt-caption text-muted-foreground" title={p.releaseReason ?? undefined}>已解除 · {fmtTime(p.releasedAt)}</span>),
    },
    {
      header: "操作",
      cell: (p) => {
        if (!p.active) return <span className="text-muted-foreground">-</span>;
        // 非人工持有的不给解除按钮，而是说清谁会撤它 —— 按钮禁用不说原因，人会去找开发
        if (p.holderType !== "MANUAL") {
          return <span className="txt-caption text-muted-foreground">条件恢复后由系统自动解除</span>;
        }
        return (
          <Button
            size="sm" variant="outline"
            disabled={!canUpdate}
            title={canUpdate ? undefined : "无权限（需要 device:cabinet:update）"}
            onClick={() => setReasonReq({
              title: `解除保护 ${p.protectionNo}`,
              help: `解除后${p.slotIndex == null ? "整柜" : `${p.slotIndex} 号仓`}恢复${p.action === "DERATE" ? "全功率" : "借还"}。写清依据（例如「现场复测正常」）`,
              onSubmit: (reason) => release.mutateAsync({ protectionNo: p.protectionNo, reason }),
            })}
          >
            解除
          </Button>
        );
      },
    },
  ];

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className={segmentedTrackClass()} role="radiogroup" aria-label="保护范围">
          {([[true, "生效中"], [false, "含已解除"]] as const).map(([v, label]) => (
            <button key={label} type="button" role="radio" aria-checked={activeOnly === v}
              className={segmentedItemClass(activeOnly === v, "px-3 py-1 text-sm")} onClick={() => setActiveOnly(v)}>
              {label}
            </button>
          ))}
        </div>
        {canUpdate && (
          <Button size="sm" onClick={() => setForm({ action: "STOP_RENT", slotIndex: "", reason: "" })}>人工挂保护</Button>
        )}
      </div>
      {!canUpdate && <ReadOnlyNotice what="机柜维护" perm="device:cabinet:update" note="不能挂或解除保护" />}
      <DataTable
        rowKey={(p: Protection) => p.protectionNo}
        columns={cols}
        rows={data}
        loading={isLoading}
        error={error}
        onRetry={refetch}
        empty={activeOnly
          ? "当前没有生效的保护——这台柜子没有被停借、禁仓或降功率；设备信号与业务告警触发时会自动挂上"
          : "这台柜子从没挂过保护"}
      />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew={`人工挂保护 · ${no}`}
        titleEdit={`人工挂保护 · ${no}`}
        isEdit={false}
        fields={protectionFields(cabinet.slotTotal)}
        value={form ?? {}}
        onChange={setForm}
        onSubmit={() => form && apply.mutate(form)}
        submitting={apply.isPending}
      />
      <ReasonDrawer req={reasonReq} onClose={() => setReasonReq(null)} />
    </div>
  );
}

/** 生效中的仓位级保护：slotIndex → 保护列表（仓位网格上标形状与来源用）。 */
export function useSlotProtections(cabinetNo: string) {
  const { data } = useQuery({
    queryKey: ["protections", cabinetNo, true],
    queryFn: () => api.listProtections(cabinetNo, true),
    enabled: !!cabinetNo,
  });
  const bySlot = new Map<number, Protection[]>();
  const whole: Protection[] = [];
  for (const p of data ?? []) {
    if (p.slotIndex == null) whole.push(p);
    else bySlot.set(p.slotIndex, [...(bySlot.get(p.slotIndex) ?? []), p]);
  }
  return { bySlot, whole };
}
