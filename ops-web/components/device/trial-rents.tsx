"use client";

// 试借还（上线门禁里最硬的一关）：**真弹一个宝出来、再真还回去**，才算证明了弹仓与回收都通。
// 只查配置不试一次的话，第一个真实用户就是试验品，而那时现场已经没人了。
//
// 发起后的推进（已弹出 → 等归还 → 通过 / 失败 / 超时）全由设备回执与仓位识别驱动，
// 运营端只有「发起」一个动作；页面在进行中时轮询，结果出来门禁自动变绿。
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { Cabinet, TrialRent } from "@/lib/types";
import { TRIAL_STATUS } from "./device-maps";

const IN_PROGRESS = ["EJECTING", "WAIT_RETURN"];

const cols: Column<TrialRent>[] = [
  { header: "试借还单号", cell: (t) => <span className="tabular-nums">{t.trialNo}</span> },
  { header: "状态", cell: (t) => <StatusBadge map={TRIAL_STATUS} value={t.status} /> },
  {
    header: "弹出的宝",
    cell: (t) => (
      <span className="tabular-nums">
        {t.powerbankNo ?? "-"}
        {t.slotIndex != null && <span className="text-muted-foreground"> · {t.slotIndex} 号仓</span>}
      </span>
    ),
  },
  { header: "弹出时间", cell: (t) => <span className="text-muted-foreground">{fmtTime(t.ejectedAt)}</span> },
  { header: "归还时间", cell: (t) => <span className="text-muted-foreground">{fmtTime(t.returnedAt)}</span> },
  { header: "失败原因", cell: (t) => (t.failReason ? <span className="txt-caption text-destructive-ink">{t.failReason}</span> : <span className="text-muted-foreground">-</span>) },
  { header: "发起人", cell: (t) => <span className="text-muted-foreground">{t.operator ?? "-"}</span> },
  { header: "发起时间", cell: (t) => <span className="text-muted-foreground">{fmtTime(t.createdAt)}</span> },
];

/**
 * mock 期「模拟设备回推」（方案 §6.1：L0 真实执行依赖接入网关，mock 期模拟成功 / 失败）。
 * 真后端下试借还只能由设备回执推进，这两个按钮**只在 mock 模式渲染**；
 * mock db 按需动态加载，不进真后端模式的首屏依赖。
 */
function MockFinish({ trial, onDone }: { trial: TrialRent; onDone: () => void }) {
  if (!IS_MOCK || trial.status !== "WAIT_RETURN") return null;
  const finish = async (pass: boolean) => {
    const dg = await import("@/lib/mock/db/device-gate");
    dg.finishTrialRent(trial.trialNo, pass, pass ? undefined : "模拟：归还时仓位没识别到宝");
    onDone();
  };
  return (
    <div className="flex gap-1.5">
      <Button size="sm" variant="outline" onClick={() => void finish(true)}>模拟归还成功</Button>
      <Button size="sm" variant="outline" onClick={() => void finish(false)}>模拟失败</Button>
    </div>
  );
}

export function TrialRentPanel({ cabinet }: { cabinet: Cabinet }) {
  const qc = useQueryClient();
  const canSend = useCan()("device:command:send");
  const no = cabinet.cabinetNo;
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["trial-rents", no],
    queryFn: () => api.listTrialRents(no),
    // 进行中时每 5 秒拉一次：结果由设备回推，人盯着页面等的就是这一下
    refetchInterval: (q) => (q.state.data ?? []).some((t) => IN_PROGRESS.includes(t.status)) ? 5000 : false,
  });
  const running = (data ?? []).find((t) => IN_PROGRESS.includes(t.status));

  const start = useMutation({
    mutationFn: () => api.startTrialRent(no),
    onSuccess: (t) => {
      qc.invalidateQueries({ queryKey: ["trial-rents", no] });
      qc.invalidateQueries({ queryKey: ["go-live-gate", no] });
      notify.success(`已下发弹出指令（${t.trialNo}）：请在现场取出 ${t.powerbankNo ?? "弹出的宝"}，再原样还回任意空仓`);
    },
  });

  const blocked = cabinet.onlineStatus !== "ONLINE"
    ? "设备离线：弹出指令发不下去，先让设备上线"
    : running ? `还有一次试借还没结束（${TRIAL_STATUS[running.status].label}）`
      : null;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="txt-caption text-muted-foreground">
          发起后系统弹出电量最高的一块宝；现场取出再还回，设备识别到这块宝即判通过。15 分钟未完成判超时。
        </div>
        {canSend && (
          <Button size="sm" disabled={!!blocked || start.isPending} title={blocked ?? undefined} onClick={() => start.mutate()}>
            {start.isPending ? "下发中…" : "发起试借还"}
          </Button>
        )}
      </div>
      {!canSend && <ReadOnlyNotice what="指令下发" perm="device:command:send" note="试借还要真弹一个宝，需要该权限" />}
      {canSend && blocked && <Notice className="mb-0">{blocked}</Notice>}
      <DataTable
        rowKey={(t: TrialRent) => t.trialNo}
        columns={IS_MOCK ? [...cols, {
          header: "模拟回推",
          cell: (t: TrialRent) => (
            <MockFinish trial={t} onDone={() => {
              qc.invalidateQueries({ queryKey: ["trial-rents", no] });
              qc.invalidateQueries({ queryKey: ["go-live-gate", no] });
            }} />
          ),
        }] : cols}
        rows={data}
        loading={isLoading}
        error={error}
        onRetry={refetch}
        empty="还没做过试借还——上线门禁要求至少一次成功的试借还；换过点位的柜子要重做"
      />
    </div>
  );
}
