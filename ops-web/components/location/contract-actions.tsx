"use client";

// 合同流转动作（方案 §4.3）：列表行与详情头共用一份 —— 两处各写一套的话，
// 「详情里能点、列表里没有」或权限码对不上，迟早出现一处。
//
// 每个动作挂**后端端点实际判的权限码**（ContractController）：
//   提交 / 撤回 → location:contract:submit · 运营审批 / 终止审批 → :audit · 财务会签 → :cosign（FINANCE）
//   登记签署 / 附件 / 编辑 → :update · 申请终止 → :terminate · 续签 / 补充协议 → :create
// 此前全挂 :update —— 财务没有这个码，于是「待财务会签」的合同在财务那里一个按钮都没有。
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Contract } from "@/lib/types";
import { CONTRACT_TRANSITIONS } from "@/lib/types";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { DateInput } from "@/components/ui/date-input";
import { Button } from "@/components/ui/button";
import { FileField } from "@/components/ui/file-field";
import type { ActionSpec } from "@/components/state-actions";

export const CONTRACT_PERM = {
  submit: "location:contract:submit",
  audit: "location:contract:audit",
  cosign: "location:contract:cosign",
  update: "location:contract:update",
  terminate: "location:contract:terminate",
  create: "location:contract:create",
} as const;

type AuditKind = "audit" | "cosign" | "termAudit";
const AUDIT_TITLE: Record<AuditKind, string> = { audit: "运营审批", cosign: "财务会签", termAudit: "终止申请审批" };

const can = (action: keyof typeof CONTRACT_TRANSITIONS, c: Contract) => CONTRACT_TRANSITIONS[action].from.includes(c.status);

export function useContractActions({ onEdit, onAttach }: {
  /** 编辑草稿（表单在列表页）。不传 = 不出「编辑」。 */
  onEdit?: (c: Contract) => void;
  /** 打开附件抽屉。不传 = 不出「附件」。 */
  onAttach?: (c: Contract) => void;
} = {}) {
  const qc = useQueryClient();
  const [audit, setAudit] = useState<{ c: Contract; kind: AuditKind } | null>(null);
  const [auditNote, setAuditNote] = useState("");
  const [signRow, setSignRow] = useState<Contract | null>(null);
  const [signedAt, setSignedAt] = useState("");
  const [signFiles, setSignFiles] = useState<string[]>([]);
  const [termRow, setTermRow] = useState<Contract | null>(null);
  const [termReason, setTermReason] = useState("");
  const [termAt, setTermAt] = useState("");
  const [termConfirm, setTermConfirm] = useState("");
  const [suppRow, setSuppRow] = useState<Contract | null>(null);
  const [suppAt, setSuppAt] = useState("");

  const done = (msg: string) => {
    qc.invalidateQueries({ queryKey: ["venue-bd"] });
    qc.invalidateQueries({ queryKey: ["contract-summary"] });
    qc.invalidateQueries({ queryKey: ["contract-detail"] });
    qc.invalidateQueries({ queryKey: ["contract-logs"] });
    qc.invalidateQueries({ queryKey: ["op", "site-contracts"] });
    notify.success(msg);
  };

  const act = useMutation({
    mutationFn: (v:
      | { kind: "submit" | "withdraw" | "renew"; no: string }
      | { kind: AuditKind; no: string; result: "APPROVE" | "REJECT"; reason?: string }
      | { kind: "sign"; no: string; signedAt: string; fileNos: string[] }
      | { kind: "terminate"; no: string; reason: string; effectiveAt?: string }
      | { kind: "supplement"; no: string; startAt?: string }) => {
      switch (v.kind) {
        case "submit": return api.submitContract(v.no);
        case "withdraw": return api.withdrawContract(v.no);
        case "renew": return api.renewContract(v.no);
        case "audit": return api.auditContract(v.no, v.result, v.reason);
        case "cosign": return api.cosignContract(v.no, v.result, v.reason);
        case "termAudit": return api.auditContractTermination(v.no, v.result, v.reason);
        case "sign": return api.signContract(v.no, v.signedAt, v.fileNos);
        case "terminate": return api.terminateContract(v.no, v.reason, v.effectiveAt);
        case "supplement": return api.supplementContract(v.no, v.startAt);
      }
    },
    onSuccess: (c, v) => {
      const msg: Record<typeof v.kind, string> = {
        submit: "已提交审批", withdraw: "已撤回为草稿", renew: `已生成续签草稿 ${c.contractNo}`,
        audit: "已处理", cosign: "已处理", termAudit: "已处理", sign: "已登记签署",
        terminate: "终止申请已提交，审批期间合同照常生效", supplement: `已生成补充协议草稿 ${c.contractNo}`,
      };
      done(msg[v.kind]);
      setAudit(null); setSignRow(null); setTermRow(null); setSuppRow(null);
    },
  });

  const openAudit = (c: Contract, kind: AuditKind) => { setAuditNote(""); setAudit({ c, kind }); };

  const actionsFor = (c: Contract): ActionSpec[] => {
    const stage = c.flow?.auditStage ?? "OPS";
    const term = c.flow?.termination;
    const ended = c.status === "EXPIRED" || c.status === "TERMINATED";
    return [
      {
        key: "submit", label: "提交审批", primary: true, perm: CONTRACT_PERM.submit, when: can("submit", c),
        confirm: { title: `提交合同 ${c.contractNo} 审批？`, desc: "提交后条款锁定；要改得先撤回。分成 / 进场费超阈值的还要财务会签。" },
        onRun: () => act.mutateAsync({ kind: "submit", no: c.contractNo }),
      },
      {
        key: "audit", label: "审批", primary: true, perm: CONTRACT_PERM.audit,
        when: c.status === "PENDING" && stage !== "FINANCE",
        onRun: () => openAudit(c, "audit"),
      },
      {
        key: "cosign", label: "财务会签", primary: true, perm: CONTRACT_PERM.cosign,
        when: c.status === "PENDING" && stage === "FINANCE",
        onRun: () => openAudit(c, "cosign"),
      },
      {
        key: "sign", label: "登记签署", primary: true, perm: CONTRACT_PERM.update, when: c.status === "SIGNED",
        onRun: () => { setSignedAt(""); setSignFiles([]); setSignRow(c); },
      },
      {
        key: "termAudit", label: "审批终止申请", primary: true, perm: CONTRACT_PERM.audit,
        when: c.status === "ACTIVE" && term?.status === "PENDING",
        onRun: () => openAudit(c, "termAudit"),
      },
      {
        key: "withdraw", label: "撤回", perm: CONTRACT_PERM.submit, when: can("withdraw", c),
        confirm: { title: `撤回合同 ${c.contractNo}？`, desc: "回到草稿，可以改条款后重新提交。审批中反悔用它，不要去驳回自己的单。" },
        onRun: () => act.mutateAsync({ kind: "withdraw", no: c.contractNo }),
      },
      {
        key: "edit", label: "编辑", perm: CONTRACT_PERM.update, when: !!onEdit && c.status === "DRAFT",
        onRun: () => onEdit?.(c),
      },
      {
        key: "attach", label: "扫描件", perm: CONTRACT_PERM.update, when: !!onAttach && !ended,
        onRun: () => onAttach?.(c),
      },
      {
        key: "renew", label: "续签", perm: CONTRACT_PERM.create, when: c.status === "ACTIVE" || c.status === "EXPIRED",
        confirm: { title: `按 ${c.contractNo} 生成续签草稿？`, desc: "新草稿沿用原条款，改好后走一遍审批。原合同不受影响。" },
        onRun: () => act.mutateAsync({ kind: "renew", no: c.contractNo }),
      },
      {
        key: "supplement", label: "补充协议", perm: CONTRACT_PERM.create, when: c.status === "ACTIVE",
        onRun: () => { setSuppAt(""); setSuppRow(c); },
      },
      {
        key: "terminate", label: "申请提前终止", danger: true, perm: CONTRACT_PERM.terminate,
        when: can("terminate", c) && term?.status !== "PENDING",
        onRun: () => { setTermReason(""); setTermAt(""); setTermConfirm(""); setTermRow(c); },
      },
    ];
  };

  const auditRow = audit?.c;
  const ui = (
    <>
      {/* 审批三合一：运营审批 / 财务会签 / 终止申请审批 —— 表单完全一样（通过 / 驳回 + 意见），差别只在端点 */}
      <Drawer
        open={!!audit}
        onOpenChange={(o) => !o && setAudit(null)}
        title={audit ? `${AUDIT_TITLE[audit.kind]} · ${audit.c.contractNo}` : ""}
        desc="驳回必须写意见 —— 提交人看到的只有这句话。不能审批自己提交的单"
        width="w-[520px]"
        footer={audit && auditRow && (<>
          <Button variant="outline" onClick={() => setAudit(null)}>取消</Button>
          <Button variant="destructive" disabled={act.isPending || !auditNote.trim()}
            onClick={() => act.mutate({ kind: audit.kind, no: auditRow.contractNo, result: "REJECT", reason: auditNote.trim() })}>驳回</Button>
          <Button disabled={act.isPending}
            onClick={() => act.mutate({ kind: audit.kind, no: auditRow.contractNo, result: "APPROVE", reason: auditNote.trim() || undefined })}>通过</Button>
        </>)}
      >
        {audit && auditRow && (<>
          <Field label="场地方 / 站点">{auditRow.venueName} · {auditRow.siteName}</Field>
          <Field label="分成 / 进场费">
            <span className="tabular-nums">{(auditRow.shareRate * 100).toFixed(0)}%</span>
            <span className="ms-3 tabular-nums">{auditRow.entryFee}</span>
            {auditRow.terms?.currency && <span className="ms-1 text-muted-foreground">{auditRow.terms.currency}</span>}
          </Field>
          <Field label="合同期">{auditRow.startAt?.slice(0, 10)} ~ {auditRow.endAt?.slice(0, 10)}</Field>
          {audit.kind === "cosign" && auditRow.flow?.auditNote && (
            <Field label="运营审批意见">{auditRow.flow.auditNote}</Field>
          )}
          {audit.kind === "termAudit" && auditRow.flow?.termination && (<>
            <Field label="终止原因">{auditRow.flow.termination.reason}</Field>
            <Field label="终止生效日">{auditRow.flow.termination.effectiveAt ?? "获批即终止"}</Field>
          </>)}
          <Field label="意见">
            <Textarea rows={3} value={auditNote} onChange={setAuditNote} placeholder="驳回时必填；通过可留空" />
          </Field>
          {audit.kind === "audit" && (
            <p className="txt-caption text-muted-foreground">分成比例 / 进场费 / 保底超阈值的合同，运营通过后还要财务会签，状态仍是「审批中」。</p>
          )}
        </>)}
      </Drawer>

      {/* 登记签署：签署日 + 盖章扫描件（经文件服务上传，签署时一并挂到合同上） */}
      <Drawer
        open={!!signRow}
        onOpenChange={(o) => !o && setSignRow(null)}
        title={`登记签署 ${signRow?.contractNo ?? ""}`}
        desc="签署日与生效日一起决定合同何时生效（到生效日由系统推进，不手工点「生效」）"
        width="w-[520px]"
        footer={signRow && (<>
          <Button variant="outline" onClick={() => setSignRow(null)}>取消</Button>
          <Button disabled={act.isPending || !signedAt}
            onClick={() => act.mutate({ kind: "sign", no: signRow.contractNo, signedAt, fileNos: signFiles })}>确认</Button>
        </>)}
      >
        {signRow && (<>
          <Field label="签署日（必填）"><DateInput value={signedAt} onChange={(e) => setSignedAt(e.target.value)} /></Field>
          <Field label="盖章扫描件">
            <FileField value={signFiles} onChange={setSignFiles} category="CONTRACT_SCAN" max={5} />
          </Field>
          {!signFiles.length && !signRow.attachments.length && (
            <p className="txt-caption text-warning-ink">还没有扫描件 —— 登记不会拦你，但「缺签署件」会一直挂在摘要条上，对账时也拿不出凭据</p>
          )}
        </>)}
      </Drawer>

      {/* 申请提前终止：不可逆方向的动作，要手输合同号确认（R4）。审批期间照常生效 */}
      <Drawer
        open={!!termRow}
        onOpenChange={(o) => !o && setTermRow(null)}
        title={`申请提前终止 ${termRow?.contractNo ?? ""}`}
        desc="提交后进入审批。审批期间合同照常生效，获批后到生效日才由系统终止"
        width="w-[520px]"
        footer={termRow && (<>
          <Button variant="outline" onClick={() => setTermRow(null)}>取消</Button>
          <Button variant="destructive"
            disabled={act.isPending || !termReason.trim() || termConfirm.trim() !== termRow.contractNo}
            onClick={() => act.mutate({ kind: "terminate", no: termRow.contractNo, reason: termReason.trim(), effectiveAt: termAt || undefined })}>
            提交申请
          </Button>
        </>)}
      >
        {termRow && (<>
          <Field label="终止原因（必填）">
            <Textarea rows={2} value={termReason} onChange={setTermReason} placeholder="如：场地方要求提前撤场" />
          </Field>
          <Field label="终止生效日">
            <DateInput value={termAt} onChange={(e) => setTermAt(e.target.value)} />
            <div className="mt-1 txt-caption text-muted-foreground">留空 = 获批即终止；填了则到那一天由系统终止</div>
          </Field>
          <Field label={`输入合同号 ${termRow.contractNo} 确认`}>
            <Input className="w-full" value={termConfirm} onChange={(e) => setTermConfirm(e.target.value)} />
          </Field>
        </>)}
      </Drawer>

      {/* 补充协议：只定生效日，条款在生成的草稿上改，到期日跟主合同 */}
      <Drawer
        open={!!suppRow}
        onOpenChange={(o) => !o && setSuppRow(null)}
        title={`补充协议 · ${suppRow?.contractNo ?? ""}`}
        desc="生成一份挂在主合同下的草稿：条款在草稿上改，到期日跟主合同，同样要走审批"
        width="w-[460px]"
        footer={suppRow && (<>
          <Button variant="outline" onClick={() => setSuppRow(null)}>取消</Button>
          <Button disabled={act.isPending}
            onClick={() => act.mutate({ kind: "supplement", no: suppRow.contractNo, startAt: suppAt || undefined })}>生成草稿</Button>
        </>)}
      >
        <Field label="生效日"><DateInput value={suppAt} onChange={(e) => setSuppAt(e.target.value)} /></Field>
        <p className="txt-caption text-muted-foreground">留空 = 明天。</p>
      </Drawer>
    </>
  );

  return { actionsFor, ui };
}
