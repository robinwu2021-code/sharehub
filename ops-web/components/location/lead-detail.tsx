"use client";

// 商机详情抽屉（方案 §三 P1）：DetailHeader（阶段 + 步骤条 + 动作）· 档案 · 谈判条款 · 丢单 · 转化结果 · 跟进时间线。
//
// **阶段只经动作改**（R1）：
//   记一条跟进时顺带推进（只给状态机允许的下一步）· 标记丢单（原因必填）· 签约转化 · 重新激活。
// 编辑表单里没有阶段 —— 此前那里是一个可以随便选的下拉，于是「阶段变了却查不到是谁推的」。
//
// 公共线索池里的商机**没有负责人**：先认领才能跟进 / 编辑 / 转化（与后端同规则）。
import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { RECENT_LIMIT, UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import type { Lead, LeadStage, LeadFollowChannel, LeadConvertReq } from "@/lib/types";
import { LEAD_FOLLOW_CHANNELS, LEAD_TRANSITIONS, leadNextStages } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge } from "@/components/ui/status-badge";
import { Timeline } from "@/components/ui/timeline";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { DateInput } from "@/components/ui/date-input";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Skeleton, EmptyState } from "@/components/ui/misc";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StateActions, type ActionSpec } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { LEAD_STAGE, LEAD_STEPS, FOLLOW_CHANNEL_LABEL, SHARE_MODE_LABEL } from "./lead-meta";

const PERM_CRM = "location:crm:update";
const PERM_FOLLOW = "location:lead:update";

const SHARE_MODE_OPTS = (Object.keys(SHARE_MODE_LABEL) as (keyof typeof SHARE_MODE_LABEL)[])
  .map((k) => ({ value: k, label: SHARE_MODE_LABEL[k] }));

/** 关联了已有站点时，新建站点的那几项不再有意义（清空并禁用，免得提交脏值）。 */
const hasSite = (v: Record<string, unknown>) => !!String(v.siteNo ?? "").trim();

/**
 * 签约转化表单。场地方 / 站点**留空 = 新建**（场地方按商机的场地名，站点为筹备中），
 * 选了就关联已有的（站点须属于所选场地方）。条款留空 = 取商机上谈下来的。
 */
/** @form POST /api/ops/leads/{leadNo}/convert */
const convertFields = (
  venues: { value: string; label: string }[],
  sites: { value: string; label: string }[],
  regions: { value: string; label: string }[],
): FieldDef[] => [
  { key: "venueNo", label: "场地方", type: "select", section: "场地方与站点",
    options: [{ value: "", label: "新建（按商机的场地名）" }, ...venues],
    help: "已有档案就选上 —— 同一家商场建两份场地方，分成会分到两个户头" },
  { key: "siteNo", label: "站点", type: "select", section: "场地方与站点",
    options: [{ value: "", label: "新建筹备中的站点" }, ...sites],
    help: "只列所选场地方名下的站点；新建的站点要走完开业清单（勘测 / 点位 / 责任人 / 营业时间）才能营业" },
  { key: "siteName", label: "新站点名称", maxLength: 128, section: "场地方与站点", placeholder: "留空 = 场地名",
    disabledWhen: hasSite },
  { key: "regionId", label: "区域（新建站点必填）", type: "select", section: "场地方与站点",
    options: [{ value: "", label: "请选择区域" }, ...regions], disabledWhen: hasSite },
  { key: "address", label: "地址", maxLength: 256, section: "场地方与站点", disabledWhen: hasSite },
  { key: "openHours", label: "营业时间（新建站点必填）", section: "场地方与站点", placeholder: "10:00-22:00",
    disabledWhen: hasSite },
  { key: "shareMode", label: "分成模式", type: "select", section: "合同草稿条款", options: SHARE_MODE_OPTS },
  { key: "shareRate", label: "分成比例（0~1）", type: "number", min: 0, max: 1, section: "合同草稿条款",
    help: "0.15 = 15%。填 15 就是十五倍分成，分完才会被发现" },
  { key: "entryFee", label: "进场费", type: "number", min: 0, section: "合同草稿条款" },
  { key: "guaranteeAmount", label: "保底金额", type: "number", min: 0, section: "合同草稿条款" },
  { key: "startAt", label: "生效日", type: "date", section: "合同草稿条款", help: "留空 = 今天" },
  { key: "termMonths", label: "期限（月）", type: "number", min: 1, section: "合同草稿条款", help: "留空 = 12 个月" },
  { key: "exclusive", label: "独家", type: "switch", section: "合同草稿条款" },
];

export function LeadDetailDrawer({
  leadNo, onOpenChange, onEdit,
}: {
  leadNo: string | null;
  onOpenChange: (open: boolean) => void;
  /** 打开编辑抽屉（档案字段）。编辑表单在列表页，与新建共用。 */
  onEdit: (lead: Lead) => void;
}) {
  const qc = useQueryClient();
  const allow = useCan();
  const open = !!leadNo;

  const leadQ = useQuery({ queryKey: ["lead", leadNo], queryFn: () => api.getLead(leadNo!), enabled: open });
  const followQ = useQuery({
    queryKey: ["lead-follow-ups", leadNo],
    queryFn: () => api.listLeadFollowUps(leadNo!, { size: RECENT_LIMIT }),
    enabled: open,
  });
  const lead = leadQ.data;

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["lead", leadNo] });
    qc.invalidateQueries({ queryKey: ["lead-follow-ups", leadNo] });
    qc.invalidateQueries({ queryKey: ["venue-bd"] });
    qc.invalidateQueries({ queryKey: ["lead-summary"] });
  };

  // —— 记一条跟进 ——
  const [fuChannel, setFuChannel] = useState<LeadFollowChannel>("CALL");
  const [fuContent, setFuContent] = useState("");
  const [fuStage, setFuStage] = useState<LeadStage | "">("");
  const [fuNextAt, setFuNextAt] = useState("");
  const resetFollow = () => { setFuContent(""); setFuStage(""); setFuNextAt(""); };
  const addFollow = useMutation({
    mutationFn: (v: { toStage?: LeadStage; content: string; channel: LeadFollowChannel; nextAt?: string }) =>
      api.addLeadFollowUp(leadNo!, v),
    onSuccess: (r) => {
      refresh();
      notify.success(r.fromStage && r.fromStage !== r.toStage
        ? `已记跟进，阶段 ${LEAD_STAGE[r.fromStage].label} → ${LEAD_STAGE[r.toStage].label}`
        : "已记跟进");
      resetFollow();
      setLostOpen(false);
    },
  });

  // —— 标记丢单 ——
  const [lostOpen, setLostOpen] = useState(false);
  const [lostReason, setLostReason] = useState("");
  const [competitor, setCompetitor] = useState("");
  const [exclusiveUntil, setExclusiveUntil] = useState("");
  const markLost = useMutation({
    mutationFn: async () => {
      // 竞品信息先落（它是档案字段），再推阶段：反过来的话丢单成功而竞品没存上，
      // 「独家到期前自动重新激活」就永远不会触发
      if (competitor.trim() || exclusiveUntil) {
        await api.saveLead({ leadNo: leadNo!, competitorName: competitor.trim() || null, competitorExclusiveUntil: exclusiveUntil || null });
      }
      return api.addLeadFollowUp(leadNo!, { channel: "OTHER", content: lostReason.trim(), toStage: "LOST" });
    },
    onSuccess: () => { refresh(); notify.success("已标记丢单"); setLostOpen(false); },
  });

  // —— 认领 ——
  const claim = useMutation({
    mutationFn: () => api.claimLead(leadNo!),
    onSuccess: () => { refresh(); notify.success("已认领，这条商机现在由你负责"); },
  });

  // —— 签约转化 ——
  const [convertForm, setConvertForm] = useState<Record<string, unknown> | null>(null);
  const venuesQ = useQuery({
    queryKey: ["venues-dict"], queryFn: () => api.listVenues({ page: 1, size: UNPAGED_SIZE }), enabled: !!convertForm,
  });
  const sitesQ = useQuery({
    queryKey: ["sites-dict"], queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }), enabled: !!convertForm,
  });
  const regionsQ = useQuery({
    queryKey: ["op", "region-options"], queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }), enabled: !!convertForm,
  });
  const fields = useMemo(() => {
    const vNo = String(convertForm?.venueNo ?? "");
    const venues = (venuesQ.data?.list ?? []).filter((v) => !v.archivedAt)
      .map((v) => ({ value: v.venueNo, label: `${v.name}（${v.venueNo}）` }));
    // 场地方没选时不列站点：新建的场地方名下必然没有站点，列出来只会被误选
    const sites = vNo
      ? (sitesQ.data?.list ?? []).filter((s) => s.venueNo === vNo && s.status !== "CLOSED")
          .map((s) => ({ value: s.siteNo, label: `${s.name}（${s.siteNo}）` }))
      : [];
    const regions = (regionsQ.data?.list ?? []).filter((r) => r.level === 3)
      .map((r) => ({ value: r.regionId, label: `${r.name}（${r.regionId}）` }));
    return convertFields(venues, sites, regions);
  }, [convertForm?.venueNo, venuesQ.data, sitesQ.data, regionsQ.data]);
  const convert = useMutation({
    mutationFn: (req: LeadConvertReq) => api.convertLead(leadNo!, req),
    onSuccess: (r) => {
      refresh();
      qc.invalidateQueries({ queryKey: ["venues-dict"] });
      qc.invalidateQueries({ queryKey: ["sites-dict"] });
      qc.invalidateQueries({ queryKey: ["contract-summary"] });
      notify.success(`已生成合同草稿 ${r.contractNo}（场地方 ${r.venueNo}${r.venueCreated ? " 新建" : ""} · 站点 ${r.siteNo}${r.siteCreated ? " 新建" : ""}）`);
      setConvertForm(null);
    },
  });
  const openConvert = (l: Lead) => setConvertForm({
    venueNo: l.venueNo ?? "", siteNo: l.siteNo ?? "", address: l.address ?? "",
    shareMode: l.terms?.shareMode ?? "SHARE", shareRate: l.terms?.shareRate ?? undefined,
    entryFee: l.terms?.entryFee ?? undefined, guaranteeAmount: l.terms?.guaranteeAmount ?? undefined,
    termMonths: l.terms?.termMonths ?? 12, exclusive: !!l.terms?.exclusive,
  });
  const submitConvert = () => {
    if (!convertForm) return;
    const f = convertForm;
    const str = (k: string) => (String(f[k] ?? "").trim() || undefined);
    const num = (k: string) => (f[k] === "" || f[k] == null ? undefined : Number(f[k]));
    // 新建站点与「站点管理 › 新增」同口径：区域与营业时间必填（服务端也拦，这里先省一次往返）
    if (!str("siteNo") && (!str("regionId") || !str("openHours"))) {
      notify.error("新建站点需要区域与营业时间 —— 营业时间决定离线告警是否计时");
      return;
    }
    convert.mutate({
      venueNo: str("venueNo"), siteNo: str("siteNo"), siteName: str("siteName"), regionId: str("regionId"),
      address: str("address"), openHours: str("openHours"),
      shareMode: str("shareMode") as LeadConvertReq["shareMode"], shareRate: num("shareRate"),
      entryFee: num("entryFee"), guaranteeAmount: num("guaranteeAmount"), startAt: str("startAt"),
      termMonths: num("termMonths"), exclusive: !!f.exclusive,
    });
  };

  const follows = followQ.data?.list ?? [];
  // 丢单是从哪一步岔出去的：取最后一条推到 LOST 的跟进；取不到就当从新线索岔出
  const lostFrom = follows.find((x) => x.toStage === "LOST" && x.fromStage && x.fromStage !== "LOST")?.fromStage ?? "NEW";

  const actions: ActionSpec[] = lead ? [
    {
      key: "claim", label: "认领", primary: true, perm: PERM_CRM, when: !!lead.inPool,
      confirm: { title: "认领这条商机？", desc: "认领后由你负责，从今天起重新计时；超期不跟进会再次回收进线索池。" },
      onRun: () => claim.mutateAsync(),
    },
    {
      key: "convert", label: "签约转化", primary: true, perm: PERM_CRM,
      // SIGNED 但还没转化（补录的历史商机）也能转；已转化的不再给
      when: !lead.inPool && !lead.contractNo
        && (LEAD_TRANSITIONS.sign.from.includes(lead.stage) || lead.stage === "SIGNED"),
      onRun: () => openConvert(lead),
    },
    {
      key: "lose", label: "标记丢单", danger: true, perm: PERM_FOLLOW,
      when: !lead.inPool && LEAD_TRANSITIONS.lose.from.includes(lead.stage),
      onRun: () => { setLostReason(""); setCompetitor(lead.competitorName ?? ""); setExclusiveUntil(lead.competitorExclusiveUntil ?? ""); setLostOpen(true); },
    },
    {
      key: "reactivate", label: "重新激活", perm: PERM_FOLLOW,
      when: !lead.inPool && LEAD_TRANSITIONS.reactivate.from.includes(lead.stage),
      confirm: { title: "重新激活这条商机？", desc: "阶段回到「新线索」，丢单原因保留作历史。竞品独家到期前系统也会自动激活。" },
      onRun: () => addFollow.mutateAsync({ channel: "OTHER", content: "重新激活，重新接触", toStage: "NEW" }),
    },
    {
      key: "edit", label: "编辑档案", perm: PERM_CRM, when: !lead.inPool,
      onRun: () => onEdit(lead),
    },
  ] : [];

  const canFollow = allow(PERM_FOLLOW);
  // 顺带推进只给状态机允许的下一步；丢单与签约各有自己的动作（要收原因 / 要生成合同），不混在下拉里
  const nextStages = lead ? leadNextStages(lead.stage).filter((s) => s !== "LOST" && s !== "SIGNED") : [];

  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={lead ? `商机 · ${lead.venueName}` : "商机详情"}
      desc="阶段只经动作改：记跟进时顺带推进、标记丢单、签约转化。跟进记录只增不改"
      width="w-[640px]"
    >
      {leadQ.isLoading && <Skeleton className="h-40" />}
      {!leadQ.isLoading && !lead && (
        <EmptyState title="商机不存在" desc="这条商机可能不在你的数据范围内，或编号有误。回到列表重新选择。" />
      )}
      {lead && (
        <>
          <DetailHeader
            no={lead.leadNo}
            title={lead.venueName}
            badge={<>
              <StatusBadge map={LEAD_STAGE} value={lead.stage} />
              {lead.inPool && <span className="txt-caption text-warning-ink">在公共线索池</span>}
            </>}
            meta={lead.inPool
              ? `无负责人${lead.prevOwner ? ` · 原负责人 ${lead.prevOwner}` : ""} · 认领后才能跟进`
              : `负责人 ${lead.owner ?? "-"}${lead.ownerType === "AGENT" ? "（伙伴）" : ""} · 最后跟进 ${fmtTime(lead.lastFollowAt ?? lead.updatedAt)}`}
            stepper={<StatusStepper steps={LEAD_STEPS} current={lead.stage}
              branch={lead.stage === "LOST" ? { label: "已丢单", after: lostFrom } : null} />}
            actions={<StateActions actions={actions} />}
            className="mb-4"
          />

          <div className="grid grid-cols-2 gap-x-4">
            <Field label="联系人">{lead.contact || <span className="text-muted-foreground">未填</span>}</Field>
            <Field label="预计站点数"><span className="tabular-nums">{lead.expectSites}</span></Field>
            <Field label="地址" className="col-span-2">{lead.address || <span className="text-muted-foreground">未填 —— 查重按场地名或地址判，地址空着就只能按名字查</span>}</Field>
            <Field label="下次跟进">{lead.nextFollowAt?.slice(0, 10) || <span className="text-muted-foreground">未约</span>}</Field>
            <Field label="落成站点"><RefLink kind="site" no={lead.siteNo} /></Field>
          </div>

          {lead.terms && (
            <Field label="谈判条款（转化时带进合同草稿）">
              <span>{lead.terms.shareMode ? SHARE_MODE_LABEL[lead.terms.shareMode] : "模式未定"}</span>
              {lead.terms.shareRate != null && <span className="ms-2 tabular-nums">{(lead.terms.shareRate * 100).toFixed(0)}%</span>}
              {lead.terms.entryFee != null && <span className="ms-2 tabular-nums">进场费 {lead.terms.entryFee}</span>}
              {lead.terms.guaranteeAmount != null && <span className="ms-2 tabular-nums">保底 {lead.terms.guaranteeAmount}</span>}
              {lead.terms.termMonths != null && <span className="ms-2 tabular-nums">{lead.terms.termMonths} 个月</span>}
              {lead.terms.exclusive && <span className="ms-2">独家</span>}
            </Field>
          )}

          {(lead.stage === "LOST" || lead.lostReason || lead.competitorName) && (
            <Field label="丢单">
              <div>{lead.lostReason || <span className="text-muted-foreground">未记录原因</span>}</div>
              <div className="txt-caption text-muted-foreground">
                {lead.lostAt && <>丢单于 {fmtTime(lead.lostAt)}</>}
                {lead.competitorName && <> · 竞品 {lead.competitorName}</>}
                {lead.competitorExclusiveUntil && <> · 独家到 {lead.competitorExclusiveUntil.slice(0, 10)}（到期前会自动重新激活）</>}
                {lead.reactivatedAt && <> · 曾于 {fmtTime(lead.reactivatedAt)} 重新激活</>}
              </div>
            </Field>
          )}

          {lead.contractNo && (
            <Field label="签约转化">
              场地方 <span className="tabular-nums">{lead.venueNo ?? "-"}</span>
              <span className="ms-3">站点 <RefLink kind="site" no={lead.siteNo} /></span>
              <span className="ms-3">合同草稿 <RefLink kind="contract" no={lead.contractNo} /></span>
            </Field>
          )}

          <Field label="跟进记录">
            <Timeline
              loading={followQ.isLoading}
              empty="还没有跟进记录 —— 这条商机还没人接触过，可在下方记第一条"
              items={follows.map((x) => ({
                key: x.followNo,
                badge: { label: FOLLOW_CHANNEL_LABEL[x.channel] ?? x.channel, tone: "outline" as const },
                meta: `${fmtTime(x.createdAt)} · ${x.owner ?? "系统"}${x.nextAt ? ` · 约下次 ${x.nextAt}` : ""}`,
                // 阶段没动的跟进不造「A → A」的假迁移
                change: x.fromStage && x.fromStage !== x.toStage
                  ? `${LEAD_STAGE[x.fromStage]?.label ?? x.fromStage} → ${LEAD_STAGE[x.toStage]?.label ?? x.toStage}`
                  : undefined,
                text: x.content,
              }))}
            />
          </Field>

          {lead.inPool ? (
            <Notice className="mt-2">这条商机在公共线索池里，没有负责人。先「认领」，再记跟进 —— 否则「谁在跟」又说不清了。</Notice>
          ) : !canFollow ? (
            <ReadOnlyNotice className="mt-2" what="商机跟进" perm={PERM_FOLLOW} note="不能记跟进，也不能推进阶段" />
          ) : (
            <div className="mt-2 space-y-3 border-t border-border pt-4">
              <div className="txt-label text-muted-foreground">记一条跟进</div>
              <div className="grid grid-cols-2 gap-3">
                <Select aria-label="跟进方式" value={fuChannel} onChange={(e) => setFuChannel(e.target.value as LeadFollowChannel)}>
                  {LEAD_FOLLOW_CHANNELS.map((c) => <option key={c} value={c}>{FOLLOW_CHANNEL_LABEL[c]}</option>)}
                </Select>
                {/* 阶段与跟进同一次提交：先记录再改阶段会漏，改了阶段没记录更糟 */}
                <Select aria-label="顺带推进阶段" value={fuStage} onChange={(e) => setFuStage(e.target.value as LeadStage | "")}>
                  <option value="">不改阶段，仅记跟进</option>
                  {nextStages.map((s) => <option key={s} value={s}>推进到「{LEAD_STAGE[s].label}」</option>)}
                </Select>
              </div>
              <Textarea rows={2} value={fuContent} onChange={setFuContent} placeholder="如：现场看点位，谈分成比例，对方要求月结" />
              <div className="flex items-center gap-3">
                <span className="txt-caption text-muted-foreground">约下次</span>
                <DateInput value={fuNextAt} onChange={(e) => setFuNextAt(e.target.value)} className="w-44" />
                <Button size="sm" className="ms-auto" disabled={addFollow.isPending || !fuContent.trim()}
                  onClick={() => addFollow.mutate({
                    channel: fuChannel, content: fuContent.trim(),
                    toStage: fuStage || undefined, nextAt: fuNextAt || undefined,
                  })}>
                  记录跟进
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      {/* 标记丢单：原因必填 + 竞品（给半年后的自己留提醒） */}
      <Drawer
        open={lostOpen}
        onOpenChange={setLostOpen}
        title={`标记丢单 ${lead?.leadNo ?? ""}`}
        desc="原因必填：它会记成一条跟进。填上竞品独家到期日，到期前系统会自动把这条商机重新激活"
        width="w-[480px]"
        footer={<>
          <Button variant="outline" onClick={() => setLostOpen(false)}>取消</Button>
          <Button variant="destructive" disabled={markLost.isPending || !lostReason.trim()} onClick={() => markLost.mutate()}>确认丢单</Button>
        </>}
      >
        <Field label="丢单原因（必填）">
          <Textarea rows={3} value={lostReason} onChange={setLostReason} placeholder="如：对方已与其他品牌签两年独家" />
        </Field>
        <Field label="竞品">
          <Input className="w-full" value={competitor} onChange={(e) => setCompetitor(e.target.value)} placeholder="丢给了哪家" />
        </Field>
        <Field label="竞品独家到期日">
          <DateInput value={exclusiveUntil} onChange={(e) => setExclusiveUntil(e.target.value)} />
        </Field>
      </Drawer>

      <FormDrawer
        open={!!convertForm}
        onOpenChange={(o) => !o && setConvertForm(null)}
        titleNew={`签约转化 ${lead?.leadNo ?? ""}`}
        titleEdit=""
        isEdit={false}
        fields={fields}
        value={convertForm ?? {}}
        onChange={setConvertForm}
        onSubmit={submitConvert}
        submitting={convert.isPending}
        width="w-[560px]"
      />
    </Drawer>
  );
}
