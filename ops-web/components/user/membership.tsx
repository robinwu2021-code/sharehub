"use client";

// 会员套餐（「卖什么」）与会员 / 次卡（「等级给什么」+ 名单 + 发卡）· OM04-18 / OM04-19。
//
// 2026-10-28 从「用户管理」搬到「销售管理 › 会员」（docs/requirements/运营端菜单清单-按运营顺序.md）：
// 会员是一种卖出去的商品与随之而来的权益，属于收入线；用户管理只管「谁在借、能不能借」。
// 内容原样搬出（查询 / 写操作 / 表单 / 抽屉），行为不变；用户页不再留第二份。
// 用户详情抽屉仍在用户页，名单里的「详情」跳 /users?no=… 打开它。
//
// 等级名、次卡状态两张映射表导出给用户详情抽屉用 —— 两处共用一份，等级名不会各写一套。
import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { MEMBER_CARD_LABEL, MEMBER_CARD_NONE } from "@/lib/types";
import type { Member, MemberBenefit, MemberPlan, MemberCard, MemberCardType } from "@/lib/types";

/** 会员三态。CANCELLED 与 EXPIRED 分开：前者是人主动退的，到期日还在未来。 */
const MEMBER_STATUS: StatusMap<Member["status"]> = {
  ACTIVE: { label: "有效", tone: "success" },
  EXPIRED: { label: "已到期", tone: "muted" },
  CANCELLED: { label: "已取消", tone: "warning" },
};

// 等级徽标：会员名单、权益表、用户详情抽屉三处共用这一份，等级名不会各写一套
export const LEVEL: StatusMap<Member["level"]> = {
  SILVER: { label: "白银", tone: "muted" },
  GOLD: { label: "黄金", tone: "warning" },
  PLATINUM: { label: "铂金", tone: "success" },
};
const BENEFIT_STATUS: StatusMap<MemberBenefit["status"]> = {
  ENABLED: { label: "启用", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};

// 会员表单不含「次卡类型 / 到期时间」：这两列由**次卡发放**派生（服务端也会剥掉这两个字段），
// 手改就能造出「名单说有月卡、次卡记录里一张都没有」的假象。要给卡走「发次卡」。
/**
 * @form POST /api/user/members
 * @form POST /api/user/members/{userNo}
 */
const MEMBER_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, required: true, placeholder: "U0001" },
  // 昵称/手机是**读出参里的展示值**（后端从 NicknameLookup 批量回填），不是这张表单能写的东西。
  { key: "level", label: "等级", type: "select", required: true, options: [
    { value: "SILVER", label: "白银" },
    { value: "GOLD", label: "黄金" },
    { value: "PLATINUM", label: "铂金" },
  ], help: "各等级的权益在上方「会员权益」里配置" },
  { key: "points", label: "积分", type: "number", required: true, min: 0 },
];

/** 方案两态。上下架走动作端点，故这里只是展示映射。 */
const PLAN_STATUS: StatusMap<MemberPlan["status"]> = {
  ENABLED: { label: "在售", tone: "success" },
  DISABLED: { label: "已下架", tone: "muted" },
};
const PLAN_CARD_TYPE: StatusMap<MemberPlan["cardType"]> = {
  MONTHLY: { label: "包月", tone: "info" },
  TIMES: { label: "次卡", tone: "info" },
  RIGHTS: { label: "权益卡", tone: "info" },
};

/**
 * 会员方案表单。
 *
 * @form POST /api/user/member-plans
 * @form-synthetic rentDiscount 权益在契约里是嵌套的 rights，表单字段是平的；提交时由 planPayloadOf 收回 rights
 * @form-synthetic freeMinutes 同 rentDiscount
 * @form-synthetic depositFree 同 rentDiscount
 *
 * **没有 status 字段** —— 上下架是动作端点（在售/下架按钮）。混进保存字段里，
 * 「下架一个方案」和「改个名字」就成了同一个动作，而其中一个会影响正在卖的东西。
 *
 * 权益三项在契约里是嵌套的 `rights`，但表单字段是平的 —— 在读写两端各拍一次扁，
 * 见 `planFormOf` / `planPayloadOf`。
 */
const PLAN_FIELDS: FieldDef[] = [
  { key: "name", label: "方案名", required: true, section: "基本" },
  { key: "nameEn", label: "英文名", section: "基本" },
  { key: "nameAr", label: "阿语名", section: "基本" },
  { key: "cardType", label: "卡型", type: "select", required: true, section: "基本", options: [
    { value: "MONTHLY", label: "包月" }, { value: "TIMES", label: "次卡" }, { value: "RIGHTS", label: "权益卡" },
  ] },
  { key: "price", label: "价格", type: "number", required: true, min: 0, section: "售价" },
  { key: "currency", label: "币种", section: "售价" },
  { key: "periodDays", label: "有效期（天）", type: "number", min: 0, section: "售价", help: "包月 / 权益卡用" },
  { key: "timesTotal", label: "总次数", type: "number", min: 0, section: "售价", help: "次卡用" },
  { key: "rentDiscount", label: "租金折扣", type: "number", min: 0.01, max: 1, section: "权益",
    help: "0.9 = 九折；留空 = 不打折。填的是折扣率，不是「几折」—— 填 9 会被服务端拒" },
  { key: "freeMinutes", label: "每单免费时长（分钟）", type: "number", min: 0, section: "权益" },
  { key: "depositFree", label: "免押金", type: "switch", section: "权益" },
  { key: "autoRenew", label: "支持自动续费", type: "switch", section: "其它" },
  { key: "sortNo", label: "排序", type: "number", min: 0, section: "其它" },
];

/** 方案行 → 平表单（权益三项从 rights 里提出来）。 */
const planFormOf = (p: MemberPlan): Record<string, unknown> => ({
  ...p, rentDiscount: p.rights?.rentDiscount, freeMinutes: p.rights?.freeMinutes, depositFree: p.rights?.depositFree,
});

/**
 * 平表单 → 保存入参（权益三项收回 rights）。
 *
 * 三项全空时 `rights` 传 null 而不是 `{}` —— 空对象与「没配权益」在读侧要是同一件事，
 * 否则「有 rights 但全是空」会被当成配过权益，而它上架后什么也不会发生。
 */
function planPayloadOf(f: Record<string, unknown>): Partial<MemberPlan> & { planNo?: string } {
  const { rentDiscount, freeMinutes, depositFree, status: _status, ...rest } = f as Record<string, unknown>;
  const rights: MemberPlan["rights"] = {
    ...(rentDiscount != null && rentDiscount !== "" ? { rentDiscount: Number(rentDiscount) } : {}),
    ...(freeMinutes != null && freeMinutes !== "" ? { freeMinutes: Number(freeMinutes) } : {}),
    ...(depositFree ? { depositFree: true } : {}),
  };
  return { ...(rest as Partial<MemberPlan>), rights: Object.keys(rights).length ? rights : null };
}

/**
 * 会员权益表单（「等级给什么」）。
 *
 * @form POST /api/user/member-benefits/{level}
 */
const BENEFIT_FIELDS: FieldDef[] = [
  { key: "rentDiscount", label: "租金折扣", type: "number", required: true, min: 0.1, max: 1, section: "计费权益",
    help: "0.9 = 九折；1 = 不打折。必须随等级变好（高档折扣不得高于低档），否则服务端拒绝" },
  { key: "freeMinutes", label: "每单免费时长（分钟）", type: "number", required: true, min: 0, section: "计费权益", help: "0 表示无免费时长" },
  { key: "depositFree", label: "免押金", type: "switch", section: "计费权益", help: "低档已免押时，高档不能取消免押" },
  { key: "monthlyCoupons", label: "每月赠券（张）", type: "number", required: true, min: 0, section: "增值权益" },
  { key: "pointsRate", label: "积分倍率", type: "number", required: true, min: 0, section: "增值权益", help: "消费 1 元累计的积分数" },
  { key: "upgradePoints", label: "升级所需积分", type: "number", required: true, min: 0, section: "升级门槛",
    help: "升到本级所需累计积分；必须严格高于低一档，否则两档分不出来" },
  { key: "status", label: "状态", type: "select", required: true, section: "升级门槛", options: [
    { value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" },
  ] },
];

// —— 次卡发放 ——
const CARD_TYPE_OPTIONS = (Object.keys(MEMBER_CARD_LABEL) as MemberCardType[])
  .map((k) => ({ value: k, label: MEMBER_CARD_LABEL[k] }));
export const CARD_STATUS: StatusMap<MemberCard["status"]> = {
  ACTIVE: { label: "生效中", tone: "success" },
  EXPIRED: { label: "已过期", tone: "muted" },
  REVOKED: { label: "已撤销", tone: "danger" },
};
/**
 * @form POST /api/user/member-cards
 */
const CARD_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", required: true, section: "发放对象", placeholder: "U3001",
    help: "必须是已注册用户；黑名单用户拒发（请先解除拉黑）" },
  { key: "cardType", label: "次卡类型", type: "select", required: true, section: "卡面", options: CARD_TYPE_OPTIONS,
    help: "月/季/年卡为时长卡（有效期内不限次）；「次卡」按次核销" },
  // 联动禁用：时长卡不限次，填次数没有意义（FormDrawer 会顺手清掉它的值）
  { key: "totalTimes", label: "总次数", type: "number", required: true, min: 1, section: "卡面",
    disabledWhen: (v) => v.cardType !== "TIMES", help: "仅「次卡」需要填；时长卡不限次" },
  { key: "validFrom", label: "生效日期", type: "date", required: true, section: "有效期" },
  { key: "validTo", label: "失效日期", type: "date", required: true, section: "有效期", help: "必须晚于生效日期" },
  { key: "note", label: "发放事由", required: true, maxLength: 60, section: "有效期",
    help: "必填：免费权益要能事后归责（同免费白名单的口径）" },
];

/** 会员套餐（「卖什么」）。此前 mbr_plan 没有任何写入方，C 端会员页因此永远是空列表。 */
export function MemberPlansTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const canEditMember = allow("user:member:update");
  const [planForm, setPlanForm] = useState<Record<string, unknown> | null>(null);
  const plans = useQuery({
    queryKey: ["member-plans"],
    queryFn: () => api.listMemberPlans({ size: UNPAGED_SIZE }),
  });
  const savePlan = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.saveMemberPlan(planPayloadOf(v)),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ["member-plans"] });
      // 新建默认下架 —— 说清楚，否则运营会以为建完就在卖了
      notify.success(p.status === "ENABLED" ? `${p.name} 已保存` : `${p.name} 已保存（未上架）`);
      setPlanForm(null);
    },
  });
  const togglePlan = useMutation({
    mutationFn: (v: { planNo: string; enable: boolean }) =>
      v.enable ? api.enableMemberPlan(v.planNo) : api.disableMemberPlan(v.planNo),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ["member-plans"] });
      notify.success(p.status === "ENABLED" ? `${p.name} 已上架` : `${p.name} 已下架`);
    },
  });

  const planCols: Column<MemberPlan>[] = [
    { header: "方案号", cell: (p) => <span className="txt-strong tabular-nums">{p.planNo}</span> },
    { header: "名称", cell: (p) => p.name },
    { header: "卡型", cell: (p) => <StatusBadge map={PLAN_CARD_TYPE} value={p.cardType} /> },
    { header: "价格", className: "text-right", cell: (p) => <span className="tabular-nums">{p.price} {p.currency ?? ""}</span> },
    {
      header: "期限",
      className: "text-right",
      // 包月看天数、次卡看次数 —— 两列各自大半是空的，合成一列读起来才连贯
      cell: (p) => <span className="tabular-nums">{p.cardType === "TIMES" ? (p.timesTotal ? `${p.timesTotal} 次` : "-") : (p.periodDays ? `${p.periodDays} 天` : "-")}</span>,
    },
    {
      header: "权益",
      // 折扣按「几折」念（运营就是这么说话的），与会员权益那张表同一口径
      cell: (p) => {
        const r = p.rights;
        const parts = [
          r?.rentDiscount != null && r.rentDiscount < 1 ? `${(r.rentDiscount * 10).toFixed(1)} 折` : null,
          r?.freeMinutes ? `免费 ${r.freeMinutes} 分钟` : null,
          r?.depositFree ? "免押" : null,
        ].filter(Boolean);
        // 空权益必须看得见地写出来 —— 它是「不能上架」的原因，写「-」运营会以为是没配满
        return parts.length ? <span>{parts.join(" · ")}</span>
          : <span className="text-muted-foreground">未配权益（不能上架）</span>;
      },
    },
    { header: "自动续费", cell: (p) => p.autoRenew ? <Badge tone="info">支持</Badge> : <span className="text-muted-foreground">否</span> },
    { header: "状态", cell: (p) => <StatusBadge map={PLAN_STATUS} value={p.status} /> },
    {
      header: t("common.actions"),
      cell: (p) => !canEditMember ? <span className="text-muted-foreground">-</span> : (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setPlanForm(planFormOf(p))}>{t("common.edit")}</Button>
          <Button
            size="sm"
            variant="outline"
            disabled={togglePlan.isPending}
            onClick={() => togglePlan.mutate({ planNo: p.planNo, enable: p.status !== "ENABLED" })}
          >
            {p.status === "ENABLED" ? "下架" : "上架"}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      {!canEditMember && <ReadOnlyNotice what="会员套餐维护" perm="user:member:update" note="不能增改方案，也不能上下架" />}
      {/*
        方案（「卖什么」）与会员权益（「等级给什么」）是**两件正交的事**：
        等级来自积分成长，卡型来自买了哪个方案。两者的权益由后端逐项取更优，
        不是相互覆盖 —— 白银用户买了次卡，不该因此丢掉等级折扣。
      */}
      <div className="mb-2 flex items-baseline gap-2">
        <h2 className="text-sm font-medium">会员套餐</h2>
        <span className="text-xs text-muted-foreground">
          新建默认下架；未配权益的方案不能上架 —— 上架后 C 端看到「有价格、没权益」的卡片，买了什么也不会发生
        </span>
      </div>
      <Toolbar
        onAdd={canEditMember ? () => setPlanForm({ cardType: "MONTHLY", price: 0, currency: "AED", sortNo: 0 }) : undefined}
        addLabel="新增套餐"
      />
      <DataTable
        rowKey={(p: MemberPlan) => p.planNo}
        columns={planCols}
        rows={plans.data?.list}
        loading={plans.isLoading} error={plans.error} onRetry={plans.refetch}
        rowClassName={(p: MemberPlan) => (p.status === "ENABLED" ? undefined : "opacity-60")}
        empty="还没有会员套餐——C 端会员页会是空的，先在这里建一个并上架"
      />
      <FormDrawer
        open={!!planForm}
        onOpenChange={(o) => !o && setPlanForm(null)}
        titleNew="新增会员套餐"
        titleEdit={`编辑套餐 · ${String(planForm?.name ?? "")}`}
        isEdit={!!planForm?.planNo}
        fields={PLAN_FIELDS}
        value={planForm ?? {}}
        onChange={(v) => setPlanForm(v as Record<string, unknown>)}
        onSubmit={() => planForm && savePlan.mutate(planForm)}
        submitting={savePlan.isPending}
      />
    </>
  );
}

/** 会员 / 次卡：会员权益（按等级）+ 会员名单 + 发放次卡。 */
export function MembersTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const canEditMember = allow("user:member:update");

  const members = useQuery({
    queryKey: ["members", paging.page, paging.size, keyword],
    queryFn: () => api.listMembers({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
  });
  const [memberForm, setMemberForm] = useState<Partial<Member> | null>(null);
  const saveMember = useMutation({
    mutationFn: (v: Partial<Member>) => api.saveMember(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["members"] }); notify.success(t("common.success")); setMemberForm(null); },
  });

  // —— 会员权益：固定三档，只改不增 ——
  const [benefitForm, setBenefitForm] = useState<Partial<MemberBenefit> | null>(null);
  const benefits = useQuery({
    queryKey: ["member-benefits"],
    queryFn: () => api.listMemberBenefits({ size: UNPAGED_SIZE }),
  });
  const saveBenefit = useMutation({
    mutationFn: (v: Partial<MemberBenefit> & { level: MemberBenefit["level"] }) => api.saveMemberBenefit(v),
    // 权益改了会员名单本身不变，但等级口径变了，一并失效免得两处解释不一样
    onSuccess: (b) => {
      qc.invalidateQueries({ queryKey: ["member-benefits"] });
      qc.invalidateQueries({ queryKey: ["members"] });
      notify.success(`${b.name}权益已更新`);
      setBenefitForm(null);
    },
  });

  // —— 次卡发放 ——发完卡会员行的次卡/到期两列由服务端同步，故一并失效
  const [cardForm, setCardForm] = useState<Partial<MemberCard> | null>(null);
  const grantCard = useMutation({
    mutationFn: (v: Partial<MemberCard>) => api.grantMemberCard({
      userNo: (v.userNo ?? "").trim(),
      cardType: v.cardType as MemberCardType,
      totalTimes: v.totalTimes,
      validFrom: v.validFrom ?? "",
      validTo: v.validTo ?? "",
      note: v.note ?? "",
    }),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["members"] });
      // 用户详情抽屉里的「次卡」块与会员行都会变
      qc.invalidateQueries({ queryKey: ["user-profile"] });
      notify.success(`已发放${MEMBER_CARD_LABEL[r.card.cardType]} ${r.card.cardNo} · ${r.card.userNo}`);
      setCardForm(null);
    },
  });
  // FieldDef 管不了跨字段约束（有效期先后），故提交前补一道；服务端仍会独立校验
  const submitCard = () => {
    if (!cardForm) return;
    if (cardForm.validFrom && cardForm.validTo && cardForm.validTo <= cardForm.validFrom) {
      notify.error("失效日期必须晚于生效日期");
      return;
    }
    grantCard.mutate(cardForm);
  };

  const memberCols: Column<Member>[] = [
    { header: "用户号", cell: (m) => <span className="txt-strong tabular-nums">{m.userNo}</span> },
    { header: "昵称", cell: (m) => m.nickname },
    { header: "等级", cell: (m) => <StatusBadge map={LEVEL} value={m.level} /> },
    { header: "积分", className: "text-right", cell: (m) => <span className="tabular-nums">{Math.round(m.points)}</span> },
    // 次卡/到期两列由生效卡派生（发放时服务端同步），故这里只读、无卡弱化显示
    { header: "次卡", cell: (m) => m.cardType === MEMBER_CARD_NONE ? <span className="text-muted-foreground">{MEMBER_CARD_NONE}</span> : <Badge tone="outline">{m.cardType}</Badge> },
    {
      header: "状态",
      className: "whitespace-nowrap",
      // 放在「到期」之前：先看还有效没有，再看到期日。
      // CANCELLED 的到期日仍在未来 —— 只看到期时间会以为它还有效。
      cell: (m) => <StatusBadge map={MEMBER_STATUS} value={m.status} />,
    },
    { header: "到期", cell: (m) => <span className="text-muted-foreground">{fmtTime(m.expireAt)}</span> },
    {
      header: t("common.actions"),
      cell: (m) => (
        <div className="flex gap-2">
          {/* 用户详情抽屉在用户页：跳过去打开，而不是在这里再做一份 */}
          <Button size="sm" variant="outline" asChild>
            <Link href={`/users?no=${encodeURIComponent(m.userNo)}`}>详情</Link>
          </Button>
          {canEditMember && <Button size="sm" variant="outline" onClick={() => setMemberForm(m)}>{t("common.edit")}</Button>}
          {canEditMember && (
            <Button size="sm" variant="outline" onClick={() => setCardForm({ userNo: m.userNo, cardType: "MONTH", totalTimes: 10, validFrom: "", validTo: "", note: "" })}>
              发次卡
            </Button>
          )}
        </div>
      ),
    },
  ];

  const benefitCols: Column<MemberBenefit>[] = [
    // 等级徽标走同一张 LEVEL 表（而非 b.name）：名单行与权益行的等级名由此不可能对不上
    { header: "等级", cell: (b) => <StatusBadge map={LEVEL} value={b.level} /> },
    // 折扣按「几折」念，运营就是这么说话的；1 折不存在，故 1 单独显示「不打折」
    { header: "租金折扣", className: "text-right", cell: (b) => <span className="tabular-nums">{b.rentDiscount >= 1 ? "不打折" : `${(b.rentDiscount * 10).toFixed(1)} 折`}</span> },
    { header: "免费时长", className: "text-right", cell: (b) => <span className="tabular-nums">{b.freeMinutes > 0 ? `${b.freeMinutes} 分钟` : "-"}</span> },
    { header: "免押", cell: (b) => b.depositFree ? <Badge tone="success">免押</Badge> : <span className="text-muted-foreground">不免押</span> },
    { header: "每月赠券", className: "text-right", cell: (b) => <span className="tabular-nums">{b.monthlyCoupons > 0 ? `${b.monthlyCoupons} 张` : "-"}</span> },
    { header: "积分倍率", className: "text-right", cell: (b) => <span className="tabular-nums">{b.pointsRate}×</span> },
    { header: "升级积分", className: "text-right", cell: (b) => <span className="tabular-nums">{b.upgradePoints}</span> },
    { header: "状态", cell: (b) => <StatusBadge map={BENEFIT_STATUS} value={b.status} /> },
    { header: "更新", cell: (b) => <span className="text-muted-foreground">{fmtTime(b.updatedAt)} · {b.updatedBy}</span> },
    {
      header: t("common.actions"),
      cell: (b) => canEditMember
        ? <Button size="sm" variant="outline" onClick={() => setBenefitForm(b)}>{t("common.edit")}</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  return (
    <>
      {!canEditMember && <ReadOnlyNotice what="会员维护" perm="user:member:update" note="不能改权益、增改会员或发放次卡" />}
      {/*
        会员权益放在名单**上方**而不是另开一个 tab：等级列的含义就来自这张表，
        分开两个页面看，运营得来回切才知道「黄金」到底意味着什么。
        只有「改」没有「增删」——等级是固定三档，多一档权益没有会员能落进去。
      */}
      <div className="mb-2 flex items-baseline gap-2">
        <h2 className="text-sm font-medium">会员权益（按等级）</h2>
        <span className="text-xs text-muted-foreground">权益必须随等级变好：高档折扣不得高于低档、免押不可反悔、升级门槛严格递增</span>
      </div>
      <DataTable
        rowKey={(b: MemberBenefit) => b.level}
        columns={benefitCols}
        rows={benefits.data?.list}
        loading={benefits.isLoading} error={benefits.error} onRetry={benefits.refetch}
        rowClassName={(b: MemberBenefit) => (b.status === "ENABLED" ? undefined : "opacity-60")}
        empty="权益表为空——等级是固定三档，这里为空说明种子数据缺失"
      />
      <h2 className="mb-2 mt-6 text-sm font-medium">会员名单</h2>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索昵称 / 用户号"
        onAdd={canEditMember ? () => setMemberForm({ level: "SILVER", points: 0, nickname: "" }) : undefined}
        addLabel="新增会员"
        onExport={() => exportCsv<Member>("会员次卡", [
          { header: "用户号", value: (m) => m.userNo },
          { header: "昵称", value: (m) => m.nickname },
          { header: "等级", value: (m) => LEVEL[m.level].label },
          { header: "积分", value: (m) => Math.round(m.points) },
          { header: "次卡", value: (m) => m.cardType },
          { header: "到期", value: (m) => fmtTime(m.expireAt) },
        ], members.data?.list ?? [])}
      >
        {canEditMember && (
          <Button size="sm" variant="outline" onClick={() => setCardForm({ cardType: "MONTH", totalTimes: 10, userNo: "", validFrom: "", validTo: "", note: "" })}>
            发放次卡
          </Button>
        )}
      </Toolbar>
      <DataTable rowKey={(m: Member) => m.userNo} columns={memberCols} rows={members.data?.list} loading={members.isLoading} error={members.error} onRetry={members.refetch}
        empty="暂无会员/次卡——尚未有用户开通会员或购买次卡；点右上「新增会员」可手工登记，或「发放次卡」直接发卡" />
      {members.data && <Pagination page={paging.page} size={paging.size} total={members.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      <FormDrawer
        open={!!memberForm}
        onOpenChange={(o) => !o && setMemberForm(null)}
        titleNew="新增会员"
        titleEdit={`编辑会员 ${memberForm?.userNo ?? ""}`}
        isEdit={!!memberForm?.userNo}
        fields={MEMBER_FIELDS}
        value={(memberForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setMemberForm(v as Partial<Member>)}
        onSubmit={() => memberForm && saveMember.mutate(memberForm)}
        submitting={saveMember.isPending}
      />
      <FormDrawer
        open={!!benefitForm}
        onOpenChange={(o) => !o && setBenefitForm(null)}
        titleNew="会员权益"
        titleEdit={`编辑权益 · ${benefitForm?.name ?? ""}`}
        // 等级即主键，永远是「改」：isEdit 恒为 true，故不存在新增态
        isEdit
        fields={BENEFIT_FIELDS}
        value={(benefitForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setBenefitForm(v as Partial<MemberBenefit>)}
        onSubmit={() => benefitForm?.level && saveBenefit.mutate(benefitForm as Partial<MemberBenefit> & { level: MemberBenefit["level"] })}
        submitting={saveBenefit.isPending}
      />
      <FormDrawer
        open={!!cardForm}
        onOpenChange={(o) => !o && setCardForm(null)}
        titleNew="发放次卡"
        titleEdit="发放次卡"
        // 发放永远是新建一张卡（已发出的卡不在此处改），故不进编辑态
        isEdit={false}
        fields={CARD_FIELDS}
        value={(cardForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCardForm(v as Partial<MemberCard>)}
        onSubmit={submitCard}
        submitting={grantCard.isPending}
      />
    </>
  );
}
