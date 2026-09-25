"use client";

// 关闭告警抽屉（方案 §8.1「关闭」）：原因必选 + 说明，抽屉本身就是确认（R4）。
//
// 规则与后端 AlarmEngine#closeManually 逐条对齐：
//  · 只能选三档人工原因（AUTO_FIXED / SUPERSEDED 只由系统写）；
//  · 「已解决」「误报」必须写说明；
//  · 安全域、以及恢复规则是「随处置完成」的业务告警，**不能人工以「已解决」关** ——
//    隐患宝锁着仓，点一下「已解决」就把锁放了，而现场可能根本没人去过。
//    这类告警的「已解决」选项禁用并写明原因（误报 / 已自愈仍可选）。
import * as React from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { notify } from "@/lib/notify";
import { ALARM_CLOSE_REASONS } from "@/lib/types";
import type { AlarmRecord, AlarmCloseReason, AlarmCode } from "@/lib/types";
import { ALARM_LEVEL } from "./alarm-maps";

/** 该告警不能人工以「已解决」关闭的原因；可以则 null。 */
export function closeRestriction(a: AlarmRecord, code?: AlarmCode): string | null {
  if (a.source !== "EVAL") return null;
  const b = code?.business;
  if (a.business?.domain === "SAFETY" || b?.domain === "SAFETY") return "安全域告警只能随处置完成关闭（工单完工并复核后自动关）";
  if (b?.recoverRule === "DISPOSITION_DONE") return "该告警码配置为「随处置完成恢复」，只能等处置完成后自动关闭";
  return null;
}

const NOTE_REQUIRED: AlarmCloseReason[] = ["RESOLVED", "FALSE_ALARM"];

export function AlarmCloseDrawer({
  alarm, restriction, onClose, onDone,
}: {
  alarm: AlarmRecord | null;
  restriction: string | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = React.useState<AlarmCloseReason | "">("");
  const [note, setNote] = React.useState("");
  React.useEffect(() => { setReason(""); setNote(""); }, [alarm?.alarmNo]);

  const m = useMutation({
    mutationFn: (v: { alarmNo: string; reason: AlarmCloseReason; note: string }) =>
      api.closeAlarm(v.alarmNo, v.reason, v.note.trim() || undefined),
    onSuccess: (r) => { notify.success(`告警 ${r.alarmNo} 已关闭`); onDone(); },
  });

  const needNote = !!reason && NOTE_REQUIRED.includes(reason);
  const blocked = !reason || (needNote && !note.trim()) || (reason === "RESOLVED" && !!restriction);

  return (
    <Drawer
      open={!!alarm}
      onOpenChange={(o) => !o && onClose()}
      title={`关闭告警 ${alarm?.alarmNo ?? ""}`}
      desc="关闭表示这条告警到此为止，不再计入「未关闭」；名下未办完的待办会一并撤销"
      footer={alarm && (
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button
            variant="destructive"
            disabled={blocked || m.isPending}
            onClick={() => reason && m.mutate({ alarmNo: alarm.alarmNo, reason, note })}
          >确认关闭</Button>
        </>
      )}
    >
      {alarm && (
        <>
          <Field label="告警">{alarm.alarmCode} · {ALARM_LEVEL[alarm.level]?.label ?? alarm.level}</Field>
          <Field label="关闭原因（必选）">
            <div className="flex flex-col gap-2" role="radiogroup" aria-label="关闭原因">
              {ALARM_CLOSE_REASONS.map((r) => {
                const disabled = r.value === "RESOLVED" && !!restriction;
                return (
                  <label key={r.value} className={disabled ? "flex items-start gap-2 opacity-60" : "flex cursor-pointer items-start gap-2"}>
                    <input
                      type="radio" name="alarm-close-reason" className="mt-1" disabled={disabled}
                      checked={reason === r.value} onChange={() => setReason(r.value)}
                    />
                    <span>
                      <span className="txt-body">{r.label}</span>
                      <span className="block txt-caption text-muted-foreground">{disabled ? restriction : r.hint}</span>
                    </span>
                  </label>
                );
              })}
            </div>
          </Field>
          <Field label={needNote ? "说明（必填）" : "说明（选填）"}>
            <Textarea
              value={note} rows={3}
              placeholder={reason === "FALSE_ALARM" ? "为什么是误报：哪个阈值 / 哪条规则太敏感——这是调规则的依据" : "处理经过或补充说明"}
              onChange={(v) => setNote(v)}
            />
          </Field>
        </>
      )}
    </Drawer>
  );
}
