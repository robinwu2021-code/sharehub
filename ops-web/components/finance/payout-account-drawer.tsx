"use client";

// 收款账户的**唯一**维护入口（表单 + 写接口 + 缓存失效）。
//
// ── 为什么抽成组件 ──────────────────────────────────────────────
// 本仓库有一条卡口：「同一个写操作 API 不被两个页面调用 —— 一份数据只留一个维护入口」。
// 它的理由不是洁癖：2026-09-23 实测过，站点 / 收费方案 / 银行等各被做了第二份页面调同一组 API，
// 一年没人发现，直到有人去改表单、在两个文件里各改了一遍才暴露。
//
// 而「场地方那一行就地设置收款账户」是个正当需求（见下），于是就有了两个**入口**。
// 解法不是绕开卡口，是让两个入口用**同一份实现** —— 表单字段、请求组装、
// 缓存失效全在这里，页面只负责「什么时候打开、绑给谁」。
//
// ── 为什么场地方那一行要能就地设置 ──────────────────────────────
// 原先那一列是个指向 `/finance?tab=payout-accounts` 的链接。点过去之后人要在几百条
// 账户里自己找回刚才那个场地方、还要**手打一遍它的编号** —— 而编号恰恰是最容易打错、
// 又最看不出打错了的东西：打错了就是钱打给别人，且事后对账才发现。
// 绑定模式下受益方由调用方带进来，表单里根本没有那两格。
import * as React from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import type { PayoutAccount } from "@/lib/types";

/** 抽屉里的表单值。`payeeType`/`payeeNo` 在绑定模式下由调用方预置且不出现在表单里。 */
export type PayoutFormValue = Record<string, unknown> | null;

/**
 * 账号本体那几格 —— 两种模式共用。
 *
 * @form POST /api/trade/payout-accounts
 */
const ACCOUNT_FIELDS: FieldDef[] = [
  { key: "bankCode", label: "开户行", required: true, placeholder: "如 ENBD / ADCB / FAB" },
  { key: "accountName", label: "户名", required: true,
    placeholder: "须与主体法人名一致，否则银行会退回" },
  { key: "accountMasked", label: "账号 / IBAN", required: true,
    placeholder: "填完整账号；系统只保存后四位",
    help: "改已有账户时这一格是空的 —— 系统存的是掩码，回填掩码会把掩码当成新账号存进去" },
  { key: "currency", label: "币种", type: "select",
    options: [{ value: "AED", label: "AED" }, { value: "SAR", label: "SAR" }] },
];

/**
 * 受益方那两格 —— 只有「从财务页的大表里新建」时才需要人来选。
 *
 * @form POST /api/trade/payout-accounts
 */
const PAYEE_FIELDS: FieldDef[] = [
  { key: "payeeType", label: "受益方类型", type: "select", required: true,
    options: [{ value: "AGENT", label: "代理商" }, { value: "VENUE", label: "场地方" }] },
  { key: "payeeNo", label: "受益方编号", required: true, placeholder: "如 AG001 / VEN300" },
];

/**
 * @form POST /api/trade/payout-accounts
 */
export const PAYOUT_FIELDS: FieldDef[] = [...PAYEE_FIELDS, ...ACCOUNT_FIELDS];

export function PayoutAccountDrawer({
  value, onOpenChange, boundTo, onSaved,
}: {
  value: PayoutFormValue;
  onOpenChange: (open: boolean) => void;
  /**
   * 绑定模式：受益方由调用方定死，表单里不出现那两格。
   * 不传 = 通用模式（财务页那张大表用），受益方由人来选。
   */
  boundTo?: { payeeType: PayoutAccount["payeeType"]; payeeNo: string; label: string };
  /** 保存成功后的额外动作（如关掉某个局部状态）。缓存失效本组件已经做了。 */
  onSaved?: () => void;
}) {
  const qc = useQueryClient();
  const [form, setForm] = React.useState<PayoutFormValue>(value);
  React.useEffect(() => setForm(value), [value]);

  const save = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.savePayoutAccount({
      accountNo: v.accountNo ? String(v.accountNo) : undefined,
      // 绑定模式下受益方来自 props，**不从表单取** —— 表单里根本没有那两格
      payeeType: boundTo?.payeeType ?? ((v.payeeType as PayoutAccount["payeeType"]) ?? "AGENT"),
      payeeNo: boundTo?.payeeNo ?? String(v.payeeNo ?? ""),
      bankCode: String(v.bankCode ?? ""),
      accountName: String(v.accountName ?? ""),
      accountMasked: String(v.accountMasked ?? ""),
      currency: v.currency ? String(v.currency) : undefined,
      /*
       * 绑定模式一律置默认：从场地方那一行点进来建的就是「这个场地方的收款账户」，
       * 不置默认等于建了不生效 —— 结算照样打不出去，而界面上看着已经设好了。
       * 通用模式沿用表单里的勾选。
       */
      makeDefault: boundTo ? true : v.makeDefault === true,
    }),
    onSuccess: () => {
      notify.success("收款账户已保存");
      // 三处读同一份数据：财务页的账户表、场地方页的那一列、提现审批的放行判断
      qc.invalidateQueries({ queryKey: ["fin", "payout-accounts"] });
      qc.invalidateQueries({ queryKey: ["venue-payout-accounts"] });
      qc.invalidateQueries({ queryKey: ["fin", "withdrawals"] });
      onOpenChange(false);
      onSaved?.();
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const isEdit = !!form?.accountNo;
  return (
    <FormDrawer
      open={!!form}
      onOpenChange={onOpenChange}
      titleNew={boundTo ? `设置收款账户 · ${boundTo.label}` : "新增收款账户"}
      titleEdit={boundTo ? `更换收款账户 · ${boundTo.label}` : `编辑收款账户 ${form?.accountNo ?? ""}`}
      isEdit={isEdit}
      fields={boundTo ? ACCOUNT_FIELDS : PAYOUT_FIELDS}
      value={(form ?? {}) as Record<string, unknown>}
      onChange={(v) => setForm(v as Record<string, unknown>)}
      onSubmit={() => form && save.mutate(form)}
      submitting={save.isPending}
    />
  );
}
