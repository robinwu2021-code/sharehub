"use client";

// 站点详情 ›「现场勘测」页签（C1）：勘测历史 + 记一次勘测。
//
// 勘测**只增不改**：复勘就再记一条，以最近一次为准。开业清单的「现场勘测」一项读的就是最近一次 ——
// 首台设备上线要求它是「通过」，所以这里的结论直接决定站点能不能开业。
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { SiteStatus, SignalLevel, SurveyResult } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Field } from "@/components/ui/drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Timeline } from "@/components/ui/timeline";
import { Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { SwitchField } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { FileField } from "@/components/ui/file-field";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { FileLink } from "./file-link";

export const SURVEY_RESULT: StatusMap<SurveyResult> = {
  PASS: { label: "通过", tone: "success" },
  FAIL: { label: "不通过", tone: "danger" },
};
export const SIGNAL_LEVEL: StatusMap<SignalLevel> = {
  STRONG: { label: "信号强", tone: "success" },
  GOOD: { label: "信号良好", tone: "success" },
  WEAK: { label: "信号弱", tone: "warning" },
  NONE: { label: "无信号", tone: "danger" },
};
const SIGNALS = Object.keys(SIGNAL_LEVEL) as SignalLevel[];

const PERM = "location:poi:update";

export function SiteSurveyPanel({ siteNo, siteStatus }: { siteNo: string; siteStatus: SiteStatus }) {
  const qc = useQueryClient();
  const allow = useCan();
  const q = useQuery({ queryKey: ["op", "site-surveys", siteNo], queryFn: () => api.listSiteSurveys(siteNo) });

  const [signal, setSignal] = useState<SignalLevel>("GOOD");
  const [powerOk, setPowerOk] = useState(true);
  const [placement, setPlacement] = useState("");
  const [result, setResult] = useState<SurveyResult>("PASS");
  const [note, setNote] = useState("");
  const [photos, setPhotos] = useState<string[]>([]);

  const save = useMutation({
    mutationFn: () => api.recordSiteSurvey(siteNo, {
      signalLevel: signal, powerOk, result,
      placementNote: placement.trim() || undefined, note: note.trim() || undefined, fileNos: photos,
    }),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["op", "site-surveys", siteNo] });
      qc.invalidateQueries({ queryKey: ["site-gate"] });
      qc.invalidateQueries({ queryKey: ["op", "site-opening", siteNo] });
      notify.success(r.result === "PASS" ? "已记录：勘测通过" : "已记录：勘测不通过");
      setPlacement(""); setNote(""); setPhotos([]);
    },
  });

  // 与服务端同一条规则，提前说清楚：没信号或不能接电就判不了通过
  const passBlocked = result === "PASS" && (signal === "NONE" || !powerOk);
  const noteMissing = result === "FAIL" && !note.trim();
  const list = q.data ?? [];
  const latest = list[0];

  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        {latest
          ? <>最近一次勘测 <StatusBadge map={SURVEY_RESULT} value={latest.result} />（{fmtTime(latest.surveyedAt)}）。开业清单以最近一次为准，首台设备上线要求它是「通过」。</>
          : "这个站点还没有勘测记录。开业清单里「现场勘测」一项会一直挂着 —— 首台设备上线要求最近一次勘测通过。"}
      </Notice>

      <Timeline
        loading={q.isLoading}
        empty="还没有勘测记录 —— 去现场看完信号、电源和可摆位置后，在下方记第一条"
        items={list.map((v) => ({
          key: v.surveyNo,
          badge: { label: SURVEY_RESULT[v.result]?.label ?? v.result, tone: SURVEY_RESULT[v.result]?.tone ?? "outline" },
          meta: `${v.surveyNo} · ${fmtTime(v.surveyedAt)} · ${v.surveyedBy ?? "-"}`,
          change: `${SIGNAL_LEVEL[v.signalLevel]?.label ?? v.signalLevel} · ${v.powerOk ? "可接电" : "不能接电"}${v.placementNote ? ` · ${v.placementNote}` : ""}`,
          text: (v.note || v.fileNos.length) ? (
            <>
              {v.note && <div>{v.note}</div>}
              {v.fileNos.length > 0 && (
                <div className="mt-1 flex flex-wrap gap-3">
                  {v.fileNos.map((f, i) => <FileLink key={f} fileNo={f} label={`照片 ${i + 1}`} />)}
                </div>
              )}
            </>
          ) : undefined,
        }))}
      />

      {siteStatus === "CLOSED" ? (
        <Notice className="mb-0">站点已关闭，不能再记勘测。要重开只能另建站点。</Notice>
      ) : !allow(PERM) ? (
        <ReadOnlyNotice what="现场勘测" perm={PERM} note="不能记录勘测" />
      ) : (
        <div className="space-y-3 border-t border-border pt-4">
          <div className="txt-label text-muted-foreground">记一次勘测</div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="现场信号" className="mb-0">
              <Select className="w-full" value={signal} onChange={(e) => setSignal(e.target.value as SignalLevel)}>
                {SIGNALS.map((s) => <option key={s} value={s}>{SIGNAL_LEVEL[s].label}</option>)}
              </Select>
            </Field>
            <Field label="结论" className="mb-0">
              <Select className="w-full" value={result} onChange={(e) => setResult(e.target.value as SurveyResult)}>
                <option value="PASS">通过</option>
                <option value="FAIL">不通过</option>
              </Select>
            </Field>
          </div>
          <SwitchField label="可以接电" desc="柜子要常供电；没有插座就得先让物业拉线" checked={powerOk} onChange={setPowerOk} />
          <Field label="可摆放位置" className="mb-0">
            <Textarea rows={2} value={placement} onChange={setPlacement} placeholder="如：B1 扶梯口左侧，靠柱，离收银台 5 米" />
          </Field>
          <Field label={result === "FAIL" ? "说明（不通过必填）" : "说明"} className="mb-0">
            <Textarea rows={2} value={note} onChange={setNote}
              placeholder={result === "FAIL" ? "为什么不通过、下次复勘该看什么" : "可留空"} />
          </Field>
          <Field label="现场照片" className="mb-0">
            <FileField value={photos} onChange={setPhotos} category="SURVEY_PHOTO" max={9} />
          </Field>
          <div className="flex items-center gap-3">
            {passBlocked && <span className="txt-caption text-warning-ink">无信号或不能接电时不能判「通过」—— 借不出也还不了</span>}
            {!passBlocked && noteMissing && <span className="txt-caption text-muted-foreground">判「不通过」需写说明</span>}
            <Button size="sm" className="ms-auto" disabled={save.isPending || passBlocked || noteMissing} onClick={() => save.mutate()}>
              记录勘测
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
