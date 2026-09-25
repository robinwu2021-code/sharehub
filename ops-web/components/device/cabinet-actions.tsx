"use client";

// 机柜生命周期动作（方案 §6.1 · 规则 R1/R4）：上线 / 标记故障 / 修复 / 撤机 / 报废。
//
// - 哪个动作出现由 CABINET_TRANSITIONS 决定（与后端 CabinetStateMachine 逐边一致），页面不手写 if。
// - 上线另有门禁：门禁没过时按钮**禁用并写明还差哪几项**，而不是隐藏 —— 隐藏的话人不知道这台柜子离上线还差什么。
// - 标故障 / 撤机 / 报废后端都要原因，走写原因抽屉；报废不可逆，先输柜机号确认（requireText）。
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ActionSpec } from "@/components/state-actions";
import { notify } from "@/lib/notify";
import { canCabinetAction, type Cabinet, type Checklist } from "@/lib/types";
import { ReasonDrawer, type ReasonRequest } from "./reason-drawer";

const PERM = "device:cabinet:update";

export function useCabinetActions(cabinet: Cabinet | undefined, gate: Checklist | undefined) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [reasonReq, setReasonReq] = useState<ReasonRequest | null>(null);
  const no = cabinet?.cabinetNo ?? "";

  const done = (msg: string) => {
    qc.invalidateQueries({ queryKey: ["cabinet", no] });
    qc.invalidateQueries({ queryKey: ["go-live-gate", no] });
    qc.invalidateQueries({ queryKey: ["protections", no] });
    qc.invalidateQueries({ queryKey: ["cabinets"] });
    notify.success(msg);
  };

  const goLive = useMutation({ mutationFn: () => api.goLive(no), onSuccess: () => done(`${no} 已上线，开始接客`) });
  const repair = useMutation({ mutationFn: () => api.repairDevice(no), onSuccess: () => done(`${no} 已修复，恢复在用`) });
  const markFault = useMutation({
    mutationFn: (reason: string) => api.markDeviceFault(no, reason),
    onSuccess: () => done(`${no} 已标记故障`),
  });
  const undeploy = useMutation({
    mutationFn: (reason: string) => api.undeployDevice(no, reason),
    onSuccess: () => done(`${no} 已撤机回库：点位与站点已解绑，再上线需重做试借还`),
  });
  const retire = useMutation({
    mutationFn: (reason: string) => api.retireDevice(no, reason),
    onSuccess: () => done(`${no} 已报废`),
  });

  if (!cabinet) return { actions: [] as ActionSpec[], drawers: dialog, confirm };
  const s = cabinet.status;
  const archived = cabinet.archivedAt ? "已归档的设备只读：先在台账里恢复" : null;
  const pending = gate?.items.filter((i) => !i.passed) ?? [];
  const gateBlocked = !gate ? "正在读取上线门禁…"
    : gate.allPassed ? null
      : `上线门禁还差 ${pending.length} 项：${pending.map((i) => i.label).join("、")}`;

  const actions: ActionSpec[] = [
    {
      key: "goLive", label: "上线", perm: PERM, primary: true,
      when: canCabinetAction(s, "goLive"),
      blockedReason: archived ?? gateBlocked,
      confirm: {
        title: `上线 ${no}`,
        desc: "上线后这台柜子出现在 C 端可借列表、开始接客；所属站点若还在筹备中，会随之转为营业。",
        confirmText: "上线",
      },
      onRun: () => goLive.mutateAsync().catch(() => undefined),
    },
    {
      key: "repair", label: "修复完成", perm: PERM, primary: true,
      when: canCabinetAction(s, "repair"), blockedReason: archived,
      confirm: { title: `修复完成 ${no}`, desc: "确认故障已排除：柜子回到在用，重新开放借还。", confirmText: "确认修复" },
      onRun: () => repair.mutateAsync().catch(() => undefined),
    },
    {
      key: "markFault", label: "标记故障", perm: PERM, danger: true,
      when: canCabinetAction(s, "markFault"), blockedReason: archived,
      onRun: () => setReasonReq({
        title: `标记故障 ${no}`,
        help: "标记后这台柜子将停止借出（C 端不再显示可借），直到修复完成。写清故障现象，派单的人靠它判断带什么配件。",
        onSubmit: (r) => markFault.mutateAsync(r),
      }),
    },
    {
      key: "undeploy", label: "撤机回库", perm: PERM, danger: true,
      when: canCabinetAction(s, "undeploy"), blockedReason: archived,
      onRun: () => setReasonReq({
        title: `撤机回库 ${no}`,
        help: "撤机会解绑点位与站点，柜子回到在库；之前的试借还随之失效，再上线要重做。",
        onSubmit: (r) => undeploy.mutateAsync(r),
      }),
    },
    {
      key: "retire", label: "报废", perm: PERM, danger: true,
      when: canCabinetAction(s, "retire"), blockedReason: archived,
      confirm: {
        title: `报废 ${no}`,
        desc: "报废不可逆：这台柜子从此不能再上线、调拨或维修。在用的柜子要先撤机。输入柜机号确认。",
        confirmText: "继续", requireText: no,
      },
      onRun: () => setReasonReq({
        title: `报废 ${no}`,
        help: "写清报废原因（进水 / 主板烧毁 / 外壳损坏无法修复…），资产盘点与厂商索赔都认这一句。",
        onSubmit: (r) => retire.mutateAsync(r),
      }),
    },
  ];

  return {
    actions,
    drawers: (
      <>
        {dialog}
        <ReasonDrawer req={reasonReq} onClose={() => setReasonReq(null)} />
      </>
    ),
    confirm,
  };
}
