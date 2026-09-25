"use client";

// 验收抽屉（方案 §8.5「验收抽屉顶部先显示复核结果，再给验收通过 / 返工」）。
//
// 复核结果放最上面，是因为它决定了这一步该怎么判：
//   复核通过的单系统已自动验收，走不到这里；
//   复核未通过（关联告警仍在）还要放行，**必须写明理由**（后端同样拒空），
//   否则「系统说没修好、人说修好了」这件事查不到依据。
import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { RefLink } from "@/components/ref-link";
import { fmtTime } from "@/lib/utils";
import type { WoAuditResult, WorkOrder } from "@/lib/types";
import { ALARM_RECOVERY, AUDIT_LABEL, FAULT_REASON_LABEL, REVIEW } from "./wo-meta";

export function WoCloseDrawer({
  wo, onClose, onDone, onRework,
}: {
  wo: WorkOrder | null;
  onClose: () => void;
  onDone: (msg: string) => void;
  /** 「退回返工」：交给页面的填原因抽屉。 */
  onRework?: (w: WorkOrder) => void;
}) {
  const detail = useQuery({
    queryKey: ["wo-detail", wo?.woNo],
    queryFn: () => api.getWorkOrderDetail(wo!.woNo),
    enabled: !!wo,
  });
  const [result, setResult] = React.useState<WoAuditResult>("PASS");
  const [note, setNote] = React.useState("");
  React.useEffect(() => { if (wo) { setResult("PASS"); setNote(""); } }, [wo]);

  const order = detail.data?.order ?? wo;
  const review = order?.ops?.reviewStatus ?? null;
  const failed = review === "FAILED";
  const alarms = detail.data?.alarms ?? [];

  const run = useMutation({
    mutationFn: () => api.closeWorkOrder(wo!.woNo, { auditResult: result, auditNote: note.trim() || undefined }),
    onSuccess: () => onDone("验收通过，工单已关闭"),
  });

  return (
    <Drawer
      open={!!wo}
      onOpenChange={(o) => !o && onClose()}
      title={`验收 ${wo?.woNo ?? ""}`}
      desc="关单是终态：验收结论与说明随工单永久留痕"
      width="w-[520px]"
      footer={
        wo && (
          <>
            <Button variant="outline" onClick={onClose}>取消</Button>
            {onRework && <Button variant="outline" onClick={() => onRework(wo)}>退回返工</Button>}
            <Button disabled={run.isPending || (failed && !note.trim())} onClick={() => run.mutate()}>确认验收关单</Button>
          </>
        )
      }
    >
      {wo && order && (
        <>
          <Field label="完工复核">
            {review ? <StatusBadge map={REVIEW} value={review} /> : <span className="text-muted-foreground">不适用（非告警来源，或没有关联告警）</span>}
          </Field>
          {alarms.length > 0 && (
            <ul className="mb-4 space-y-1.5">
              {alarms.map((a) => (
                <li key={a.alarmNo} className="flex flex-wrap items-center gap-2 txt-body">
                  <StatusBadge map={ALARM_RECOVERY} value={a.status === "CLOSED" ? "RECOVERED" : "ACTIVE"} />
                  <RefLink kind="alarm" no={a.alarmNo} />
                  <span className="text-muted-foreground">{a.alarmCode}</span>
                </li>
              ))}
            </ul>
          )}
          {failed && (
            <Notice className="mb-4">
              关联告警仍未恢复。如果现场确已修好（例如告警是设备侧延迟上报），可以放行，但必须在下方写明理由；
              否则请「退回返工」。
            </Notice>
          )}
          <Field label="处理人 / 完工时间">{order.handlerName ?? order.assigneeName ?? "-"} · {fmtTime(order.completedAt)}</Field>
          <Field label="处理说明">{order.handleNote ?? "-"}</Field>
          {order.ops?.faultReasonCode && <Field label="故障原因">{FAULT_REASON_LABEL[order.ops.faultReasonCode] ?? order.ops.faultReasonCode}</Field>}
          <Field label="更换配件">{order.partsReplaced ? "是" : "否"}</Field>
          <Field label="验收结论（必填）">
            <Select className="w-full" value={result} onChange={(e) => setResult(e.target.value as WoAuditResult)}>
              <option value="PASS">{AUDIT_LABEL.PASS}</option>
              <option value="PASS_WITH_ISSUE">{AUDIT_LABEL.PASS_WITH_ISSUE}</option>
            </Select>
          </Field>
          <Field label={failed ? "放行理由（复核未通过，必填）" : result === "PASS_WITH_ISSUE" ? "遗留问题说明（建议填写）" : "验收备注"}>
            <Input value={note} placeholder={failed ? "如：告警为设备延迟上报，现场复测弹宝 3 次正常" : "如：抽检一次弹出正常"}
              onChange={(e) => setNote(e.target.value)} />
          </Field>
        </>
      )}
    </Drawer>
  );
}
