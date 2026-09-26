"use client";

// 充电宝人工动作（方案 §6.4）：标记故障 / 维修回仓 / 找回 / 报废 + 入库质检。
// 「标记丢失」不在这里：后端 V113 收成「疑似丢失 → 人工核实」专用端点（见 POWERBANK_TRANSITIONS 注释）。
//
// 哪个动作出现由 POWERBANK_TRANSITIONS 决定（照后端 PowerbankStateMachine 人工可触发的那几条边）；
// 借出 / 归还 / 买断 / 投放由订单与设备事件推进，这里不给按钮。发给后端的是**事件**，由后端状态机裁决。
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { StateActions, type ActionSpec } from "@/components/state-actions";
import { notify } from "@/lib/notify";
import { canPowerbankAction, POWERBANK_TRANSITIONS, type Powerbank, type PowerbankAction } from "@/lib/types";
import { PB_STATUS } from "./device-maps";

const PERM = "device:powerbank:update";

const CONFIRM_DESC: Record<PowerbankAction, string> = {
  reportFault: "标记后这块宝不再被挑去借出，等维修回仓。",
  repair: "确认已修好：回到在库，需重新入库质检后才能投放。",
  recover: "丢失的宝追回来了：回到在仓。",
  scrap: "报废不可逆：这块宝从此不能再借出、调拨或维修。输入充电宝号确认。",
};

export function PowerbankActions({
  pb, onQc, onDismissLost,
}: {
  pb: Powerbank;
  onQc: (pb: Powerbank) => void;
  /** 「已找回」要填说明，交给页面开抽屉收（这里只负责判定动作出不出现）。 */
  onDismissLost: (pb: Powerbank) => void;
}) {
  const qc = useQueryClient();
  const run = useMutation({
    mutationFn: (a: PowerbankAction) => api.transitPowerbank(pb.powerbankNo, a),
    onSuccess: (r, a) => {
      qc.invalidateQueries({ queryKey: ["devices"] });
      notify.success(`${r.powerbankNo} 已${POWERBANK_TRANSITIONS[a].label}，当前「${PB_STATUS[r.status]?.label ?? r.status}」`);
    },
  });
  const archived = pb.archivedAt ? "已归档的充电宝只读：先恢复" : null;
  const confirmLost = useMutation({
    mutationFn: () => api.confirmPowerbankLost(pb.powerbankNo),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["devices"] });
      notify.success(`${r.powerbankNo} 已确认丢失`);
    },
  });

  const actions: ActionSpec[] = (Object.keys(POWERBANK_TRANSITIONS) as PowerbankAction[]).map((a) => ({
    key: a,
    label: POWERBANK_TRANSITIONS[a].label,
    perm: PERM,
    when: canPowerbankAction(pb.status, a),
    blockedReason: archived,
    danger: a === "scrap",
    confirm: {
      title: `${POWERBANK_TRANSITIONS[a].label} ${pb.powerbankNo}`,
      desc: CONFIRM_DESC[a],
      confirmText: POWERBANK_TRANSITIONS[a].label,
      ...(a === "scrap" ? { requireText: pb.powerbankNo } : {}),
    },
    onRun: () => run.mutateAsync(a).catch(() => undefined),
  }));
  /*
   * 疑似丢失的两个核实动作（后端 V113）。**只在打了标记时出现** —— 与后端同一条闸：
   * 没被系统怀疑过的宝要标丢失，说明判断依据不在系统里，应先查清再说。
   * 确认丢失是不可逆的资产动作（核销、可能向最后借用人追偿），所以要求手输充电宝号。
   */
  if (pb.suspectedLostAt) {
    actions.unshift(
      {
        key: "confirmLost", label: "确认丢失", perm: PERM, danger: true, blockedReason: archived,
        confirm: {
          title: `确认丢失 ${pb.powerbankNo}`,
          desc: "这块宝将转为「丢失」并从柜位上摘除。丢失会进入资产核销、并可能向最后借用人追偿——"
            + "确认前请先核对最后订单与现场。输入充电宝号确认。",
          confirmText: "确认丢失", requireText: pb.powerbankNo,
        },
        onRun: () => confirmLost.mutateAsync().catch(() => undefined),
      },
      {
        key: "dismissLost", label: "已找回 / 误判", perm: PERM, blockedReason: archived,
        onRun: () => onDismissLost(pb),
      },
    );
  }
  actions.unshift({
    key: "qc", label: "入库质检", perm: PERM,
    // 只在在库时质检：在仓 / 借出的宝出问题走「标记故障」
    when: pb.status === "IN_STOCK", blockedReason: archived,
    onRun: () => onQc(pb),
  });

  return <StateActions actions={actions} />;
}
