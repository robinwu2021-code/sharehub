"use client";

// 工作台快捷动作（F2 补齐）：看板原先只有「跳转卡」，看到问题得先跳页、再找行、再开抽屉。
// 这里把三个高频动作搬到页内 —— 远程弹出 / 开工单 / 查订单。
//
// 与被复用页面的关系：
// - 抽屉形态复用 ui/drawer + ui/form-drawer 两个原语，接口也是同一批（sendCommand /
//   createWorkOrder / listOrders），行为口径与 app/devices、app/work-orders、app/orders 一致；
// - **刻意没有从那三个页面里抽组件**：它们的抽屉都长在各自 tab 的 state 上（选中行、分页、
//   导出列…），抽出来要先拆那一堆上下文，风险远大于这里的一份最小实现。
// - 工作台没有行上下文，故每个动作自带目标选择器；机柜一律从台账列表选（不给自由文本，
//   避免把指令下发到不存在的柜子），订单则搜关键词后从命中结果里点选。
import * as React from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Radio, ClipboardPlus, Search } from "lucide-react";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import { money, fmtTime } from "@/lib/utils";
import type { Cabinet, RentOrder, WorkOrderDraft } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Drawer, Field } from "@/components/ui/drawer";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Input, Select } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Skeleton, EmptyState } from "@/components/ui/misc";
import { OrderStatusBadge, CabinetStatusBadge, OnlineBadge, WO_TYPE_LABEL } from "@/components/status";
import {
  findCabinet, parseSlotInput, ejectCommandType, ejectPrecheck, ejectConfirmDesc, orderKeywordReady,
} from "./quick-actions-logic";

/** 目标选择器共用：机柜下拉的数据源与设备台账同一份（size 200 与开单抽屉一致）。 */
function useCabinetOptions(enabled: boolean) {
  return useQuery({
    queryKey: ["quick-action-cabinets"],
    queryFn: () => api.listCabinets({ page: 1, size: 200 }),
    enabled,
  });
}

// —— 动作一：远程弹出 ——
function EjectDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const { confirm, dialog } = useConfirm();
  const [cabinetNo, setCabinetNo] = React.useState("");
  const [slot, setSlot] = React.useState("");
  const cabs = useCabinetOptions(open);
  const list: Cabinet[] = cabs.data?.list ?? [];

  React.useEffect(() => { if (!open) { setCabinetNo(""); setSlot(""); } }, [open]);

  const target = findCabinet(list, cabinetNo);
  const { slotIndex, bad } = parseSlotInput(slot);
  const check = ejectPrecheck(target, slotIndex, bad);

  const send = useMutation({
    mutationFn: (v: { no: string; slotIndex?: number }) =>
      api.sendCommand(v.no, ejectCommandType(v.slotIndex), v.slotIndex == null ? undefined : { slotIndex: v.slotIndex }),
    onSuccess: (r) => { notify.success(`弹出指令已下发：${r.commandId}，执行结果见设备 › 远程指令记录`); onOpenChange(false); },
  });

  const submit = async () => {
    if (!target || !check.ok) return;
    const ok = await confirm({
      title: `远程弹出 · ${target.cabinetNo}`,
      desc: ejectConfirmDesc(target, slotIndex, check.warn),
      danger: true, confirmText: "下发",
    });
    if (ok) send.mutate({ no: target.cabinetNo, slotIndex });
  };

  return (
    <>
      <Drawer
        open={open}
        onOpenChange={onOpenChange}
        title="远程弹出"
        desc="选目标机柜下发弹出指令，与设备详情页同一条指令通道"
        footer={
          <>
            <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
            <Button disabled={!check.ok || send.isPending} onClick={() => void submit()}>下发</Button>
          </>
        }
      >
        <div className="mb-4">
          <div className="mb-1 text-xs text-muted-foreground">目标机柜 <span className="text-destructive">*</span></div>
          {/* 只能选、不能打：柜号是硬件寻址主键，自由文本会把指令发到不存在的柜子上 */}
          <Select className="w-full" value={cabinetNo} onChange={(e) => setCabinetNo(e.target.value)}>
            <option value="">{cabs.isLoading ? "机柜列表加载中…" : "请选择机柜"}</option>
            {list.map((c) => (
              <option key={c.cabinetNo} value={c.cabinetNo}>
                {c.cabinetNo} · {c.locationName ?? "未上架"}
              </option>
            ))}
          </Select>
          <div className="mt-1 text-xs text-muted-foreground/70">与设备台账同一份数据</div>
        </div>

        <div className="mb-4">
          <div className="mb-1 text-xs text-muted-foreground">仓位号</div>
          <Input value={slot} placeholder="留空 = 任意可用仓位" onChange={(e) => setSlot(e.target.value)} />
          <div className="mt-1 text-xs text-muted-foreground/70">
            {target ? `该机柜共 ${target.slotTotal} 个仓位，在仓 ${target.availableCount} 个充电宝` : "选定机柜后显示仓位数"}
          </div>
        </div>

        {target && (
          <div className="grid grid-cols-2 gap-3 rounded-lg bg-muted/50 p-3">
            <Field label="点位" className="mb-0">{target.locationName ?? "未上架"}</Field>
            <Field label="型号" className="mb-0">{target.model}</Field>
            <Field label="机柜状态" className="mb-0"><CabinetStatusBadge s={target.status} /></Field>
            <Field label="在线" className="mb-0"><OnlineBadge s={target.onlineStatus} /></Field>
          </div>
        )}

        {/* 拦截原因与「离线会排队」的提醒都摊在按钮上方，不留到点了才报错 */}
        {!check.ok && <Notice className="mt-4 mb-0">{check.reason}</Notice>}
        {check.ok && check.warn && <Notice className="mt-4 mb-0">{check.warn}</Notice>}
      </Drawer>
      {dialog}
    </>
  );
}

// —— 动作二：开工单 ——
const NEW_WO: Partial<WorkOrderDraft> = { type: "FAULT", priority: "MEDIUM", description: "", source: "MANUAL" };
const WO_TYPE_OPTIONS = Object.entries(WO_TYPE_LABEL).map(([value, label]) => ({ value, label }));
const PRIO_OPTIONS = [
  { value: "LOW", label: "低" }, { value: "MEDIUM", label: "中" },
  { value: "HIGH", label: "高" }, { value: "URGENT", label: "紧急" },
];

function WorkOrderDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [form, setForm] = React.useState<Partial<WorkOrderDraft>>({ ...NEW_WO });
  const cabs = useCabinetOptions(open);

  React.useEffect(() => { if (open) setForm({ ...NEW_WO }); }, [open]);

  const fields: FieldDef[] = React.useMemo(() => {
    const list: Cabinet[] = cabs.data?.list ?? [];
    return [
      { key: "type", label: "工单类型", type: "select", options: WO_TYPE_OPTIONS, required: true, section: "基本信息" },
      {
        key: "cabinetNo", label: "机柜号", type: "select", required: true, section: "基本信息",
        options: [{ value: "", label: "请选择机柜" }, ...list.map((c) => ({ value: c.cabinetNo, label: `${c.cabinetNo} · ${c.locationName ?? "未上架"}` }))],
        help: cabs.isLoading ? "机柜列表加载中…" : "与设备台账同一份数据",
      },
      { key: "priority", label: "优先级", type: "select", options: PRIO_OPTIONS, required: true, section: "基本信息" },
      { key: "description", label: "问题描述", type: "textarea", rows: 4, required: true, maxLength: 200, section: "问题与时限", placeholder: "现象、影响面、已做过的排查" },
      { key: "expectedAt", label: "期望完成时间", type: "date", section: "问题与时限", help: "超期工单会在看板与 SLA 统计里标红" },
    ];
  }, [cabs.data, cabs.isLoading]);

  const create = useMutation({
    mutationFn: (v: WorkOrderDraft) => api.createWorkOrder(v),
    onSuccess: (w) => { notify.success(`工单 ${w.woNo} 已创建，待派单`); onOpenChange(false); },
  });

  return (
    <FormDrawer
      open={open}
      onOpenChange={onOpenChange}
      titleNew="开工单"
      titleEdit="开工单"
      isEdit={false}
      fields={fields}
      value={form as Record<string, unknown>}
      onChange={(v) => setForm(v as Partial<WorkOrderDraft>)}
      onSubmit={() => create.mutate(form as WorkOrderDraft)}
      submitting={create.isPending}
    />
  );
}

// —— 动作三：查订单 ——
function OrderLookupDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [keyword, setKeyword] = React.useState("");
  const [picked, setPicked] = React.useState<RentOrder | null>(null);

  React.useEffect(() => { if (!open) { setKeyword(""); setPicked(null); } }, [open]);

  const ready = orderKeywordReady(keyword);
  const q = useQuery({
    queryKey: ["quick-action-orders", keyword.trim()],
    queryFn: () => api.listOrders({ page: 1, size: 10, keyword: keyword.trim() }),
    enabled: open && ready,
  });
  const rows = q.data?.list ?? [];

  return (
    <Drawer open={open} onOpenChange={onOpenChange} title="查订单" desc="按订单号 / 用户号 / 柜机号检索，只读">
      <Input
        value={keyword}
        placeholder="订单号 / 用户号 / 柜机号"
        onChange={(e) => { setKeyword(e.target.value); setPicked(null); }}
      />
      {!ready ? (
        <Notice className="mt-4 mb-0">输入至少 2 个字符开始检索</Notice>
      ) : q.isLoading ? (
        <Skeleton className="mt-4 h-24" />
      ) : rows.length === 0 ? (
        <div className="mt-4"><EmptyState title="没有匹配的订单" desc="换个订单号或柜机号试试" /></div>
      ) : (
        <ul className="mt-4 space-y-1">
          {rows.map((o) => (
            <li key={o.orderNo}>
              {/* 命中结果点选即出详情：与订单页的详情抽屉同一批字段，但这里不带干预动作（看板只读） */}
              <button
                type="button"
                onClick={() => setPicked(o)}
                className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-start text-sm transition-colors hover:bg-accent ${picked?.orderNo === o.orderNo ? "bg-accent" : ""}`}
              >
                <span className="font-medium">{o.orderNo}</span>
                <OrderStatusBadge s={o.status} />
                <span className="ms-auto tabular-nums text-muted-foreground">{money(o.feeAmount, o.currency)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {picked && (
        <div className="mt-4 rounded-lg bg-muted/50 p-3">
          <div className="grid grid-cols-2 gap-3">
            <Field label="用户" className="mb-0">{picked.cUserNo}</Field>
            <Field label="状态" className="mb-0"><OrderStatusBadge s={picked.status} /></Field>
            <Field label="借出柜机 / 点位" className="mb-0">{picked.cabinetNo} · {picked.locationName ?? "-"}</Field>
            <Field label="归还柜机" className="mb-0">{picked.returnCabinetNo ?? "-"}</Field>
            <Field label="借出时间" className="mb-0">{picked.rentStartAt ? fmtTime(picked.rentStartAt) : "-"}</Field>
            <Field label="归还时间" className="mb-0">{picked.rentEndAt ? fmtTime(picked.rentEndAt) : "-"}</Field>
            <Field label="时长" className="mb-0">{picked.durationMin != null ? `${picked.durationMin} 分钟` : "-"}</Field>
            <Field label="费用 / 押金" className="mb-0">{money(picked.feeAmount, picked.currency)} / {money(picked.depositAmount, picked.currency)}</Field>
          </div>
          {/* 干预（弹出/免单/补偿/退款）一律回订单页做，权限与留痕都在那边。
              订单页已读 ?keyword= 深链：点开即预填该单号，落地就是筛好的那一单。 */}
          <Link
            href={`/orders?tab=list&keyword=${encodeURIComponent(picked.orderNo)}`}
            className="text-sm text-primary hover:underline"
            onClick={() => onOpenChange(false)}
          >
            去订单页做干预 →
          </Link>
        </div>
      )}
    </Drawer>
  );
}

/**
 * 工作台快捷动作按钮组。三个动作各按底层操作真实需要的权限码显示：
 * 没权限就不出按钮（而不是点进去再 404/报错）；三个都没有时整组不渲染。
 */
export function QuickActions() {
  const allow = useCan();
  const canEject = allow("device:command:send");
  const canWo = allow("workorder:wo:create");
  const canOrder = allow("order:order:read");
  const [openAct, setOpenAct] = React.useState<"eject" | "wo" | "order" | null>(null);

  if (!canEject && !canWo && !canOrder) return null;

  return (
    <div className="flex gap-2">
      {canEject && (
        <Button size="sm" variant="outline" onClick={() => setOpenAct("eject")}><Radio className="size-4" /> 远程弹出</Button>
      )}
      {canWo && (
        <Button size="sm" variant="outline" onClick={() => setOpenAct("wo")}><ClipboardPlus className="size-4" /> 开工单</Button>
      )}
      {canOrder && (
        <Button size="sm" variant="outline" onClick={() => setOpenAct("order")}><Search className="size-4" /> 查订单</Button>
      )}
      {canEject && <EjectDrawer open={openAct === "eject"} onOpenChange={(o) => setOpenAct(o ? "eject" : null)} />}
      {canWo && <WorkOrderDrawer open={openAct === "wo"} onOpenChange={(o) => setOpenAct(o ? "wo" : null)} />}
      {canOrder && <OrderLookupDrawer open={openAct === "order"} onOpenChange={(o) => setOpenAct(o ? "order" : null)} />}
    </div>
  );
}
