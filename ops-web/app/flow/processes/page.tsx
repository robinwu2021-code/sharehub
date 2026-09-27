"use client";

// 流程配置（原型 P5a）：表单化配置审批流程（不做图形设计器）。编辑的是「审批模板」（21/02 §6.4a）——
// 线性步骤，服务端编译成审批类流程。保存即发布新版本：版本 = 内容哈希，只增不改；
// 进行中的审批按提交时的版本走完，新版本只影响新提交。
// 校验在服务端（422 逐条带路径，路径按模板字段给），这里把每条错误挂到对应字段、并在顶部汇总。
// 名称是多语种（name{i18n}）：按当前界面语言编辑那一种，其他语种原样保留。
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { isValidationError } from "@/lib/flow/errors";
import { fmtShort } from "@/lib/flow/present";
import { ALL_MODE_KINDS } from "@/lib/flow/types";
import type { ApprovalTemplate, ApproverKind, ConditionOp, FieldError, I18nText, TemplateStep, TemplateSummary } from "@/lib/flow/types";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { FlowTimeline } from "@/components/flow/flow-timeline";

const ENABLED: StatusMap<"on" | "off"> = { on: { label: "启用", tone: "success" }, off: { label: "已停用", tone: "muted" } };
const APPROVER: { value: ApproverKind; label: string; hint: string }[] = [
  { value: "PERM", label: "按权限码", hint: "如 location:contract:audit" },
  { value: "ROLE", label: "按角色", hint: "角色代号" },
  { value: "GROUP", label: "按组", hint: "组代号；队列组为领取制" },
  { value: "MEMBER", label: "指定成员", hint: "成员号" },
  { value: "FIELD", label: "提交时指定", hint: "字段代号：业务系统提交时在这个字段里给出审批人" },
];
const allowsAll = (k: ApproverKind) => ALL_MODE_KINDS.includes(k);
/** 当前语言有就用，没有回落到任一语种（只作占位提示） */
const fallback = (t: I18nText) => t.zh ?? t.en ?? t.ar ?? "";
const COND: { value: "" | ConditionOp; label: string }[] = [
  { value: "", label: "无（必经）" }, { value: "TRUE", label: "属性为真" }, { value: "EQ", label: "属性等于" }, { value: "IN", label: "属性属于（逗号分隔）" },
];

const blankStep = (): TemplateStep => ({ code: "", name: {}, approver: { kind: "PERM", value: "" }, mode: "ANY", dueHours: 24 });

function FlowProcesses() {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const { locale } = useI18n();
  const list = useQuery({ queryKey: [...FLOW_KEY, "approval-templates"], queryFn: flow.templates });
  const [editing, setEditing] = useState<{ def: ApprovalTemplate; isNew: boolean } | null>(null);
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<FieldError[]>([]);
  const code = editing && !editing.isNew ? editing.def.code : null;
  const versions = useQuery({
    queryKey: [...FLOW_KEY, "template-versions", code], queryFn: () => flow.templateVersions(code!), enabled: !!code,
  });

  const open = async (p: TemplateSummary) => {
    setErrors([]); setNote("");
    setEditing({ def: await flow.template(p.code), isNew: false });
  };
  const toggle = useMutation({
    mutationFn: (p: TemplateSummary) => flow.setTemplateEnabled(p.code, !p.enabled),
    onSuccess: (_v, p) => { notify.success(p.enabled ? `已停用「${p.name}」：在途单走完后不再接受新提交` : `已启用「${p.name}」`); qc.invalidateQueries({ queryKey: FLOW_KEY }); },
    onError: (e: Error) => notify.error(e.message),
  });
  const publish = useMutation({
    mutationFn: () => flow.publishTemplate(editing!.def, note, editing!.isNew),
    onSuccess: (r) => {
      setErrors([]);
      if (!r.created) notify.info("内容没有变化，未生成新版本");
      else notify.success(`已发布新版本 ${r.version}${r.runningOnOlderVersions ? `；在途 ${r.runningOnOlderVersions} 单仍按旧版走完` : ""}`);
      setNote("");
      if (editing?.isNew) setEditing({ def: editing.def, isNew: false });
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
    onError: (e: Error) => {
      if (isValidationError(e)) setErrors(e.errors);
      notify.error(e.message);
    },
  });

  const err = (path: string) => errors.find((x) => x.path === path)?.error;
  const set = (fn: (d: ApprovalTemplate) => void) => setEditing((cur) => {
    if (!cur) return cur;
    const def = structuredClone(cur.def);
    fn(def);
    return { ...cur, def };
  });
  const setStep = (i: number, fn: (s: TemplateStep) => void) => set((d) => fn(d.steps[i]));
  const move = (i: number, dir: -1 | 1) => set((d) => { const [s] = d.steps.splice(i, 1); d.steps.splice(i + dir, 0, s); });

  const columns: Column<TemplateSummary>[] = [
    { header: "流程", cell: (p) => <span className="txt-strong">{p.name}</span> },
    { header: "代号", pk: true, cell: (p) => p.code },
    { header: "步骤", cell: (p) => p.stepsSummary },
    { header: "当前版本", cell: (p) => <span className="font-mono text-xs">{p.version}</span> },
    { header: "在途单", className: "text-end", cell: (p) => <span className="tabular-nums">{p.running}</span> },
    { header: "状态", cell: (p) => <StatusBadge map={ENABLED} value={p.enabled ? "on" : "off"} /> },
    {
      header: "操作", className: "text-end",
      cell: (p) => (
        <span className="inline-flex gap-2">
          <Button size="sm" variant="secondary" onClick={() => open(p)}>编辑</Button>
          <Button size="sm" variant={p.enabled ? "destructive" : "secondary"} disabled={toggle.isPending}
            onClick={async () => {
              if (!p.enabled || await confirm({ title: `停用「${p.name}」？`, desc: `停用后不再接受新提交；在途 ${p.running} 单会按原流程走完。可随时重新启用。`, confirmText: "停用", danger: true })) toggle.mutate(p);
            }}>
            {p.enabled ? "停用" : "启用"}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title="流程配置" desc="表单化配置审批流程（不做图形设计器）。保存即发布新版本：进行中的审批按提交时的版本走完，新版本只影响新提交。" />

      <Card className="mb-4 p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">流程列表</h2>
          <Button size="sm" onClick={() => { setErrors([]); setNote(""); setEditing({ isNew: true, def: { code: "", name: {}, rules: { selfApproval: false, crossStepExclusive: true }, steps: [blankStep()] } }); }}>
            <Plus className="size-4" /> 新建流程
          </Button>
        </div>
        <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error} onRetry={() => list.refetch()}
          rowKey={(p) => p.code} empty="还没有流程 —— 业务系统可经 API 登记，也可以在这里新建" />
      </Card>

      {editing && (
        <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
          <Card className="p-5">
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="txt-strong">{editing.isNew ? "新建流程" : `编辑：${editing.def.name[locale] || fallback(editing.def.name)} ${editing.def.code}`}</h2>
              <span className="txt-caption text-muted-foreground">改动保存后生成新版本</span>
            </div>

            {errors.length > 0 && (
              <Notice className="border-[var(--destructive)] text-destructive-ink">
                保存被拒，有 {errors.length} 处需要修改：{errors.map((e) => `${e.path} ${e.error}`).join("；")}
              </Notice>
            )}

            <div className="mb-4 grid gap-3 sm:grid-cols-2">
              {editing.isNew && (
                <Field label="代号" error={err("code")} help="大写字母开头，如 LOC_CONTRACT；创建后不能改">
                  <Input value={editing.def.code} aria-invalid={!!err("code")} onChange={(e) => set((d) => { d.code = e.target.value.toUpperCase(); })} />
                </Field>
              )}
              <Field label="流程名称" error={err("name")} help="按当前界面语言编辑；其他语种保留原值">
                <Input value={editing.def.name[locale] ?? ""} placeholder={fallback(editing.def.name)} aria-invalid={!!err("name")}
                  onChange={(e) => set((d) => { d.name[locale] = e.target.value; })} />
              </Field>
            </div>
            {err("steps") && <p className="mb-2 txt-caption text-destructive-ink">{err("steps")}</p>}

            <div className="space-y-3">
              {editing.def.steps.map((s, i) => {
                const p = (k: string) => `steps[${i}].${k}`;
                const kind = APPROVER.find((a) => a.value === s.approver.kind)!;
                return (
                  <div key={i} className="rounded-card border border-[var(--border)] p-4">
                    <div className="mb-3 flex items-center justify-between gap-2">
                      <span className="txt-strong">步骤 {i + 1} · {s.name[locale] || fallback(s.name) || "未命名"}</span>
                      <span className="inline-flex gap-1">
                        <Button size="icon" variant="ghost" aria-label="上移" disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp /></Button>
                        <Button size="icon" variant="ghost" aria-label="下移" disabled={i === editing.def.steps.length - 1} onClick={() => move(i, 1)}><ArrowDown /></Button>
                        <Button size="icon" variant="ghost" aria-label="删除步骤" disabled={editing.def.steps.length === 1}
                          onClick={() => set((d) => { d.steps.splice(i, 1); })}><Trash2 /></Button>
                      </span>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                      <Field label="步骤名称" error={err(p("name"))}>
                        <Input value={s.name[locale] ?? ""} placeholder={fallback(s.name)} aria-invalid={!!err(p("name"))}
                          onChange={(e) => setStep(i, (x) => { x.name[locale] = e.target.value; })} />
                      </Field>
                      <Field label="审批人" error={err(p("approver.value"))} help={kind.hint}>
                        <div className="flex gap-2">
                          <Select className="w-36 shrink-0" value={s.approver.kind}
                            onChange={(e) => setStep(i, (x) => {
                              x.approver.kind = e.target.value as ApproverKind;
                              if (!allowsAll(x.approver.kind)) x.mode = "ANY";
                            })}>
                            {APPROVER.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
                          </Select>
                          <Input value={s.approver.value} aria-invalid={!!err(p("approver.value"))}
                            onChange={(e) => setStep(i, (x) => { x.approver.value = e.target.value; })} />
                        </div>
                      </Field>
                      <Field label="模式" error={err(p("mode"))}
                        help={allowsAll(s.approver.kind) ? undefined : "按角色 / 权限码时人选不可枚举，只能或签"}>
                        <Select value={s.mode} aria-invalid={!!err(p("mode"))} onChange={(e) => setStep(i, (x) => { x.mode = e.target.value as "ANY" | "ALL"; })}>
                          <option value="ANY">或签（一人通过即过）</option>
                          <option value="ALL" disabled={!allowsAll(s.approver.kind)}>会签（全部通过才过）</option>
                        </Select>
                      </Field>
                      <Field label="步骤时限（小时）" error={err(p("dueHours"))} help="超时提醒，不自动通过；留空 = 不限">
                        <Input type="number" min={1} max={720} value={s.dueHours ?? ""} aria-invalid={!!err(p("dueHours"))}
                          onChange={(e) => setStep(i, (x) => { x.dueHours = e.target.value === "" ? undefined : Number(e.target.value); })} />
                      </Field>
                      <Field label="进入条件" error={err(p("when.field")) ?? err(p("when.value"))}
                        help="只支持「属性 = 值 / 为真 / 属于」；复杂判断由业务系统在提交前算成属性" className="sm:col-span-2">
                        <div className="flex flex-wrap gap-2">
                          <Select className="w-44" value={s.when?.op ?? ""}
                            onChange={(e) => setStep(i, (x) => {
                              const op = e.target.value as "" | ConditionOp;
                              x.when = op ? { field: x.when?.field ?? "", op, value: x.when?.value } : undefined;
                            })}>
                            {COND.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
                          </Select>
                          {s.when && (
                            <Input className="w-44" placeholder="属性名，如 needsCosign" value={s.when.field} aria-invalid={!!err(p("when.field"))}
                              onChange={(e) => setStep(i, (x) => { x.when!.field = e.target.value; })} />
                          )}
                          {s.when && s.when.op !== "TRUE" && (
                            <Input className="w-44" placeholder="比较值" value={s.when.value ?? ""} aria-invalid={!!err(p("when.value"))}
                              onChange={(e) => setStep(i, (x) => { x.when!.value = e.target.value; })} />
                          )}
                        </div>
                      </Field>
                    </div>
                  </div>
                );
              })}
              <Button variant="secondary" onClick={() => set((d) => { d.steps.push(blankStep()); })}><Plus className="size-4" /> 添加步骤</Button>
            </div>

            <div className="mt-5 space-y-2">
              <Rule label="提交人不能审自己" checked={!editing.def.rules.selfApproval}
                onChange={(v) => set((d) => { d.rules.selfApproval = !v; })} />
              <Rule label="驳回必填原因" locked />
              <Rule label="跨步互斥（后步不能由前步决定人办）" checked={editing.def.rules.crossStepExclusive}
                onChange={(v) => set((d) => { d.rules.crossStepExclusive = v; })} />
            </div>

            <div className="mt-5 flex flex-wrap items-end gap-3">
              <Field label="版本说明" className="min-w-60 flex-1">
                <Input value={note} placeholder="这次改了什么（进版本历史），如「会签时限 48h → 24h」" onChange={(e) => setNote(e.target.value)} />
              </Field>
              <Button variant="secondary" onClick={() => { setEditing(null); setErrors([]); }}>关闭</Button>
              <Button loading={publish.isPending} onClick={() => publish.mutate()}>保存并发布</Button>
            </div>
          </Card>

          <div className="space-y-4">
            {!editing.isNew && (
              <Card className="p-5">
                <h2 className="mb-3 txt-strong">版本历史</h2>
                <FlowTimeline
                  empty={versions.isLoading ? "加载中…" : "还没有版本"}
                  items={(versions.data ?? []).map((v) => ({
                    key: v.version,
                    title: <span className="font-mono">{v.version}{v.current ? " · 当前" : ""}</span>,
                    meta: `${fmtShort(v.atMs)} · ${v.channel === "API" ? "API 登记" : "控制台"} · ${v.by}`,
                    detail: v.note,
                  }))}
                />
              </Card>
            )}
            <Card className="p-5">
              <h2 className="mb-2 txt-strong">改动的边界</h2>
              <ul className="list-disc space-y-1 ps-5 txt-body text-muted-foreground">
                <li>版本 = 内容哈希，只增不改</li>
                <li>在途审批按提交时版本走完</li>
                <li>审批人只能选五种：权限码 / 角色 / 组 / 成员 / 提交时字段指定</li>
                <li>会签只能用于成员 / 组 / 字段指定（角色、权限码的人选不可枚举）</li>
                <li>停用流程 = 在途走完后不再接受新提交（不删除）</li>
              </ul>
            </Card>
          </div>
        </div>
      )}
      {dialog}
    </div>
  );
}

function Field({ label, error, help, className, children }: { label: string; error?: string; help?: string; className?: string; children: React.ReactNode }) {
  return (
    <label className={["block", className].filter(Boolean).join(" ")}>
      <span className="mb-1 block txt-label text-muted-foreground">{label}</span>
      {children}
      {error ? <span className="mt-1 block txt-caption text-destructive-ink">{error}</span>
        : help ? <span className="mt-1 block txt-caption text-muted-foreground">{help}</span> : null}
    </label>
  );
}

function Rule({ label, locked, checked, onChange }: { label: string; locked?: boolean; checked?: boolean; onChange?: (v: boolean) => void }) {
  return (
    <div className="flex items-center gap-3">
      <Switch checked={locked ? true : !!checked} disabled={locked} onChange={onChange} aria-label={label} />
      <span className="txt-body">{label}</span>
      {locked && <span className="txt-caption text-muted-foreground">（不可关）</span>}
    </div>
  );
}

export default function FlowProcessesPage() {
  return <NeedPerm code="flow:process:manage" what="流程配置"><FlowProcesses /></NeedPerm>;
}
