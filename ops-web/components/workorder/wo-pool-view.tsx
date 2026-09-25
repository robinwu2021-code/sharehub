"use client";

// 抢单池（后端 pool / grab，E3）：还没派出的工单，现场运维自己挑着接。
// 抢单 = 派给自己 + 接单（CREATED → ACCEPTED），两人同时抢只一人成功，另一人收到「已不在池中」。
// 数据范围由后端按区域收敛：区域员工只看见本区域的池。
// 池子本身就要 workorder:wo:handle 才能看（后端 pool 端点的码），所以页面只对持码人露出这个视图。
import * as React from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { StateActions } from "@/components/state-actions";
import { WO_TYPE_LABEL } from "@/components/status";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { WorkOrder } from "@/lib/types";
import { PRIO } from "./wo-meta";
import { SlaRemain } from "./sla-remain";

const TYPE_OPTIONS = Object.entries(WO_TYPE_LABEL).map(([value, label]) => ({ value, label }));

export function WoPoolView({ onOpen, onChanged }: { onOpen: (woNo: string) => void; onChanged: () => void }) {
  const qc = useQueryClient();
  const paging = usePaging();
  const [type, setType] = React.useState("");
  const q = useQuery({
    queryKey: ["wo-pool", paging.page, paging.size, type],
    queryFn: () => api.listWoPool({ page: paging.page, size: paging.size, type: type || undefined }),
    placeholderData: keepPreviousData,
  });
  const grab = useMutation({
    mutationFn: (no: string) => api.grabWorkOrder(no),
    onSuccess: (w) => {
      notify.success(`已抢到 ${w.woNo}，已派给你并接单；到场后提交处理`);
      qc.invalidateQueries({ queryKey: ["wo-pool"] });
      onChanged();
    },
    // 被别人先抢走是常态，不是故障：刷新池子让那一行消失
    onError: () => qc.invalidateQueries({ queryKey: ["wo-pool"] }),
  });

  const cols: Column<WorkOrder>[] = [
    {
      header: "工单号",
      cell: (w) => (
        <button type="button" className="txt-strong tabular-nums underline-offset-2 hover:underline" onClick={() => onOpen(w.woNo)}>{w.woNo}</button>
      ),
    },
    { header: "类型", cell: (w) => WO_TYPE_LABEL[w.type] ?? w.type },
    { header: "优先级", cell: (w) => <StatusBadge map={PRIO} value={w.priority} className="whitespace-nowrap" /> },
    { header: "柜机 / 点位", cell: (w) => <span className="text-muted-foreground">{w.cabinetNo ?? "-"} · {w.locationName ?? "-"}</span> },
    { header: "描述", cell: (w) => <span className="line-clamp-2">{w.description}</span> },
    { header: "SLA 剩余", cell: (w) => <SlaRemain w={w} /> },
    { header: "创建", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.createdAt)}</span> },
    {
      header: "操作",
      cell: (w) => (
        <div className="flex items-center gap-1.5">
          <StateActions actions={[{
            key: "grab", label: "抢单", primary: true, perm: "workorder:wo:handle",
            blockedReason: grab.isPending ? "处理中…" : null,
            confirm: {
              title: `抢单 ${w.woNo}`,
              desc: `${WO_TYPE_LABEL[w.type] ?? w.type} · ${w.locationName ?? w.cabinetNo ?? ""}。抢到后直接派给你并算作已接单，SLA 响应计时到此为止；之后要自己到场处理。`,
              confirmText: "确认抢单",
            },
            onRun: () => grab.mutate(w.woNo),
          }]} />
          <Button size="sm" variant="outline" onClick={() => onOpen(w.woNo)}>详情</Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <Toolbar>
        <FilterSelect value={type} onChange={(v) => { setType(v); paging.reset(); }}
          allLabel="全部类型" options={TYPE_OPTIONS} aria-label="按工单类型筛选抢单池" />
      </Toolbar>
      <PagedTable
        rowKey={(w: WorkOrder) => w.woNo}
        columns={cols}
        query={q}
        paging={paging}
        empty="抢单池是空的——所有工单都已派出。新开的单若没有站点责任人可自动派，会出现在这里。"
      />
    </>
  );
}
