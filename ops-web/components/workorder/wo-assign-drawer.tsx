"use client";

// 派单 / 平台接管抽屉（方案 §8.4「派单」）。
//
// 候选人从后端取（站点运维责任人置顶 → 该站运营代理 → OPS 角色员工），**替换写死的 STAFF**：
// 此前运营得自己记「哪个站归谁」，记错了单子就派到另一个城市。
// 默认预选责任人并标注「责任人」；候选人多时可按姓名 / 编号过滤。
//
// 接管模式：只能转给平台员工（后端 takeover 只收 employeeNo），可留空让后端按站点责任人 / 区域负载选；
// 原因后端可空，这里要求必填 —— 它写进时间线，被接管的代理看得到为什么换人。
import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { cn } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { WO_TYPE_LABEL } from "@/components/status";
import { SlaRemain } from "./sla-remain";
import type { AssigneeCandidate, WorkOrder } from "@/lib/types";

const CAND_TYPE: StatusMap<string> = {
  EMPLOYEE: { label: "员工", tone: "muted" },
  AGENT: { label: "代理", tone: "info" },
};
const OWNER: StatusMap<"OWNER"> = { OWNER: { label: "责任人", tone: "success" } };
/** 接管模式的「不指定」选项。 */
const AUTO = "";

export function WoAssignDrawer({
  wo, mode, onClose, onDone,
}: {
  wo: WorkOrder | null;
  mode: "dispatch" | "takeover";
  onClose: () => void;
  onDone: (msg: string) => void;
}) {
  const siteNo = wo?.ops?.siteNo ?? undefined;
  const cands = useQuery({
    queryKey: ["wo-candidates", siteNo ?? ""],
    queryFn: () => api.assigneeCandidates(siteNo),
    enabled: !!wo,
  });
  const [pick, setPick] = React.useState<string>(AUTO);
  const [kw, setKw] = React.useState("");
  const [reason, setReason] = React.useState("");

  const list = React.useMemo(() => {
    const all = cands.data ?? [];
    return mode === "takeover" ? all.filter((c) => c.type === "EMPLOYEE") : all;
  }, [cands.data, mode]);

  // 打开 / 换单时复位；派单默认预选责任人（没有就第一位），接管默认「自动」
  React.useEffect(() => {
    if (!wo) return;
    setKw(""); setReason("");
    setPick(mode === "takeover" ? AUTO : (list.find((c) => c.siteOwner) ?? list[0])?.no ?? AUTO);
  }, [wo, mode, list]);

  const shown = kw.trim()
    ? list.filter((c) => `${c.name} ${c.no}`.toLowerCase().includes(kw.trim().toLowerCase()))
    : list;

  const run = useMutation({
    mutationFn: () =>
      mode === "dispatch"
        ? api.dispatchWorkOrder(wo!.woNo, pick)
        : api.takeoverWorkOrder(wo!.woNo, { employeeNo: pick || null, reason: reason.trim() }),
    onSuccess: (r) => onDone(mode === "dispatch"
      ? `已派单给 ${nameOf(list, pick)}`
      : `已由平台接管，改派给 ${r.assigneeName ?? "平台运维"}`),
  });

  const canSubmit = mode === "dispatch" ? !!pick : !!reason.trim();

  return (
    <Drawer
      open={!!wo}
      onOpenChange={(o) => !o && onClose()}
      title={`${mode === "dispatch" ? "派单" : "平台接管"} ${wo?.woNo ?? ""}`}
      desc={wo ? `${WO_TYPE_LABEL[wo.type] ?? wo.type} · ${wo.description}` : ""}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button disabled={run.isPending || !canSubmit} onClick={() => run.mutate()}>
            {mode === "dispatch" ? "确认派单" : "确认接管"}
          </Button>
        </>
      }
    >
      {wo && (
        <>
          <Field label="柜机 / 点位">{wo.cabinetNo ?? "-"} · {wo.locationName ?? "-"}</Field>
          {mode === "takeover" && (
            <Field label="当前承接 / SLA">
              代理 {wo.assigneeName ?? "-"} · <SlaRemain w={wo} />
            </Field>
          )}
          {wo.rejectReason && <Field label="上次驳回原因">{wo.rejectReason}（已驳回 {wo.rejectCount ?? 1} 次）</Field>}

          <Field label={mode === "dispatch" ? "指派给" : "接管人（平台员工）"}>
            {(list.length > 8) && (
              <Input className="mb-2" value={kw} placeholder="按姓名 / 编号过滤" onChange={(e) => setKw(e.target.value)} />
            )}
            {cands.isLoading ? (
              <span className="text-muted-foreground">候选人加载中…</span>
            ) : cands.error ? (
              <span className="text-destructive-ink">候选人取不到（{cands.error instanceof Error ? cands.error.message : "网络错误"}），稍后重开抽屉再试</span>
            ) : (
              <ul role="radiogroup" className="max-h-80 space-y-1 overflow-y-auto">
                {mode === "takeover" && (
                  <CandRow checked={pick === AUTO} onPick={() => setPick(AUTO)}
                    title="自动选择" sub="站点员工责任人优先，没有则按区域负载选平台运维" />
                )}
                {shown.map((c) => (
                  <CandRow key={c.no} checked={pick === c.no} onPick={() => setPick(c.no)}
                    title={c.name} sub={c.no}
                    tags={<>
                      {c.siteOwner && <StatusBadge map={OWNER} value="OWNER" />}
                      <StatusBadge map={CAND_TYPE} value={c.type} />
                    </>} />
                ))}
                {shown.length === 0 && (
                  <li className="px-1 py-3 txt-caption text-muted-foreground">
                    {kw.trim()
                      ? "没有匹配的候选人——换个关键字，或清空过滤看全部"
                      : "没有可派的人：该站点未设运维责任人，也没有在职的运维（OPS）员工；请先在站点档案里指定责任人"}
                  </li>
                )}
              </ul>
            )}
          </Field>

          {mode === "takeover" && (
            <Field label="接管原因（必填）">
              <Textarea rows={3} value={reason} onChange={setReason} placeholder="如：代理超时 3 小时未到场，用户多次投诉" />
            </Field>
          )}
        </>
      )}
    </Drawer>
  );
}

const nameOf = (list: AssigneeCandidate[], no: string) => {
  const c = list.find((x) => x.no === no);
  return c ? `${c.name}（${c.no}）` : no;
};

function CandRow({
  checked, onPick, title, sub, tags,
}: { checked: boolean; onPick: () => void; title: string; sub: string; tags?: React.ReactNode }) {
  return (
    <li>
      <button
        type="button"
        role="radio"
        aria-checked={checked}
        onClick={onPick}
        className={cn(
          "flex w-full items-center gap-3 rounded-field px-3 py-2 text-start transition-colors",
          "hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          checked && "bg-accent-subtle ring-1 ring-primary",
        )}
      >
        <span className={cn("size-3.5 shrink-0 rounded-chip border", checked ? "border-primary bg-primary" : "border-border-strong")} />
        <span className="min-w-0 flex-1">
          <span className={cn("block truncate", checked ? "txt-strong" : "txt-body")}>{title}</span>
          <span className="block truncate txt-caption text-muted-foreground tabular-nums">{sub}</span>
        </span>
        <span className="flex shrink-0 items-center gap-1">{tags}</span>
      </button>
    </li>
  );
}
