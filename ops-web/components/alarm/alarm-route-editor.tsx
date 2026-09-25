"use client";

// 告警码 › 根因路由表（方案 §8.2「处置 → 根因路由表以子表编辑」）。
//
// 同一个业务告警，根因不同派的活不同：「站点借不到」离线要派维修、无宝要派补货。
// 一行 = 成因 → 处置方式 · 工单类型 · 优先级增减 · 兜底。`*` 行是该码的兜底路由。
//
// 与后端 saveRoutes 同一套校验（保存前在前端先拦一遍，免得点了才报错）：
//  · 至少一行；每行必须有处置方式；
//  · **同一成因不许两行** —— 命中哪条取决于顺序，那是最难查的一类配置错误；
//  · 工单类型只对「开工单」有意义，别的处置方式下禁用；优先级增减 −2…+2。
// 保存是整组覆盖，属有副作用的配置变更，先确认（R4）。
import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { HelpNote } from "@/components/ui/help-note";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { statusOptions } from "@/components/ui/status-badge";
import type { AlarmCode, AlarmRouteReq, AlarmDisposition, AlarmRouteCause } from "@/lib/types";
import { ALARM_CAUSE, ALARM_DISPOSITION } from "./alarm-maps";

const WO_TYPES = [
  { value: "FAULT", label: "故障维修" }, { value: "REFILL", label: "补货 / 取宝" }, { value: "INSPECT", label: "巡检" },
  { value: "CLEAN", label: "清洁" }, { value: "REMOVE", label: "撤机" },
];
const DELTAS = [-2, -1, 0, 1, 2];

type Row = { cause: AlarmRouteCause; disposition: AlarmDisposition | ""; woType: string; priorityDelta: number; fallback: AlarmDisposition | "" };

export function AlarmRouteEditor({ code, onClose }: { code: AlarmCode | null; onClose: () => void }) {
  const qc = useQueryClient();
  const allow = useCan();
  const canConfig = allow("workorder:alarm:config");
  const { confirm, dialog } = useConfirm();
  const [rows, setRows] = React.useState<Row[] | null>(null);

  const q = useQuery({
    queryKey: ["alarm", "routes", code?.code],
    queryFn: () => api.listAlarmRoutes(code!.code),
    enabled: !!code,
  });
  React.useEffect(() => {
    if (!q.data) return;
    setRows(q.data.map((r) => ({
      cause: (r.cause || "*") as AlarmRouteCause, disposition: r.disposition ?? "", woType: r.woType ?? "",
      priorityDelta: r.priorityDelta ?? 0, fallback: r.fallback ?? "",
    })));
  }, [q.data]);
  React.useEffect(() => { if (!code) setRows(null); }, [code]);

  const save = useMutation({
    mutationFn: (reqs: AlarmRouteReq[]) => api.saveAlarmRoutes(code!.code, reqs),
    onSuccess: () => {
      notify.success(`已保存 ${code!.code} 的根因路由`);
      qc.invalidateQueries({ queryKey: ["alarm"] });
      onClose();
    },
  });

  const list = rows ?? [];
  const causes = list.map((r) => r.cause);
  const dup = causes.find((c, i) => causes.indexOf(c) !== i);
  const missing = list.some((r) => !r.disposition);
  const problem = !list.length ? "至少保留一条路由——没有路由的码，告警产生了也不知道该干嘛"
    : missing ? "每一行都要选处置方式"
    : dup ? `成因「${dup === "*" ? "兜底" : ALARM_CAUSE[dup as keyof typeof ALARM_CAUSE]?.label ?? dup}」配了两行：命中哪行取决于顺序，请合并`
    : null;

  const patch = (i: number, p: Partial<Row>) => setRows((rs) => (rs ?? []).map((r, j) => (j === i ? { ...r, ...p } : r)));
  const onSave = async () => {
    if (problem || !code) return;
    const ok = await confirm({
      title: `保存 ${code.code} 的根因路由`,
      desc: `整组覆盖为 ${list.length} 条。只影响之后的处置，已开出的工单 / 待办不变。`,
      confirmText: "保存",
    });
    if (!ok) return;
    save.mutate(list.map((r) => ({
      cause: r.cause, disposition: r.disposition as AlarmDisposition,
      woType: r.disposition === "WORK_ORDER" ? r.woType || null : null,
      priorityDelta: r.priorityDelta, fallback: r.fallback || null,
    })));
  };

  const causeOptions = [{ value: "*", label: "* 兜底（其余成因）" }, ...statusOptions(ALARM_CAUSE)];
  const dispOptions = statusOptions(ALARM_DISPOSITION);

  return (
    <Drawer
      open={!!code}
      onOpenChange={(o) => !o && onClose()}
      title={`根因路由 · ${code?.message ?? ""}`}
      desc={code ? `${code.code} · 同一告警按根因派不同的活` : undefined}
      width="w-[min(100vw,760px)]"
      footer={code && canConfig && (
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button disabled={!!problem || save.isPending || !rows} onClick={onSave} title={problem ?? undefined}>保存路由</Button>
        </>
      )}
    >
      {!canConfig && <ReadOnlyNotice what="告警配置" perm="workorder:alarm:config" note="只能查看路由，不能修改" className="mb-4" />}
      {q.isLoading && <Skeleton className="h-32" />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {rows && (
        <div className="space-y-3">
          <div className="flex items-center gap-2 txt-caption text-muted-foreground">
            <span>命中顺序：先按告警的根因找对应行，没有再用「* 兜底」行；都没有时用告警码的首选处置。</span>
            <HelpNote>
              <p>优先级增减在告警算好的处置优先级上加减档（−2…+2，封顶紧急、保底低）。</p>
              <p className="mt-1">兜底处置：主路径走不通时改走它，例如开单失败（站点没有运维责任人）时改为仅通知。</p>
            </HelpNote>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full txt-body">
              <thead>
                <tr className="border-b border-border text-start txt-label text-muted-foreground">
                  <th className="py-2 pe-2 text-start">成因</th>
                  <th className="py-2 pe-2 text-start">处置方式</th>
                  <th className="py-2 pe-2 text-start">工单类型</th>
                  <th className="py-2 pe-2 text-start">优先级增减</th>
                  <th className="py-2 pe-2 text-start">兜底</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i} className="border-b border-border/60 last:border-0">
                    <td className="py-2 pe-2">
                      <Select aria-label="成因" disabled={!canConfig} value={r.cause}
                        onChange={(e) => patch(i, { cause: e.target.value as AlarmRouteCause })}>
                        {causeOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </Select>
                    </td>
                    <td className="py-2 pe-2">
                      <Select aria-label="处置方式" disabled={!canConfig} value={r.disposition}
                        onChange={(e) => patch(i, { disposition: e.target.value as AlarmDisposition })}>
                        <option value="">请选择</option>
                        {dispOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </Select>
                    </td>
                    <td className="py-2 pe-2">
                      <Select aria-label="工单类型" disabled={!canConfig || r.disposition !== "WORK_ORDER"} value={r.disposition === "WORK_ORDER" ? r.woType : ""}
                        title={r.disposition !== "WORK_ORDER" ? "只有「开工单」才需要工单类型" : undefined}
                        onChange={(e) => patch(i, { woType: e.target.value })}>
                        <option value="">默认（故障维修）</option>
                        {WO_TYPES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </Select>
                    </td>
                    <td className="py-2 pe-2">
                      <Select aria-label="优先级增减" disabled={!canConfig} value={String(r.priorityDelta)}
                        onChange={(e) => patch(i, { priorityDelta: Number(e.target.value) })}>
                        {DELTAS.map((d) => <option key={d} value={d}>{d > 0 ? `+${d}` : d === 0 ? "不变" : d}</option>)}
                      </Select>
                    </td>
                    <td className="py-2 pe-2">
                      <Select aria-label="兜底处置" disabled={!canConfig} value={r.fallback}
                        onChange={(e) => patch(i, { fallback: e.target.value as AlarmDisposition | "" })}>
                        <option value="">无</option>
                        {dispOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </Select>
                    </td>
                    <td className="py-2 text-end">
                      {canConfig && (
                        <Button size="sm" variant="ghost" onClick={() => setRows(rows.filter((_, j) => j !== i))}>移除</Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {rows.length === 0 && (
            <p className="txt-body text-muted-foreground">这个码还没有路由：告警会按码的首选处置执行。点「加一行」按根因分流。</p>
          )}
          {canConfig && (
            <Button size="sm" variant="outline"
              onClick={() => setRows([...rows, { cause: rows.some((r) => r.cause === "*") ? "OFFLINE" : "*", disposition: code?.business?.disposition ?? "", woType: "", priorityDelta: 0, fallback: "" }])}>
              加一行
            </Button>
          )}
          {problem && canConfig && <p className="txt-caption text-destructive-ink">{problem}</p>}
        </div>
      )}
      {dialog}
    </Drawer>
  );
}
